package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.dfa.DataFlowVariable;
import kotlinbootstrap.dfa.Flow;
import kotlinbootstrap.dfa.Implication;
import kotlinbootstrap.dfa.LogicSystem;
import kotlinbootstrap.dfa.Operation;
import kotlinbootstrap.dfa.OperationStatement;
import kotlinbootstrap.dfa.RealVariable;
import kotlinbootstrap.dfa.SyntheticVariable;
import kotlinbootstrap.dfa.TypeStatement;
import kotlinbootstrap.dfa.VariableStorage;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.Symbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;

/**
 * The data flow analysis of a body, as FIR's FirDataFlowAnalyzer: what is
 * known of the variables at each point, which gives smart casts. Instead
 * of building a control flow graph, the {@link BodyChecker} reports events
 * in evaluation order, saves the state where control flow splits, and
 * joins the states where it merges. Whether a local var is stable, not
 * assigned where it may be read concurrently, is up to
 * {@link LocalVariableAssignments}.
 *
 * <p>Not yet: statements about the values of enum entries and objects,
 * which only exhaustiveness uses.
 */
final class DataFlowAnalyzer {
    /** What is known at a point, and whether the point is reachable. */
    record State(Flow flow, boolean dead) {
    }

    /** The states of a loop, from its entry to its exit. */
    static final class LoopState {
        private final Set<LocalVariable> assigned;
        private State enter;
        private State conditionEnter;
        private State conditionExit;
        private final List<State> breaks = new ArrayList<>();
        private final List<State> continues = new ArrayList<>();

        private LoopState(Set<LocalVariable> assigned) {
            this.assigned = assigned;
        }
    }

    /** The state outside a lambda being analyzed. */
    record LambdaFrame(State outer, List<LambdaExit> outerExits) {
    }

    /**
     * A lambda analyzed, to be joined at the exit of the call that completes it.
     *
     * @param kind how the call calls it; null if not in place
     */
    private record LambdaExit(State creation, State exit, Contract.InvocationKind kind) {
    }

    /** The backing field of a property, as the symbol of a variable. */
    private record BackingField(PropertySymbol property) {
    }

    private final TypeSystem ts;
    private final Checker checker;
    private final VariableStorage storage = new VariableStorage();
    private final LogicSystem logic;
    private final LocalVariableAssignments assignments;
    private int assignmentCounter;
    private Flow flow = new Flow();
    private boolean dead;
    /** The receivers of safe calls, by the variables their selectors read them from. */
    private final Map<LocalVariable, TExpr> safeCallReceivers = new HashMap<>();
    private final Map<TExpr.Loop, LoopState> loops = new HashMap<>();
    /** The states at the returns from lambdas being analyzed. */
    private final Map<CheckedFunction, List<State>> lambdaReturns = new HashMap<>();
    /** The lambdas analyzed since the last completed call, which joins them. */
    private List<LambdaExit> lambdaExits = new ArrayList<>();

    DataFlowAnalyzer(Checker checker) {
        this.ts = checker.ts();
        this.checker = checker;
        this.logic = new LogicSystem(ts, storage);
        this.assignments = new LocalVariableAssignments(ts);
    }

    // ==== States ====

    State save() {
        State s = new State(flow, dead);
        flow = flow.fork();
        return s;
    }

    void restore(State s) {
        flow = s.flow().fork();
        dead = s.dead();
    }

    /** Continues from where the states meet, having come from one of them. */
    void join(List<State> states) {
        State s = joined(states);
        flow = s.flow();
        dead = s.dead();
    }

    /** Unreachable states do not count, unless all are. */
    private State joined(List<State> states) {
        List<Flow> live = new ArrayList<>();
        for (State s : states) {
            if (!s.dead()) live.add(s.flow());
        }
        boolean allDead = live.isEmpty();
        if (allDead) {
            for (State s : states) live.add(s.flow());
        }
        return new State(logic.joinFlow(live, live, false), allDead);
    }

    /** Code from here on is not reached: after a jump or a call that returns Nothing. */
    void markDead() {
        dead = true;
    }

    // ==== Smart casts ====

    /**
     * The expression with the type data flow knows it has, if more specific
     * than its own, as FIR's transformExpressionUsingSmartcastInfo.
     */
    TExpr smartCast(TExpr e) {
        // An unstable alias can stand for a stable variable, so aliases are kept.
        if (!(variable(e, false, false) instanceof RealVariable v)) return e;
        TypeStatement s = flow.getTypeStatement(v);
        if (s == null || s.upperTypes().isEmpty() || !isEffectivelyStable(v, s.upperTypes())) return e;
        List<KType> types = new ArrayList<>(s.upperTypes());
        types.add(e.type());
        KType type = ts.intersect(types);
        // TODO: smart casts to Nothing?, which kotlinc makes, resolving members as if without it
        if (type.equals(e.type()) || isKindOfNothing(type)) return e;
        return new TExpr.SmartCast(e.pos(), type, e);
    }

    private boolean isKindOfNothing(KType t) {
        return Types.lowerBound(t) instanceof KType.ClassType c && c.symbol() == ts.nothingClass();
    }

    /**
     * As FIR's computeEffectiveStability: stable, and a local var is not
     * assigned where it may run concurrently, other than with a value of
     * all the types (any assignment, with types null).
     */
    private boolean isEffectivelyStable(RealVariable v, Set<KType> types) {
        return isStable(v) && !isUnstableLocalVariable(v, types);
    }

    private boolean isUnstableLocalVariable(RealVariable v, Set<KType> types) {
        return v.symbol() instanceof LocalVariable l && assignments.isUnstableInCurrentScope(l, types)
                || v.dispatchReceiver() != null && isUnstableLocalVariable(v.dispatchReceiver(), null);
    }

    /** As FIR's RealVariable.getStability, with all sources in one module; local vars are stable here. */
    private boolean isStable(RealVariable v) {
        if (v.implicit()) return true;
        switch (v.symbol()) {
            case PropertySymbol p when p.isEnumEntry() -> {
            }
            case PropertySymbol p when p.isJavaField() -> {
                if (p.isVar() || p.origin() == Origin.JAVA_BINARY) return false;
            }
            case PropertySymbol p -> {
                Decl.Property d = p.source() instanceof Decl.Property dp ? dp : null;
                boolean customGetter = d != null && d.getter() != null && d.getter().body() != null;
                if (p.isVar() || isExpect(p) || d != null && d.delegate() != null || customGetter
                        || p.signature().receiver() != null) {
                    return false;
                }
                // An open property can be overridden with a custom getter.
                if (p.visibility() != Visibility.PRIVATE && p.modality() != Modality.FINAL
                        && !hasFinalType(v.dispatchReceiver())) {
                    return false;
                }
            }
            case BackingField f -> {
                if (f.property().isVar()) return false;
            }
            case LocalVariable l when l.kind() == LocalVariable.Kind.DELEGATED -> {
                return false;
            }
            default -> {
            }
        }
        // Members of unstable values are unstable, as the receiver could change.
        return v.dispatchReceiver() == null || isStable(v.dispatchReceiver());
    }

    private static boolean isExpect(PropertySymbol p) {
        if (p.has(Modifier.EXPECT)) return true;
        for (Symbol s = p.owner(); s instanceof ClassSymbol c; s = c.outer()) {
            if (c.has(Modifier.EXPECT)) return true;
        }
        return false;
    }

    private boolean hasFinalType(RealVariable v) {
        if (v == null || isFinal(v.originalType())) return true;
        TypeStatement s = flow.getTypeStatement(v);
        return s != null && s.upperTypes().stream().anyMatch(this::isFinal);
    }

    private boolean isFinal(KType t) {
        return switch (t) {
            case KType.ClassType c -> c.symbol().modality() == Modality.FINAL;
            case KType.Flexible f -> isFinal(f.lower());
            case KType.DefinitelyNotNull d -> isFinal(d.inner());
            case KType.TypeParamType p -> p.symbol().bounds().stream().anyMatch(this::isFinal);
            case KType.Intersection i -> i.components().stream().anyMatch(this::isFinal);
            case KType.Captured c -> c.constructor().supertypes() != null
                    && c.constructor().supertypes().stream().anyMatch(this::isFinal);
            case KType.IntegerLiteral l -> true;
            case KType.TypeVariableType v -> false;
        };
    }

    // ==== Variables ====

    /**
     * The variable whose value an expression is, as FIR's VariableStorage.get:
     * a real variable for a stored value, otherwise a synthetic one for the
     * expression. Null if the real variable is not known and not to be made.
     */
    private DataFlowVariable variable(TExpr e, boolean createReal, boolean unwrapAlias) {
        TExpr u = unwrap(e);
        Object symbol;
        boolean implicit = false;
        TExpr dispatch = null;
        TExpr extension = null;
        switch (u) {
            case TExpr.LocalGet g -> symbol = g.variable();
            case TExpr.This t -> {
                symbol = t.owner();
                implicit = true;
            }
            case TExpr.ObjectValue o -> symbol = o.object();
            // A synthetic Java property is a call of its getter.
            case TExpr.PropertyGet p when p.property().javaGetter() == null -> {
                symbol = p.property();
                dispatch = p.dispatchReceiver();
                extension = p.extensionReceiver();
            }
            case TExpr.FieldGet f -> {
                symbol = new BackingField(f.property());
                dispatch = f.receiver();
            }
            case TExpr.Unresolved r when r.state() instanceof Candidate c && c.symbol instanceof PropertySymbol p
                    && p.javaGetter() == null -> {
                symbol = p;
                dispatch = c.dispatchReceiver;
                extension = c.extensionReceiver;
            }
            default -> {
                return new SyntheticVariable(u, u.type());
            }
        }
        RealVariable dispatchVariable = null;
        if (dispatch != null) {
            DataFlowVariable d = variable(dispatch, createReal, true);
            if (d == null) return null;
            if (!(d instanceof RealVariable r)) return new SyntheticVariable(u, u.type());
            dispatchVariable = r;
        }
        RealVariable extensionVariable = null;
        if (extension != null) {
            DataFlowVariable x = variable(extension, createReal, true);
            if (x == null) return null;
            if (!(x instanceof RealVariable r)) return new SyntheticVariable(u, u.type());
            extensionVariable = r;
        }
        RealVariable prototype = new RealVariable(symbol, implicit, dispatchVariable, extensionVariable, u.type());
        RealVariable real = createReal ? storage.rememberWithKnownReceivers(prototype) : storage.getKnown(prototype);
        if (real == null) return null;
        return unwrapAlias ? unwrapVariableIfStable(real) : real;
    }

    /** The variable an alias stands for; null if the alias is unstable. */
    private RealVariable unwrapVariableIfStable(RealVariable v) {
        RealVariable unwrapped = flow.unwrapVariable(v);
        return unwrapped != v && isUnstableLocalVariable(v, null) ? null : unwrapped;
    }

    /** Without what does not change the value: smart casts, {@code !!}, and a safe call's subject. */
    private TExpr unwrap(TExpr e) {
        while (true) {
            switch (e) {
                case TExpr.SmartCast s -> e = s.expr();
                case TExpr.NotNull n -> e = n.expr();
                case TExpr.Unresolved u when u.state() instanceof Candidate c && c.symbol.origin() == Origin.SYNTHETIC
                        && c.symbol.name().equals(SyntheticCalls.CHECK_NOT_NULL) -> e = c.arguments.getFirst();
                case TExpr.SafeCall s -> e = s.selector();
                case TExpr.LocalGet g when safeCallReceivers.containsKey(g.variable()) ->
                        e = safeCallReceivers.get(g.variable());
                default -> {
                    return e;
                }
            }
        }
    }

    private RealVariable knownLocal(LocalVariable v) {
        return storage.getKnown(new RealVariable(v, false, null, null, v.type()));
    }

    /** For statements about a value: null if nothing will be known of it. */
    private DataFlowVariable variableIfUsed(Flow f, TExpr e) {
        DataFlowVariable v = variable(e, false, true);
        return v != null && hasImplications(f, v) ? v : null;
    }

    /** For type statements: null if a synthetic variable that nothing will be known of. */
    private DataFlowVariable variableIfUsedOrReal(TExpr e) {
        DataFlowVariable v = variable(e, true, true);
        return v instanceof RealVariable || v != null && hasImplications(flow, v) ? v : null;
    }

    private static boolean hasImplications(Flow f, DataFlowVariable v) {
        List<Implication> implications = f.getImplications(v);
        return implications != null && !implications.isEmpty();
    }

    // ==== Statements ====

    private void addImplication(Implication i) {
        logic.addImplication(flow, i);
    }

    private void addTypeStatement(TypeStatement s) {
        logic.addTypeStatement(flow, s);
    }

    private void addAll(Map<DataFlowVariable, TypeStatement> statements) {
        statements.values().forEach(this::addTypeStatement);
    }

    private void addAllConditionally(OperationStatement condition, Map<DataFlowVariable, TypeStatement> statements) {
        for (TypeStatement s : statements.values()) addImplication(condition.implies(s));
    }

    private void commit(OperationStatement s) {
        addAll(logic.approveOperationStatement(flow, s, true));
    }

    /** The statements of a flow this one does not have; joining flows can drop statements. */
    private Map<DataFlowVariable, TypeStatement> statementsNotInheritedFrom(Flow parent) {
        Map<DataFlowVariable, TypeStatement> result = new LinkedHashMap<>();
        for (DataFlowVariable v : parent.knownVariables()) {
            // The statement about what it aliases is added instead.
            if (!flow.unwrapVariable(v).equals(v)) continue;
            TypeStatement s = parent.getTypeStatement(v);
            if (s != null && !s.equals(flow.getTypeStatement(v))) result.put(v, s);
        }
        return result;
    }

    // ==== Conditions ====

    /** Assumes a condition has a value: at the start of a branch it guards. */
    void assume(TExpr condition, boolean value) {
        if (Boolean.valueOf(!value).equals(booleanValue(condition))) dead = true;
        if (!isBoolean(condition.type())) return;
        DataFlowVariable v = variableIfUsed(flow, condition);
        if (v != null) commit(OperationStatement.eq(v, value));
    }

    /** {@code a is T} and {@code a !is T}, as FIR's addTypeOperatorStatements. */
    void typeCheck(TExpr.TypeCheck t) {
        boolean isType = !t.negated();
        KType type = t.target();
        // x is Nothing? is x == null; x is Any is x != null.
        if (isClass(type, ts.nothingClass(), true)) {
            processEqNull(t, t.expr(), isType, null);
            return;
        }
        if (isClass(type, ts.anyClass(), false)) {
            processEqNull(t, t.expr(), !isType, null);
            return;
        }
        DataFlowVariable operand = variableIfUsedOrReal(t.expr());
        if (operand == null) return;
        SyntheticVariable expression = new SyntheticVariable(t, t.type());
        if (operand instanceof RealVariable) {
            addImplication(OperationStatement.eq(expression, isType).implies(TypeStatement.typeEq(operand, type)));
            addImplication(OperationStatement.eq(expression, !isType).implies(TypeStatement.typeNotEq(operand, type)));
        }
        if (!Types.canBeNull(type)) {
            // x is T & Any implies x != null
            addImplication(OperationStatement.eq(expression, isType).implies(OperationStatement.notEqNull(operand)));
        } else if (Types.isMarkedNullable(type)) {
            // x !is T? implies x != null
            addImplication(OperationStatement.eq(expression, !isType).implies(OperationStatement.notEqNull(operand)));
        }
    }

    /** {@code a as T} and {@code a as? T}. */
    void cast(TExpr.Cast c) {
        DataFlowVariable operand = variableIfUsedOrReal(c.expr());
        if (operand == null) return;
        KType type = c.target();
        SyntheticVariable expression = new SyntheticVariable(c, c.type());
        if (c.safe()) {
            addImplication(OperationStatement.notEqNull(expression).implies(OperationStatement.notEqNull(operand)));
            if (operand instanceof RealVariable) {
                addImplication(OperationStatement.notEqNull(expression).implies(TypeStatement.typeEq(operand, type)));
            }
            return;
        }
        if (operand instanceof RealVariable) addTypeStatement(TypeStatement.typeEq(operand, type));
        if (!Types.canBeNull(type)) {
            commit(OperationStatement.notEqNull(operand));
        } else {
            addImplication(OperationStatement.notEqNull(expression).implies(OperationStatement.notEqNull(operand)));
            addImplication(OperationStatement.eqNull(expression).implies(OperationStatement.eqNull(operand)));
        }
    }

    /** {@code a!!} */
    void checkNotNull(TExpr argument) {
        DataFlowVariable v = variableIfUsedOrReal(argument);
        if (v != null) commit(OperationStatement.notEqNull(v));
    }

    // ==== Equality ====

    /**
     * {@code ==}, {@code !=}, {@code ===} and {@code !==}, as FIR's
     * exitEqualityOperatorCall.
     *
     * @param lhsExit the state after the left side
     */
    void equality(TExpr expression, TExpr.EqualityOp op, TExpr left, TExpr right, State lhsExit) {
        boolean isEq = op == TExpr.EqualityOp.EQ || op == TExpr.EqualityOp.IDENTITY_EQ;
        TExpr.Const leftConst = left instanceof TExpr.Const c ? c : null;
        TExpr.Const rightConst = right instanceof TExpr.Const c ? c : null;
        boolean leftIsNullConst = leftConst != null && leftConst.value() == null;
        boolean rightIsNullConst = rightConst != null && rightConst.value() == null;
        boolean leftIsNull = leftIsNullConst || isClass(left.type(), ts.nothingClass(), true) && !rightIsNullConst;
        boolean rightIsNull = rightIsNullConst || isClass(right.type(), ts.nothingClass(), true) && !leftIsNullConst;
        if (!leftIsNull && leftConst == null && !rightIsNull && rightConst == null) {
            processEq(expression, op, left, right, lhsExit.flow());
            return;
        }
        if (leftIsNull) {
            processEqNull(expression, right, isEq, lhsExit.flow());
        } else if (leftConst != null) {
            processEqConst(expression, right, leftConst, isEq, true);
        }
        if (rightIsNull) {
            processEqNull(expression, left, isEq, lhsExit.flow());
        } else if (rightConst != null) {
            processEqConst(expression, left, rightConst, isEq, false);
        }
    }

    private void processEqNull(TExpr expression, TExpr operand, boolean isEq, Flow lhsExitFlow) {
        DataFlowVariable v = variableIfUsedOrReal(operand);
        if (v == null) return;
        if (v instanceof RealVariable r && lhsExitFlow != null && !logic.isSameValueIn(lhsExitFlow, flow, r)) return;
        SyntheticVariable e = new SyntheticVariable(expression, expression.type());
        addImplication(OperationStatement.eq(e, isEq).implies(OperationStatement.eqNull(v)));
        addImplication(OperationStatement.eq(e, !isEq).implies(OperationStatement.notEqNull(v)));
    }

    private void processEqConst(TExpr expression, TExpr operand, TExpr.Const c, boolean isEq, boolean constIsLeft) {
        if (c.value() == null) {
            processEqNull(expression, operand, isEq, null);
            return;
        }
        DataFlowVariable v = variableIfUsedOrReal(operand);
        if (v == null) return;
        SyntheticVariable e = new SyntheticVariable(expression, expression.type());
        if (c.value() instanceof Boolean expected && Types.lowerBound(operand.type()) instanceof KType.ClassType t
                && t.symbol() == booleanClass()) {
            addImplication(OperationStatement.eq(e, isEq).implies(OperationStatement.eq(v, expected)));
            if (isBoolean(operand.type())) {
                addImplication(OperationStatement.eq(e, !isEq).implies(OperationStatement.eq(v, !expected)));
            }
            if (v instanceof RealVariable r) {
                addImplication(OperationStatement.eq(e, !isEq).implies(TypeStatement.valueNotEq(r, expected)));
            }
        } else {
            addImplication(OperationStatement.eq(e, isEq).implies(OperationStatement.notEqNull(v)));
        }
        // "a" == x: the type of the constant, if one whose equals is known.
        if (v instanceof RealVariable && constIsLeft && isClass(c.type(), ts.classType("kotlin.String").symbol(), false)) {
            addImplication(OperationStatement.eq(e, isEq).implies(TypeStatement.typeEq(v, c.type())));
        }
    }

    private void processEq(TExpr expression, TExpr.EqualityOp op, TExpr left, TExpr right, Flow lhsExitFlow) {
        boolean isEq = op == TExpr.EqualityOp.EQ || op == TExpr.EqualityOp.IDENTITY_EQ;
        boolean leftIsNullable = Types.isMarkedNullable(left.type());
        boolean rightIsNullable = Types.isMarkedNullable(right.type());
        // Either being null would need a second level of implications.
        if (leftIsNullable && rightIsNullable) return;
        // The left side counts only if the right did not reassign it.
        DataFlowVariable leftVariable = isSameValueIn(lhsExitFlow, left) ? variableIfUsedOrReal(left) : null;
        DataFlowVariable rightVariable = variableIfUsedOrReal(right);
        if (leftVariable == null && rightVariable == null) return;
        SyntheticVariable e = new SyntheticVariable(expression, expression.type());
        if (leftIsNullable || rightIsNullable) {
            // a == b: Any implies a != null; a != b does not imply a == null.
            DataFlowVariable nullable = leftIsNullable ? leftVariable : rightVariable;
            if (nullable != null) addImplication(OperationStatement.eq(e, isEq).implies(OperationStatement.notEqNull(nullable)));
        }
        boolean identity = op == TExpr.EqualityOp.IDENTITY_EQ || op == TExpr.EqualityOp.IDENTITY_NE;
        if (!identity && !equalsIsSafeForSmartCast(left.type())) return;
        if (leftVariable instanceof RealVariable) {
            addImplication(OperationStatement.eq(e, isEq).implies(TypeStatement.typeEq(leftVariable, right.type())));
        }
        if (rightVariable instanceof RealVariable) {
            addImplication(OperationStatement.eq(e, isEq).implies(TypeStatement.typeEq(rightVariable, left.type())));
        }
    }

    private boolean isSameValueIn(Flow other, TExpr e) {
        return !(variable(e, false, false) instanceof RealVariable r) || logic.isSameValueIn(other, flow, r);
    }

    /**
     * Whether a == b implies that b has a's type, as FIR's
     * computeEqualsOverrideContract gives SAFE_FOR_SMART_CAST: a's class is
     * final, and neither it nor a superclass overrides equals.
     */
    private boolean equalsIsSafeForSmartCast(KType type) {
        if (!(Types.lowerBound(type) instanceof KType.ClassType c) || c.symbol().modality() != Modality.FINAL) {
            return false;
        }
        for (ClassSymbol cls = c.symbol(); cls != null; cls = superclass(cls)) {
            switch (cls.id().fqName()) {
                case "kotlin.Any", "kotlin.String", "kotlin.Enum" -> {
                    continue;
                }
                // Their equals differs from ==.
                case "kotlin.Float", "kotlin.Double" -> {
                    return false;
                }
                default -> {
                }
            }
            if (cls.has(Modifier.EXPECT) || cls.has(Modifier.DATA) || cls.has(Modifier.VALUE)
                    || cls.has(Modifier.INLINE)) {
                return false;
            }
            if (cls.members().functions("equals").stream().anyMatch(f -> f.signature().params().size() == 1)) {
                return false;
            }
        }
        return true;
    }

    private static ClassSymbol superclass(ClassSymbol cls) {
        for (KType t : cls.supertypes()) {
            if (t instanceof KType.ClassType c && !c.symbol().isInterface()) return c.symbol();
        }
        return null;
    }

    // ==== Boolean operators ====

    /**
     * After the left side of {@code &&} or {@code ||}: the right side is
     * evaluated only if the left did not decide the value. Returns the state
     * after the left side, for {@link #exitLogical}.
     */
    State exitLeftOfLogical(TExpr left, boolean and) {
        State leftExit = save();
        DataFlowVariable v = variableIfUsed(flow, left);
        if (v != null) commit(OperationStatement.eq(v, and));
        return leftExit;
    }

    /** As FIR's mergeBooleanLogicOperatorFlow. */
    void exitLogical(TExpr.Logical expression, State leftExit) {
        State rightExit = save();
        join(List.of(leftExit, rightExit));
        // The value that decides the result: true for ||, false for &&.
        boolean saturating = !expression.and();
        Flow fromLeft = leftExit.flow();
        Flow fromRight = rightExit.flow();
        TExpr left = expression.left();
        TExpr right = expression.right();
        DataFlowVariable leftVariable = isBoolean(left.type()) ? variableIfUsed(fromLeft, left) : null;
        DataFlowVariable rightVariable = isBoolean(right.type()) ? variableIfUsed(fromRight, right) : null;
        SyntheticVariable operator = new SyntheticVariable(expression, expression.type());
        if (!dead && rightExit.dead()) {
            // The right side does not terminate, so the left side decided.
            if (leftVariable != null) commit(OperationStatement.eq(leftVariable, saturating));
        } else if (Boolean.valueOf(!saturating).equals(booleanValue(left))) {
            if (rightVariable != null) logic.translateVariableFromConditionInStatements(flow, rightVariable, operator, i -> i);
        } else if (Boolean.valueOf(!saturating).equals(booleanValue(right))) {
            if (leftVariable != null) logic.translateVariableFromConditionInStatements(flow, leftVariable, operator, i -> i);
        } else {
            Map<DataFlowVariable, TypeStatement> statementsFromRight = statementsNotInheritedFrom(fromRight);
            // Both sides were evaluated, neither saturating.
            Map<DataFlowVariable, TypeStatement> whenNotSaturating =
                    statementsWhenRightIs(!saturating, statementsFromRight, fromRight, rightVariable);
            // The left side was saturating, or both were evaluated and the right was.
            Map<DataFlowVariable, TypeStatement> whenSaturating = leftVariable == null ? Map.of()
                    : logic.orForTypeStatements(
                            logic.approveOperationStatement(fromLeft, OperationStatement.eq(leftVariable, saturating)),
                            statementsWhenRightIs(saturating, statementsFromRight, fromRight, rightVariable));
            addAll(logic.orForTypeStatements(whenSaturating, whenNotSaturating));
            addAllConditionally(OperationStatement.eq(operator, saturating), whenSaturating);
            addAllConditionally(OperationStatement.eq(operator, !saturating), whenNotSaturating);
        }
    }

    /** The right side is only evaluated if the left is not saturating, so these include what that implies. */
    private Map<DataFlowVariable, TypeStatement> statementsWhenRightIs(
            boolean value, Map<DataFlowVariable, TypeStatement> statementsFromRight, Flow fromRight,
            DataFlowVariable rightVariable) {
        if (rightVariable == null) return statementsFromRight;
        return logic.andForTypeStatements(statementsFromRight,
                logic.approveOperationStatement(fromRight, OperationStatement.eq(rightVariable, value)));
    }

    private void exitBooleanNot(TExpr expression, TExpr argument) {
        DataFlowVariable v = variableIfUsed(flow, argument);
        if (v == null) return;
        SyntheticVariable e = new SyntheticVariable(expression, expression.type());
        logic.translateVariableFromConditionInStatements(flow, v, e, i -> switch (i.condition().operation()) {
            case EQ_TRUE -> OperationStatement.eq(e, false).implies(i.effect());
            case EQ_FALSE -> OperationStatement.eq(e, true).implies(i.effect());
            default -> null;
        });
    }

    // ==== Variables ====

    /** A local variable declared with an initializer. */
    void localVariableInitialized(LocalVariable v, TExpr initializer, boolean hasExplicitType, boolean isWhenSubject) {
        RealVariable variable = storage.remember(new RealVariable(v, false, null, null, v.type()));
        initialize(variable, initializer, hasExplicitType, isWhenSubject, false, v.isVar());
    }

    /** A local variable declared by the syntax: a property or destructuring entry in a body. */
    void declare(LocalVariable v, Object declaration) {
        assignments.declare(v, declaration);
    }

    /** An assignment of the variable the target reads. */
    void assigned(TExpr target, TExpr value) {
        if (target instanceof TExpr.LocalGet g) assignments.visitAssignment(g.variable(), value.type());
        boolean isLocal = target instanceof TExpr.LocalGet;
        boolean isVal = target instanceof TExpr.PropertyGet p && !p.property().isVar()
                || target instanceof TExpr.FieldGet f && !f.property().isVar();
        if (isLocal || isVal) {
            if (variable(target, true, false) instanceof RealVariable r) {
                initialize(r, value, false, false, true, isLocal && ((TExpr.LocalGet) target).variable().isVar());
            }
        } else if (variable(target, false, false) instanceof RealVariable r) {
            logic.recordNewAssignment(flow, r, ++assignmentCounter);
        }
    }

    /** As FIR's exitVariableInitialization. */
    private void initialize(RealVariable variable, TExpr initializer, boolean hasExplicitType, boolean isWhenSubject,
                            boolean isAssignment, boolean isLocalVar) {
        if (isAssignment) logic.recordNewAssignment(flow, variable, ++assignmentCounter);
        boolean addInitializerType = isAssignment;
        if (isStable(variable)) {
            DataFlowVariable initializerVariable = variableIfUsedOrReal(initializer);
            if (!hasExplicitType && initializerVariable instanceof RealVariable r
                    && (isWhenSubject || isEffectivelyStable(r, null))) {
                // val b = a; if (b != null) { /* a != null */ }
                logic.addLocalVariableAlias(flow, variable, r);
            } else if (initializerVariable != null && !isLocalVar) {
                // val b = x is String; if (b) { /* x is String */ }
                logic.translateVariableFromConditionInStatements(flow, initializerVariable, variable, i -> i);
            } else {
                addInitializerType = addInitializerType || initializer instanceof TExpr.SmartCast
                        && !flow.unwrapVariable(variable).originalType().equals(initializer.type());
            }
        }
        if (addInitializerType) {
            addTypeStatement(TypeStatement.typeEq(flow.unwrapVariable(variable), initializer.type()));
        }
    }

    // ==== Safe calls and elvis ====

    /**
     * Enters the selector of a safe call, which reads the receiver's value
     * from the subject variable. Returns the state before, for {@link #exitSafeCall}.
     */
    State enterSafeCall(LocalVariable subject, TExpr receiver) {
        safeCallReceivers.put(subject, receiver);
        State before = save();
        DataFlowVariable v = variableIfUsedOrReal(receiver);
        if (v != null) commit(OperationStatement.notEqNull(v));
        return before;
    }

    void exitSafeCall(TExpr.SafeCall safeCall, State before) {
        State selectorExit = save();
        join(List.of(before, selectorExit));
        DataFlowVariable v = variable(safeCall, true, true);
        if (v == null) return;
        // If the result is not null, the selector was evaluated.
        addAllConditionally(OperationStatement.notEqNull(v), statementsNotInheritedFrom(selectorExit.flow()));
        // a?.b() == null can be from either being null; the rest are as for b().
        List<Implication> implications = selectorExit.flow().getImplications(v);
        if (implications == null) return;
        for (Implication i : implications) {
            if (i.condition().operation() != Operation.EQ_NULL) addImplication(i);
        }
    }

    /**
     * After the left side of an elvis: on to the right side, where it is
     * null. Returns the state where it is not, for {@link #exitElvis}.
     */
    State exitElvisLhs(TExpr lhs) {
        State lhsExit = save();
        DataFlowVariable v = variableIfUsedOrReal(lhs);
        if (v != null) commit(OperationStatement.notEqNull(v));
        State notNull = save();
        restore(lhsExit);
        if (v != null) commit(OperationStatement.eqNull(v));
        return notNull;
    }

    /** @param lhsIsNotNull whether the right side returns Nothing, so that only the left side's value is the result */
    void exitElvis(TExpr expression, TExpr lhs, TExpr rhs, State lhsNotNull, boolean lhsIsNotNull) {
        State rhsExit = save();
        join(List.of(lhsNotNull, lhsIsNotNull ? new State(rhsExit.flow(), true) : rhsExit));
        if (lhsIsNotNull) return;
        SyntheticVariable e = new SyntheticVariable(expression, expression.type());
        // (x ?: null) != null implies x != null, and (null ?: x) != null too.
        if (isClass(rhs.type(), ts.nothingClass(), true)) {
            DataFlowVariable v = variableIfUsedOrReal(lhs);
            if (v != null) addImplication(OperationStatement.notEqNull(e).implies(OperationStatement.notEqNull(v)));
        }
        if (isClass(lhs.type(), ts.nothingClass(), true)) {
            DataFlowVariable v = variableIfUsedOrReal(rhs);
            if (v != null) addImplication(OperationStatement.notEqNull(e).implies(OperationStatement.notEqNull(v)));
        }
        // P(u ?: false) != P(false) implies u != null.
        if (booleanValue(rhs) instanceof Boolean b) {
            addAllConditionally(OperationStatement.eq(e, !b), statementsNotInheritedFrom(lhsNotNull.flow()));
        }
    }

    // ==== Loops and jumps ====

    /** @param node the loop's syntax */
    LoopState enterWhileLoop(TExpr.Loop loop, Expr node) {
        Set<LocalVariable> assigned = assignments.enterLoop(node);
        LoopState l = new LoopState(assigned);
        loops.put(loop, l);
        l.enter = save();
        enterRepeatable(assigned);
        l.conditionEnter = save();
        return l;
    }

    void exitWhileLoopCondition(LoopState l, TExpr condition) {
        l.conditionExit = save();
        assume(condition, true);
    }

    void exitWhileLoop(LoopState l, TExpr condition) {
        assignments.exitLoop();
        List<State> backEdges = new ArrayList<>(List.of(l.enter, save()));
        backEdges.addAll(l.continues);
        exitLoop(l, condition);
        processWhileLoopExit(l, backEdges);
    }

    LoopState enterDoWhileLoop(TExpr.Loop loop, Expr node) {
        Set<LocalVariable> assigned = assignments.enterLoop(node);
        LoopState l = new LoopState(assigned);
        loops.put(loop, l);
        l.enter = save();
        enterRepeatable(assigned);
        return l;
    }

    void enterDoWhileLoopCondition(LoopState l) {
        List<State> states = new ArrayList<>(List.of(save()));
        states.addAll(l.continues);
        join(states);
    }

    void exitDoWhileLoop(LoopState l, TExpr condition) {
        assignments.exitLoop();
        l.conditionExit = save();
        exitLoop(l, condition);
    }

    /** The values of variables a loop assigns are not known when it repeats. */
    private void enterRepeatable(Set<LocalVariable> assigned) {
        for (LocalVariable v : assigned) {
            RealVariable r = knownLocal(v);
            if (r != null) logic.recordNewAssignment(flow, r, ++assignmentCounter);
        }
    }

    /** Joins the condition's exit, unless it is {@code true}, and the breaks; as FIR's processLoopExit. */
    private void exitLoop(LoopState l, TExpr condition) {
        List<State> exits = new ArrayList<>();
        if (!Boolean.TRUE.equals(booleanValue(condition))) exits.add(l.conditionExit);
        exits.addAll(l.breaks);
        if (exits.isEmpty()) {
            dead = true;
            return;
        }
        join(exits);
        // Without a break, the loop ended because the condition was false.
        long reached = l.breaks.stream().filter(s -> !s.dead()).count() + (l.conditionExit.dead() ? 0 : 1);
        if (l.conditionExit.dead() || reached > 1 || !isBoolean(condition.type())) return;
        DataFlowVariable v = variableIfUsed(flow, condition);
        if (v != null) commit(OperationStatement.eq(v, false));
    }

    /**
     * What is known after a loop of the variables it assigns, as FIR's
     * processWhileLoopExit: their statements were dropped at the condition,
     * before the jumps back to it were known.
     */
    private void processWhileLoopExit(LoopState l, List<State> backEdges) {
        if (l.assigned.isEmpty()) return;
        List<Flow> enterAndContinue = liveFlows(backEdges);
        List<State> exitStates = new ArrayList<>();
        if (l.conditionExit != null) exitStates.add(l.conditionExit);
        exitStates.addAll(l.breaks);
        List<Flow> exitAndBreak = liveFlows(exitStates);
        Flow conditionEnter = l.conditionEnter.flow();
        variables:
        for (LocalVariable local : l.assigned) {
            RealVariable v = knownLocal(local);
            if (v == null) continue;
            List<TypeStatement> in = new ArrayList<>();
            for (Flow f : enterAndContinue) {
                TypeStatement s = f.getTypeStatement(v);
                if (s == null) continue variables;
                in.add(s);
            }
            TypeStatement toAdd = logic.or(in);
            if (toAdd == null || toAdd.isEmpty()) continue;
            List<TypeStatement> out = new ArrayList<>();
            for (Flow f : exitAndBreak) {
                TypeStatement atExit = f.getTypeStatement(v);
                if (logic.isSameValueIn(conditionEnter, f, v)) {
                    out.add(atExit == null ? toAdd : atExit.and(toAdd));
                } else if (atExit == null) {
                    continue variables;
                } else {
                    out.add(atExit);
                }
            }
            TypeStatement s = logic.or(out);
            if (s != null) addTypeStatement(s);
        }
    }

    private static List<Flow> liveFlows(List<State> states) {
        List<Flow> result = new ArrayList<>();
        for (State s : states) {
            if (!s.dead()) result.add(s.flow());
        }
        return result;
    }

    void breakLoop(TExpr.Loop loop) {
        LoopState l = loops.get(loop);
        if (l != null) l.breaks.add(save());
        dead = true;
    }

    void continueLoop(TExpr.Loop loop) {
        LoopState l = loops.get(loop);
        if (l != null) l.continues.add(save());
        dead = true;
    }

    /** A return; from a lambda, its state joins those at the lambda's exit. */
    void returnFrom(CheckedFunction target, boolean isLambda) {
        if (isLambda) lambdaReturns.computeIfAbsent(target, k -> new ArrayList<>()).add(save());
        dead = true;
    }

    // ==== Lambdas and calls ====

    /**
     * Enters the body of a lambda, from the state where it was made.
     *
     * @param node its syntax
     * @param kind how its call calls it; null if not in place
     */
    LambdaFrame enterLambda(Expr node, State creation, Contract.InvocationKind kind) {
        Set<LocalVariable> assigned = assignments.enterFunction(node, kind != null);
        LambdaFrame frame = new LambdaFrame(new State(flow, dead), lambdaExits);
        lambdaExits = new ArrayList<>();
        flow = creation.flow().fork();
        dead = creation.dead();
        // The values of the variables it assigns are not known if it can run again.
        if (kind == null || kind.canBeRevisited()) enterRepeatable(assigned);
        return frame;
    }

    void exitLambda(LambdaFrame frame, CheckedFunction function, State creation, Contract.InvocationKind kind) {
        assignments.exitFunction();
        List<State> exits = new ArrayList<>();
        List<State> returns = lambdaReturns.remove(function);
        if (returns != null) exits.addAll(returns);
        exits.add(save());
        State exit = joined(exits);
        flow = frame.outer().flow();
        dead = frame.outer().dead();
        lambdaExits = frame.outerExits();
        lambdaExits.add(new LambdaExit(creation, exit, kind));
    }

    /**
     * Enters a body of a local function or class, from the state where it
     * was declared: as a lambda that may be called any time, without
     * joining the state after it. What it assigns is unstable, rather
     * than unknown at its start, as in FIR.
     */
    LambdaFrame enterLocalDeclaration(State declaration) {
        LambdaFrame frame = new LambdaFrame(new State(flow, dead), lambdaExits);
        lambdaExits = new ArrayList<>();
        flow = declaration.flow().fork();
        dead = declaration.dead();
        return frame;
    }

    void exitLocalDeclaration(LambdaFrame frame) {
        flow = frame.outer().flow();
        dead = frame.outer().dead();
        lambdaExits = frame.outerExits();
    }

    /** Enters a named function or accessor, the root of a body or local, which is not called in place. */
    void enterFunction(Object node) {
        assignments.enterFunction(node, false);
    }

    void exitFunction() {
        assignments.exitFunction();
    }

    void enterInitBlock(Decl.Initializer node) {
        assignments.enterAnonymousInitializer(node);
    }

    void exitInitBlock(Decl.Initializer node) {
        assignments.exitAnonymousInitializer(node);
    }

    /** Enters the body of a local class or object literal. */
    void enterClass(Decl.Class node, boolean isAnonymousObject) {
        assignments.enterClass(node, isAnonymousObject);
    }

    void exitClass() {
        assignments.exitClass();
    }

    /**
     * Enters the arguments of a call, as FIR's enterCallArguments, with the
     * lambdas among them; {@link #exitFunctionCall} after its completion.
     */
    void enterCallArguments(List<Expr> lambdas) {
        assignments.enterFunctionCall(lambdas);
    }

    /** @param completed whether the call was completed fully, rather than with the call it is an argument of */
    void exitFunctionCall(boolean completed) {
        assignments.exitFunctionCall(completed);
    }

    /** The exit of a call or property read, as FIR's exitFunctionCall and exitQualifiedAccessExpression. */
    void exitCall(Candidate candidate, TExpr result, boolean completed) {
        if (completed) mergeLambdaExits();
        if (candidate.symbol instanceof FunctionSymbol f && f.origin() != Origin.SYNTHETIC) {
            if (f.name().equals("not") && f.owner() instanceof ClassSymbol c && c == booleanClass()) {
                // As if with returns(true) implies (this == false) and the opposite.
                exitBooleanNot(result, candidate.dispatchReceiver);
            } else {
                processContract(candidate, f, result);
            }
        } else if (candidate.symbol instanceof PropertySymbol p) {
            processContract(candidate, p, result);
        }
        if (candidate.explicitBackingFieldVisible) processBackingFieldAccess((PropertySymbol) candidate.symbol, result);
        if (ts.isNothing(result.type())) dead = true;
    }

    /**
     * A property read where its explicit backing field is visible has the
     * field's type, if stable, as FIR's processBackingFieldAccess.
     */
    private void processBackingFieldAccess(PropertySymbol p, TExpr result) {
        if (result instanceof TExpr.Unresolved) return;
        if (variable(result, true, false) instanceof RealVariable v) {
            addTypeStatement(TypeStatement.typeEq(v, checker.backingFieldType(p)));
        }
    }

    /**
     * Joins the lambdas the call called in place, which may have assigned
     * variables, as FIR's unifyDataFlowFromPostponedLambdas; what they found
     * out is not known after them.
     */
    private void mergeLambdaExits() {
        if (lambdaExits.isEmpty()) return;
        State call = save();
        List<Flow> flows = new ArrayList<>(List.of(call.flow()));
        boolean anyDead = call.dead();
        for (LambdaExit e : lambdaExits) {
            // A lambda not called in place is as if never called.
            if (e.kind() == null) continue;
            State s = e.kind().isDefinitelyVisited() ? e.exit() : joined(List.of(e.creation(), e.exit()));
            flows.add(s.flow());
            anyDead |= s.dead();
        }
        lambdaExits.clear();
        flow = logic.joinFlow(flows, List.of(call.flow()), true);
        dead = anyDead;
    }

    /** As FIR's processConditionalContract, for the effects returns(...) implies (...). */
    private void processContract(Candidate c, CallableSymbol f, TExpr call) {
        Contract contract = checker.contract(f);
        if (contract.returns().isEmpty()) return;
        // The receiver, then the arguments by parameter.
        DataFlowVariable[] arguments = new DataFlowVariable[
                (f instanceof FunctionSymbol function ? function.signature().params().size() : 0) + 1];
        TExpr receiver = c.extensionReceiver != null ? c.extensionReceiver : c.dispatchReceiver;
        boolean any = false;
        if (receiver != null) {
            arguments[0] = variableIfUsedOrReal(receiver);
            any = arguments[0] != null;
        }
        for (int i = 0; i < c.arguments.size() && c.argumentMapping != null; i++) {
            int param = c.argumentMapping[i];
            if (param < 0 || param + 1 >= arguments.length) continue;
            arguments[param + 1] = variableIfUsedOrReal(c.arguments.get(i));
            any |= arguments[param + 1] != null;
        }
        if (!any) return;
        for (Contract.Returns r : contract.returns()) {
            Operation operation = switch (r.value()) {
                case ANY -> null;
                case TRUE -> Operation.EQ_TRUE;
                case FALSE -> Operation.EQ_FALSE;
                case NULL -> Operation.EQ_NULL;
                case NOT_NULL -> Operation.NOT_EQ_NULL;
            };
            Map<DataFlowVariable, TypeStatement> statements = approve(r.condition(), false, arguments, operation == null);
            if (statements == null) continue;
            if (operation == null) {
                addAll(statements);
            } else {
                DataFlowVariable v = variable(call, true, true);
                if (v != null) addAllConditionally(new OperationStatement(v, operation), statements);
            }
        }
    }

    /** The type statements a contract's condition implies, as FIR's approveContractStatement; null if it cannot hold. */
    private Map<DataFlowVariable, TypeStatement> approve(Contract.Condition condition, boolean inverted,
                                                         DataFlowVariable[] arguments, boolean removeApproved) {
        return switch (condition) {
            case Contract.Condition.Constant c -> inverted == c.value() ? null : Map.of();
            case Contract.Condition.Not n -> approve(n.condition(), !inverted, arguments, removeApproved);
            case Contract.Condition.IsNull n -> {
                DataFlowVariable v = arguments[n.arg() + 1];
                yield v == null ? Map.of() : approve(inverted == n.negated() ? OperationStatement.eqNull(v)
                        : OperationStatement.notEqNull(v), removeApproved);
            }
            case Contract.Condition.BooleanArg b -> {
                DataFlowVariable v = arguments[b.arg() + 1];
                yield v == null ? Map.of() : approve(OperationStatement.eq(v, !inverted), removeApproved);
            }
            case Contract.Condition.IsInstance i -> {
                DataFlowVariable v = arguments[i.arg() + 1];
                if (v == null) yield Map.of();
                boolean isType = inverted == i.negated();
                KType type = i.type();
                if (isClass(type, ts.anyClass(), false)) {
                    yield approve(isType ? OperationStatement.notEqNull(v) : OperationStatement.eqNull(v), removeApproved);
                }
                if (isClass(type, ts.nothingClass(), true)) {
                    yield approve(isType ? OperationStatement.eqNull(v) : OperationStatement.notEqNull(v), removeApproved);
                }
                // x is T & Any, or x !is T?, implies x != null.
                Map<DataFlowVariable, TypeStatement> fromNullability =
                        isType && !Types.canBeNull(type) || !isType && Types.isMarkedNullable(type)
                                ? approve(OperationStatement.notEqNull(v), removeApproved) : Map.of();
                if (!(v instanceof RealVariable)) yield fromNullability;
                yield logic.andForTypeStatements(fromNullability,
                        Map.of(v, isType ? TypeStatement.typeEq(v, type) : TypeStatement.typeNotEq(v, type)));
            }
            case Contract.Condition.Logical l -> {
                Map<DataFlowVariable, TypeStatement> a = approve(l.left(), inverted, arguments, removeApproved);
                Map<DataFlowVariable, TypeStatement> b = approve(l.right(), inverted, arguments, removeApproved);
                boolean isAnd = inverted != l.and();
                if (a == null) yield isAnd ? null : b;
                if (b == null) yield isAnd ? null : a;
                yield isAnd ? logic.andForTypeStatements(a, b) : logic.orForTypeStatements(a, b);
            }
        };
    }

    private Map<DataFlowVariable, TypeStatement> approve(OperationStatement s, boolean removeApproved) {
        return logic.approveOperationStatement(flow, s, removeApproved);
    }

    // ==== Types ====

    private ClassSymbol booleanClass() {
        return ts.classType("kotlin.Boolean").symbol();
    }

    private boolean isBoolean(KType t) {
        return isClass(t, booleanClass(), false);
    }

    private static boolean isClass(KType t, ClassSymbol cls, boolean nullable) {
        return t instanceof KType.ClassType c && c.symbol() == cls && c.nullable() == nullable;
    }

    private static Boolean booleanValue(TExpr e) {
        return e instanceof TExpr.Const c && c.value() instanceof Boolean b ? b : null;
    }
}
