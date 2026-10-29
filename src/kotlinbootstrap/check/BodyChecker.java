package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.ast.Stmt;
import kotlinbootstrap.ast.TypeRef;
import kotlinbootstrap.ast.ValueArg;
import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.syntax.SourceFile;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;
import kotlinbootstrap.types.Variance;

/**
 * Checks the expressions of one file, as FIR's body resolve transformers:
 * each expression is checked in a {@link Mode} that says what type is
 * expected of it and whether its calls are completed at once.
 */
final class BodyChecker {
    private final Checker owner;
    private final TypeSystem ts;
    private final SourceFile source;
    private final CallCompleter completer;
    private final CallResolver calls;
    private final DataFlowAnalyzer dfa;
    /** The name of the call whose arguments are being checked, as FIR's calleeNamesForLambda; nullable. */
    private String calleeNameForLambda;

    BodyChecker(Checker owner, SourceFile source) {
        this.owner = owner;
        this.ts = owner.ts();
        this.source = source;
        this.dfa = new DataFlowAnalyzer(owner);
        this.completer = new CallCompleter(ts, this);
        this.calls = new CallResolver(owner, completer,
                new OverloadByLambdaReturnType(owner.overloads(), completer, this), dfa);
    }

    CheckException error(int pos, String message) {
        return new CheckException(source, pos, message);
    }

    SyntheticCalls synthetic() {
        return owner.synthetic();
    }

    ArgumentChecker arguments() {
        return owner.arguments();
    }

    DataFlowAnalyzer dfa() {
        return dfa;
    }

    CallResolver calls() {
        return calls;
    }

    CallCompleter completer() {
        return completer;
    }

    // ==== Expressions ====

    TExpr expr(Expr e, Context ctx, Mode mode) {
        return switch (e) {
            case Expr.IntLit l -> integerLiteral(l, false, mode);
            case Expr.FloatLit l -> floatLiteral(l);
            case Expr.CharLit l -> new TExpr.Const(l.pos(), type("kotlin.Char"), l.value());
            case Expr.BoolLit l -> new TExpr.Const(l.pos(), bool(), l.value());
            case Expr.NullLit l -> new TExpr.Const(l.pos(), ts.nullableNothing(), null);
            case Expr.StringTemplate s -> stringTemplate(s, ctx);
            case Expr.Paren p -> expr(p.expr(), ctx, mode);
            case Expr.Annotated a -> expr(a.expr(), ctx, mode);
            case Expr.Name n -> name(n, ctx, mode);
            case Expr.Member m -> member(m, ctx, mode);
            case Expr.Call c -> call(c, ctx, mode);
            case Expr.InfixCall i -> operatorCall(i.pos(), i.name(), receiver(i.left(), ctx),
                    List.of(i.right()), TExpr.CallOrigin.INFIX, ctx, mode);
            case Expr.Index i -> operatorCall(i.pos(), "get", receiver(i.receiver(), ctx), i.indices(),
                    TExpr.CallOrigin.OPERATOR, ctx, mode);
            case Expr.Prefix p when p.op() == Expr.PrefixOp.INC || p.op() == Expr.PrefixOp.DEC ->
                    incrementOrDecrement(p.pos(), p.expr(), p.op() == Expr.PrefixOp.INC, true, ctx);
            case Expr.Prefix p -> prefix(p, ctx, mode);
            case Expr.This t -> thisExpr(t, ctx);
            case Expr.Block b -> block(b.pos(), b.statements(), ctx, mode);
            case Expr.DeclExpr d -> declaration(d.decl(), ctx);
            case Expr.If i -> ifExpr(i, ctx, mode);
            case Expr.When w -> whenExpr(w, ctx, mode);
            case Expr.Try t -> tryExpr(t, ctx, mode);
            case Expr.Binary b -> binary(b, ctx, mode);
            case Expr.Postfix p when p.op() == Expr.PostfixOp.NOT_NULL -> checkNotNull(p, ctx, mode);
            case Expr.Postfix p -> incrementOrDecrement(p.pos(), p.expr(), p.op() == Expr.PostfixOp.INC, false, ctx);
            case Expr.Is i -> {
                TExpr operand = expr(i.expr(), ctx, Mode.INDEPENDENT);
                yield typeCheck(i.pos(), operand, typeOperand(i.type(), operand.type(), ctx), i.negated());
            }
            case Expr.As a -> cast(a, ctx);
            case Expr.Assign a -> assign(a, ctx);
            case Expr.Return r -> returnExpr(r, ctx);
            case Expr.Throw t -> {
                TExpr thrown = expr(t.expr(), ctx, Mode.expecting(type("kotlin.Throwable")));
                dfa.markDead();
                yield new TExpr.Throw(t.pos(), ts.nothing(), thrown);
            }
            case Expr.Break b -> {
                TExpr.Loop loop = loop(b.pos(), b.label(), ctx);
                dfa.breakLoop(loop);
                yield new TExpr.Break(b.pos(), ts.nothing(), loop);
            }
            case Expr.Continue c -> {
                TExpr.Loop loop = loop(c.pos(), c.label(), ctx);
                dfa.continueLoop(loop);
                yield new TExpr.Continue(c.pos(), ts.nothing(), loop);
            }
            case Expr.While w -> whileLoop(w, null, ctx);
            case Expr.DoWhile w -> doWhileLoop(w, null, ctx);
            case Expr.For f -> forLoop(f, null, ctx);
            case Expr.Labeled l -> labeled(l, ctx, mode);
            case Expr.Lambda l -> lambda(l, null, ctx, mode);
            case Expr.AnonymousFunction f -> lambda(f, null, ctx, mode);
            case Expr.ObjectLiteral o -> objectLiteral(o, ctx);
            case Expr.ClassLiteral c -> classLiteral(c, ctx);
            case Expr.CallableRef r -> callableReference(r, ctx, mode);
            default -> throw error(e.pos(), "Unsupported expression: " + e.getClass().getSimpleName());
        };
    }

    // ==== Literals ====

    /**
     * An integer literal: without a suffix, an integer literal type, which
     * becomes a definite type at once unless the literal is an argument.
     *
     * @param negate for {@code -1}, which is a literal rather than a call when without a suffix
     */
    private TExpr integerLiteral(Expr.IntLit l, boolean negate, Mode mode) {
        String text = l.text().replace("_", "");
        boolean unsignedLong = text.endsWith("ul") || text.endsWith("uL") || text.endsWith("Ul") || text.endsWith("UL");
        boolean unsigned = !unsignedLong && (text.endsWith("u") || text.endsWith("U"));
        boolean isLong = !unsignedLong && (text.endsWith("l") || text.endsWith("L"));
        String digits = text.substring(0, text.length() - (unsignedLong ? 2 : unsigned || isLong ? 1 : 0));
        int radix = 10;
        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            radix = 16;
            digits = digits.substring(2);
        } else if (digits.startsWith("0b") || digits.startsWith("0B")) {
            radix = 2;
            digits = digits.substring(2);
        }
        long value;
        try {
            value = unsigned || unsignedLong ? Long.parseUnsignedLong(digits, radix) : Long.parseLong(digits, radix);
        } catch (NumberFormatException ex) {
            throw error(l.pos(), "The value is out of range");
        }
        if (unsignedLong) return new TExpr.Const(l.pos(), type("kotlin.ULong"), value);
        if (isLong) return new TExpr.Const(l.pos(), type("kotlin.Long"), value);
        if (negate) value = -value;
        KType literalType = integerLiteralType(value, unsigned);
        if (literalType instanceof KType.IntegerLiteral ilt) {
            literalType = switch (mode) {
                // TODO: integer literal operator types for receivers, as in 1.plus(2)
                case Mode.WithExpectedType w -> TypeApproximator.integerLiteralType(ilt, w.type());
                case Mode.ContextIndependent m -> TypeApproximator.integerLiteralType(ilt, null);
                case Mode.Receiver m -> TypeApproximator.integerLiteralType(ilt, null);
                case Mode.ContextDependent m -> ilt;
                case Mode.Delegate m -> ilt;
            };
        }
        return new TExpr.Const(l.pos(), literalType, value);
    }

    /** As ConeIntegerLiteralConstantTypeImpl.create: the types the value fits, or the one if only one. */
    private KType integerLiteralType(long value, boolean unsigned) {
        List<KType.ClassType> possible = new ArrayList<>();
        if (unsigned) {
            if (value >= 0 && value <= 0xFFFF_FFFFL) possible.add(type("kotlin.UInt"));
            possible.add(type("kotlin.ULong"));
            if (value >= 0 && value <= 0xFF) possible.add(type("kotlin.UByte"));
            if (value >= 0 && value <= 0xFFFF) possible.add(type("kotlin.UShort"));
        } else {
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) possible.add(type("kotlin.Int"));
            possible.add(type("kotlin.Long"));
            if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) possible.add(type("kotlin.Byte"));
            if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) possible.add(type("kotlin.Short"));
        }
        if (possible.size() == 1) return possible.getFirst();
        return new KType.IntegerLiteral(value, List.copyOf(possible), unsigned, false);
    }

    private TExpr floatLiteral(Expr.FloatLit l) {
        String text = l.text().replace("_", "");
        boolean isFloat = text.endsWith("f") || text.endsWith("F");
        try {
            if (isFloat) {
                return new TExpr.Const(l.pos(), type("kotlin.Float"), (double) Float.parseFloat(text));
            }
            return new TExpr.Const(l.pos(), type("kotlin.Double"), Double.parseDouble(text));
        } catch (NumberFormatException ex) {
            throw error(l.pos(), "Invalid floating-point literal");
        }
    }

    private TExpr stringTemplate(Expr.StringTemplate s, Context ctx) {
        KType string = type("kotlin.String");
        List<TExpr> parts = new ArrayList<>();
        for (Expr.StringPart part : s.parts()) {
            parts.add(switch (part) {
                case Expr.TextPart t -> new TExpr.Const(s.pos(), string, t.text());
                case Expr.ExprPart p -> expr(p.expr(), ctx, Mode.INDEPENDENT);
            });
        }
        if (parts.size() == 1 && parts.getFirst() instanceof TExpr.Const c && c.value() instanceof String) return c;
        return new TExpr.StringTemplate(s.pos(), string, parts);
    }

    // ==== Names ====

    private TExpr name(Expr.Name n, Context ctx, Mode mode) {
        LocalVariable v = ctx.findLocal(n.name());
        if (v != null && v.kind() == LocalVariable.Kind.BACKING_FIELD) {
            TExpr receiver = null;
            for (Context.ImplicitReceiver r : ctx.receivers()) {
                if (r.owner() == ctx.containingClass()) {
                    receiver = r.expr(n.pos());
                    break;
                }
            }
            return dfa.smartCast(new TExpr.FieldGet(n.pos(), v.type(), v.property(), receiver));
        }
        if (v != null) return dfa.smartCast(new TExpr.LocalGet(n.pos(), v.type(), v));
        return completer.completeCall(resolved(variable(n.pos(), n.name(), null), ctx), mode);
    }

    private TExpr thisExpr(Expr.This t, Context ctx) {
        for (Context.ImplicitReceiver r : ctx.receivers()) {
            if (t.label() == null || t.label().equals(r.label())) {
                return dfa.smartCast(new TExpr.This(t.pos(), r.type(), r.owner(), false));
            }
        }
        throw error(t.pos(), t.label() == null ? "'this' is not defined in this context"
                : "Unresolved label " + t.label());
    }

    // ==== Calls ====

    private static CallInfo variable(int pos, String name, CallInfo.Receiver receiver) {
        return new CallInfo(CallInfo.Kind.VARIABLE, name, receiver, List.of(), List.of(), TExpr.CallOrigin.PLAIN,
                false, pos);
    }

    private static CallInfo function(int pos, String name, CallInfo.Receiver receiver, List<CallInfo.Argument> args,
                                     List<TypeArg> typeArgs, TExpr.CallOrigin origin) {
        return new CallInfo(CallInfo.Kind.FUNCTION, name, receiver, args, typeArgs, origin, false, pos);
    }

    /** The candidate a call resolves to, or an error if none or more than one. */
    private Candidate resolved(CallInfo info, Context ctx) {
        return resolved(info, calls.resolve(info, ctx));
    }

    Candidate resolved(CallInfo info, CallResolver.Result result) {
        List<Candidate> candidates = result.candidates();
        if (result.success() && candidates.size() == 1) return candidates.getFirst();
        if (candidates.isEmpty()) throw error(info.pos(), "Unresolved reference " + info.name());
        if (result.success()) throw error(info.pos(), "Ambiguous call of " + info.name() + ": " + candidates);
        throw error(info.pos(), "No applicable " + info.name() + ": " + candidates);
    }

    /** {@code a.b} or {@code a?.b} as a value. */
    private TExpr member(Expr.Member m, Context ctx, Mode mode) {
        return onReceiver(m.pos(), receiver(m.receiver(), ctx), m.safe(),
                r -> completer.completeCall(resolved(variable(m.pos(), m.name(), r), ctx), mode));
    }

    private TExpr call(Expr.Call c, Context ctx, Mode mode) {
        List<TypeArg> typeArgs = typeArguments(c.typeArgs(), ctx);
        return switch (c.callee()) {
            case Expr.Name n -> completer.completeCall(resolved(function(c.pos(), n.name(), null,
                    arguments(n.name(), c.args(), c.lambdas(), ctx), typeArgs, TExpr.CallOrigin.PLAIN), ctx), mode);
            case Expr.Member m -> {
                CallInfo.Receiver receiver = receiver(m.receiver(), ctx);
                List<CallInfo.Argument> args = arguments(m.name(), c.args(), c.lambdas(), ctx);
                yield onReceiver(m.pos(), receiver, m.safe(), r -> completer.completeCall(
                        resolved(function(c.pos(), m.name(), r, args, typeArgs, TExpr.CallOrigin.PLAIN), ctx), mode));
            }
            default -> {
                // (f)(x): the invoke of a value.
                TExpr callee = expr(c.callee(), ctx, Mode.RECEIVER);
                CallInfo info = new CallInfo(CallInfo.Kind.FUNCTION, "invoke", new CallInfo.ValueReceiver(callee),
                        arguments("invoke", c.args(), c.lambdas(), ctx), typeArgs, TExpr.CallOrigin.PLAIN, true, c.pos());
                yield completer.completeCall(resolved(info, ctx), mode);
            }
        };
    }

    /**
     * The arguments of a call, checked without completing their calls.
     *
     * @param callee the name of the function called, the default label of lambdas in the arguments
     */
    private List<CallInfo.Argument> arguments(String callee, List<ValueArg> args, List<Expr> lambdas, Context ctx) {
        String outerCallee = calleeNameForLambda;
        calleeNameForLambda = callee;
        try {
            List<CallInfo.Argument> result = new ArrayList<>();
            for (ValueArg a : args) {
                result.add(new CallInfo.Argument(expr(a.value(), ctx, Mode.DEPENDENT), a.name(), a.spread(), false));
            }
            for (Expr l : lambdas) result.add(new CallInfo.Argument(expr(l, ctx, Mode.DEPENDENT), null, false, true));
            return result;
        } finally {
            calleeNameForLambda = outerCallee;
        }
    }

    /** Explicit type arguments; {@code _} is null, to be inferred. */
    private List<TypeArg> typeArguments(List<TypeRef.TypeArg> args, Context ctx) {
        List<TypeArg> result = new ArrayList<>();
        for (TypeRef.TypeArg a : args) {
            boolean underscore = a.type() instanceof TypeRef.Simple t && t.segments().size() == 1
                    && t.segments().getFirst().name().equals("_") && t.segments().getFirst().args().isEmpty();
            if (a.type() == null) throw error(a.pos(), "Projections are not allowed as type arguments of a call");
            result.add(underscore ? null : TypeArg.of(resolveType(a.type(), ctx)));
        }
        return result;
    }

    /**
     * A call on a receiver; for a safe call, on the receiver's value in a
     * variable, which the selector sees as not null.
     */
    private TExpr onReceiver(int pos, CallInfo.Receiver receiver, boolean safe,
                             Function<CallInfo.Receiver, TExpr> selector) {
        if (!safe) return selector.apply(receiver);
        if (!(receiver instanceof CallInfo.ValueReceiver v)) throw error(pos, "A safe call needs a value as receiver");
        LocalVariable subject = temporary("<safe>", Types.makeDefinitelyNotNull(v.value().type()));
        DataFlowAnalyzer.State before = dfa.enterSafeCall(subject, v.value());
        TExpr result = selector.apply(new CallInfo.ValueReceiver(new TExpr.LocalGet(pos, subject.type(), subject)));
        KType type = TExprs.isStatement(result) ? result.type() : Types.makeNullable(result.type());
        TExpr.SafeCall safeCall = new TExpr.SafeCall(pos, type, v.value(), subject, result);
        dfa.exitSafeCall(safeCall, before);
        return safeCall;
    }

    /** The receiver of a member access or call: a value, {@code super}, or a class or package as a qualifier. */
    private CallInfo.Receiver receiver(Expr e, Context ctx) {
        return switch (e) {
            case Expr.Super s -> superReceiver(s, ctx);
            case Expr.Name n when ctx.findLocal(n.name()) == null -> qualifiedName(n.pos(), n.name(), null, ctx);
            case Expr.Member m when !m.safe() -> qualifiedName(m.pos(), m.name(), receiver(m.receiver(), ctx), ctx);
            default -> new CallInfo.ValueReceiver(expr(e, ctx, Mode.RECEIVER));
        };
    }

    /**
     * A name as a receiver: a value, or a class (with its object or
     * companion object, if any) or package, as FIR's resolved qualifiers.
     */
    private CallInfo.Receiver qualifiedName(int pos, String name, CallInfo.Receiver receiver, Context ctx) {
        CallInfo info = variable(pos, name, receiver);
        CallResolver.Result result = calls.resolve(info, ctx);
        Applicability best = Applicability.HIDDEN;
        for (Candidate c : result.candidates()) {
            if (c.applicability().compareTo(best) > 0) best = c.applicability();
        }
        for (Candidate c : result.candidates()) {
            if (c.symbol instanceof ClassifierSymbol classifier && c.applicability() == best
                    && (result.success() || best == Applicability.K2_NO_COMPANION_OBJECT)) {
                TExpr value = result.success() ? completer.completeCall(c, Mode.RECEIVER) : null;
                return new CallInfo.Qualifier(pos, null, classifier, List.of(), value);
            }
        }
        if (!result.success()) {
            String pkg = switch (receiver) {
                case null -> name;
                case CallInfo.Qualifier q when q.classifier() == null -> q.packageName() + "." + name;
                default -> null;
            };
            if (pkg != null && ts.table().packageExists(pkg)) {
                return new CallInfo.Qualifier(pos, pkg, null, List.of(), null);
            }
        }
        return new CallInfo.ValueReceiver(completer.completeCall(resolved(info, result), Mode.RECEIVER));
    }

    private CallInfo.Receiver superReceiver(Expr.Super s, Context ctx) {
        for (Context.ImplicitReceiver r : ctx.receivers()) {
            if (!(r.owner() instanceof ClassSymbol cls) || s.label() != null && !s.label().equals(r.label())) continue;
            List<KType> supertypes = cls.supertypes();
            KType supertype;
            if (s.qualifier() != null) {
                KType written = resolveType(s.qualifier(), ctx);
                supertype = supertypes.stream()
                        .filter(t -> t instanceof KType.ClassType c && written instanceof KType.ClassType w
                                && c.symbol() == w.symbol())
                        .findFirst().orElseThrow(() -> error(s.pos(), written + " is not a supertype"));
            } else {
                supertype = supertypes.size() == 1 ? supertypes.getFirst() : ts.intersect(supertypes);
            }
            return new CallInfo.SuperReceiver(new TExpr.This(s.pos(), r.type(), cls, false), supertype);
        }
        throw error(s.pos(), "'super' is not allowed here");
    }

    // ==== Blocks and declarations ====

    /**
     * A block in a new scope. The last statement gets the block's mode, the
     * others are on their own. With Unit expected, the block has type Unit
     * whatever its last statement.
     */
    TExpr block(int pos, List<Stmt> statements, Context ctx, Mode mode) {
        Context inner = ctx.withLocalScope(new LocalScope());
        return blockInScope(pos, statements, inner, mode);
    }

    private TExpr blockInScope(int pos, List<Stmt> statements, Context ctx, Mode mode) {
        List<TExpr> result = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            Mode m = Mode.INDEPENDENT;
            if (i == statements.size() - 1) {
                m = mode instanceof Mode.WithExpectedType w
                        ? new Mode.WithExpectedType(w.type(), true, w.fromCast(), w.forceFullCompletion()) : mode;
            }
            if (statements.get(i) instanceof Decl.Destructuring d) {
                destructuringDeclaration(d, ctx, result);
            } else {
                result.add(statement(statements.get(i), ctx, m));
            }
        }
        TExpr.Block block = new TExpr.Block(pos, unit(), result);
        KType type;
        if (mode instanceof Mode.WithExpectedType w && isUnitOrFlexibleUnit(w.type())) {
            type = w.type();
        } else {
            TExpr last = TExprs.lastExpression(block);
            type = last == null ? unit() : last.type();
        }
        return new TExpr.Block(pos, type, result);
    }

    /** The body of a branch or loop, which is a block even if written as a single expression. */
    private TExpr branch(int pos, Expr body, Context ctx, Mode mode) {
        if (body == null) return block(pos, List.of(), ctx, mode);
        if (body instanceof Expr.Block b) return block(b.pos(), b.statements(), ctx, mode);
        return block(body.pos(), List.of(body), ctx, mode);
    }

    private TExpr statement(Stmt s, Context ctx, Mode mode) {
        return switch (s) {
            case Decl d -> declaration(d, ctx);
            case Expr e -> expr(e, ctx, mode);
        };
    }

    /** A local declaration, added to the innermost local scope. */
    private TExpr declaration(Decl d, Context ctx) {
        return switch (d) {
            case Decl.Property p -> localVariable(p, ctx);
            case Decl.Function f -> {
                CheckedFunction function = owner.localFunction(f, this, ctx, ctx.locals().getLast());
                yield new TExpr.LocalFunctionDecl(f.pos(), unit(), (FunctionSymbol) function.symbol(), function);
            }
            case Decl.Class c -> new TExpr.LocalClassDecl(c.pos(), unit(),
                    owner.localClass(c, this, ctx, ctx.locals().getLast()));
            default -> throw error(d.pos(), "Unsupported local declaration: " + d.getClass().getSimpleName());
        };
    }

    /**
     * {@code A::class}, of type {@code KClass<A>}, with star projections for
     * arguments not given; or {@code a::class}, of type {@code KClass<out A>}
     * for the type of {@code a}. As FIR, a value of the name comes first.
     */
    private TExpr classLiteral(Expr.ClassLiteral c, Context ctx) {
        if (c.receiver() == null) throw error(c.pos(), "'::class' needs a class or value on the left");
        Expr lhs = c.receiver();
        boolean isType = lhs instanceof Expr.TypeApplication
                || lhs instanceof Expr.Name n && ctx.findLocal(n.name()) == null
                        && ctx.findClassifier(n.name()) instanceof TypeParamSymbol;
        TExpr value = null;
        if (!isType) {
            CallInfo.Receiver r = receiver(lhs, ctx);
            switch (r) {
                case CallInfo.Qualifier q when q.classifier() != null -> isType = true;
                case CallInfo.ValueReceiver v -> value = v.value();
                default -> throw error(c.pos(), "'::class' needs a class or value on the left");
            }
        }
        TypeArg arg;
        if (isType) {
            arg = TypeArg.of(doubleColonLhsType(lhs, ctx));
        } else {
            arg = new TypeArg(Variance.OUT, value.type());
        }
        KType.ClassType kclass = owner.resolver().types().classType("kotlin.reflect.KClass", arg);
        return new TExpr.ClassReference(c.pos(), kclass, arg.type(), value);
    }

    /** The class on the left of {@code ::}, with star projections for the type arguments not given. */
    private KType doubleColonLhsType(Expr lhs, Context ctx) {
        TypeRef ref = asType(lhs);
        if (ref == null) throw error(lhs.pos(), "Unsupported left side of '::'");
        KType type = owner.resolver().types().resolveAllowingBare(ref, ctx.typeScope(), ctx.file());
        if (BareTypes.isBare(type) && Types.lowerBound(type) instanceof KType.ClassType bare) {
            type = new KType.ClassType(bare.symbol(),
                    Collections.nCopies(bare.symbol().typeParams().size(), TypeArg.STAR), bare.nullable());
        }
        return type;
    }

    /** A possibly qualified name with type arguments, as a type; null if not one. */
    private static TypeRef asType(Expr e) {
        List<TypeRef.TypeArg> args = List.of();
        if (e instanceof Expr.TypeApplication t) {
            args = t.typeArgs();
            e = t.expr();
        }
        int pos = e.pos();
        List<TypeRef.Segment> segments = new ArrayList<>();
        while (e instanceof Expr.Member m && !m.safe()) {
            segments.addFirst(new TypeRef.Segment(m.name(), segments.isEmpty() ? args : List.of()));
            e = m.receiver();
        }
        if (!(e instanceof Expr.Name n)) return null;
        segments.addFirst(new TypeRef.Segment(n.name(), segments.isEmpty() ? args : List.of()));
        return new TypeRef.Simple(pos, segments);
    }

    // ==== Callable references ====

    /**
     * {@code a::f}, {@code A::f} or {@code ::f}. As an argument, it is
     * resolved with its call; otherwise, as FIR's
     * resolveCallableReferenceWithSyntheticOuterCall, as the argument of a
     * call of {@code fun accept(p: expected)}.
     */
    private TExpr callableReference(Expr.CallableRef r, Context ctx, Mode mode) {
        CallableReferenceExpression reference = callableReferenceExpression(r, ctx);
        TExpr.Unresolved argument = new TExpr.Unresolved(r.pos(), null, reference);
        if (mode instanceof Mode.ContextDependent) return argument;
        KType expected = mode instanceof Mode.WithExpectedType w ? w.type() : null;
        KType parameterType = expected != null && !isUnitOrFlexibleUnit(expected) ? expected : ts.any();
        Candidate candidate = synthetic().acceptCall(r.pos(), parameterType, argument,
                w -> w.writeArgument(argument, parameterType));
        if (reference.atom.error() != null) throw error(r.pos(), reference.atom.error());
        return completer.completeCall(candidate, Mode.INDEPENDENT);
    }

    /**
     * The reference with its left side checked: a value, which it is bound
     * to, or a class or package. A class that is not an object is also a
     * type, whose members and extensions are referenced unbound, as FIR's
     * FirCallableReferenceLhsResolver.
     */
    private CallableReferenceExpression callableReferenceExpression(Expr.CallableRef r, Context ctx) {
        Expr lhs = r.receiver();
        CallInfo.Receiver receiver = null;
        KType lhsAsType = null;
        if (lhs instanceof Expr.TypeApplication) {
            lhsAsType = doubleColonLhsType(lhs, ctx);
            KType.ClassType type = (KType.ClassType) Types.lowerBound(lhsAsType);
            receiver = new CallInfo.Qualifier(lhs.pos(), null, type.symbol(),
                    type.args().stream().map(a -> a.isStar() ? ts.nullableAny() : a.type()).toList(), null);
        } else if (lhs != null) {
            receiver = receiver(lhs, ctx);
            if (receiver instanceof CallInfo.Qualifier q && q.classifier() != null) {
                KType type = doubleColonLhsType(lhs, ctx);
                // An object is only a value.
                if (!(Types.lowerBound(type) instanceof KType.ClassType c && c.symbol().isObject())) lhsAsType = type;
            }
        }
        return new CallableReferenceExpression(r.pos(), r.name(), receiver, lhsAsType, ctx, calls);
    }

    /**
     * Resolves a reference not resolved with its call, or ambiguous then,
     * as FIR's processCallableReference; ambiguous again, it waits for its
     * expected type to be known better.
     */
    private void processCallableReference(CallableReferenceAtom atom, Candidate candidate) {
        if (atom.needsResolution()) {
            calls.resolveCallableReference(candidate, atom, false);
            if (atom.state == CallableReferenceAtom.State.POSTPONED_BECAUSE_OF_AMBIGUITY) return;
        }
        Candidate resolved = atom.resolved();
        if (resolved == null) throw error(atom.expression.pos, atom.error());
        atom.setResult(new TExpr.Unresolved(atom.expression.pos, resolved.referenceType, resolved));
    }

    /** {@code object : A { ... }}: of the type of its anonymous class. */
    private TExpr objectLiteral(Expr.ObjectLiteral o, Context ctx) {
        ClassSymbol cls = owner.localClass(o.decl(), this, ctx, null);
        return new TExpr.ObjectLiteral(o.pos(), owner.resolver().types().defaultType(cls), cls);
    }

    private TExpr localVariable(Decl.Property p, Context ctx) {
        KType declared = p.type() == null ? null : resolveType(p.type(), ctx);
        if (p.delegate() != null) return localDelegatedVariable(p, declared, ctx);
        TExpr initializer = null;
        if (p.initializer() != null) {
            initializer = expr(p.initializer(), ctx, declared != null ? Mode.expecting(declared) : Mode.INDEPENDENT);
        }
        KType type = declared;
        if (type == null) {
            if (initializer == null) throw error(p.pos(), "A local variable needs a type or an initializer");
            type = approximateLocalType(initializer.type());
        }
        LocalVariable v = new LocalVariable(p.name(), type, LocalVariable.Kind.LOCAL, p.isVar(), null);
        ctx.locals().getLast().add(v);
        if (initializer != null) dfa.localVariableInitialized(v, initializer, declared != null, false);
        return new TExpr.LocalVarDecl(p.pos(), unit(), v, initializer);
    }

    /**
     * {@code val x by d}: a property local to the body, as in FIR, which
     * {@code ::x} refers to; its delegate is checked where it is declared.
     */
    private TExpr localDelegatedVariable(Decl.Property p, KType declared, Context ctx) {
        LocalVariable[] variable = new LocalVariable[1];
        PropertySymbol symbol = new PropertySymbol(p.name(), Origin.KOTLIN_SOURCE, null, Visibility.PUBLIC,
                Modality.FINAL, Set.of(), false, p, p.isVar(), false, false, null, List::of,
                () -> CallableSymbol.Signature.NONE, () -> variable[0].type());
        CheckedDelegate delegate = new DelegatedProperties(this, ts).check(symbol, p.delegate(), declared, null, null,
                ctx, ctx, this::approximateLocalType);
        variable[0] = LocalVariable.delegated(symbol, delegate.getter().returnType());
        ctx.locals().getLast().add(variable[0]);
        return new TExpr.LocalDelegatedVarDecl(p.pos(), unit(), variable[0], delegate);
    }

    /** {@code val (a, b) = x}: a temporary for {@code x}, then the entries. */
    private void destructuringDeclaration(Decl.Destructuring d, Context ctx, List<TExpr> out) {
        if (d.initializer() == null) throw error(d.pos(), "A destructuring declaration needs an initializer");
        TExpr initializer = expr(d.initializer(), ctx, Mode.INDEPENDENT);
        LocalVariable source = temporary("<destruct>", approximateLocalType(initializer.type()));
        out.add(new TExpr.LocalVarDecl(d.pos(), unit(), source, initializer));
        dfa.localVariableInitialized(source, initializer, false, false);
        destructure(d, source, ctx, out);
    }

    /**
     * The entries of a destructuring of a variable: {@code val a = x.component1()}
     * and so on, skipping {@code _}; for the full form {@code (val a, val b)},
     * which destructures by name, {@code val a = x.a}.
     */
    private void destructure(Decl.Destructuring d, LocalVariable source, Context ctx, List<TExpr> out) {
        boolean byName = !d.squareBrackets() && d.valOrVar() == Decl.ValOrVar.NONE
                && d.entries().stream().allMatch(e -> e.valOrVar() != Decl.ValOrVar.NONE);
        for (int i = 0; i < d.entries().size(); i++) {
            Decl.DestructuringEntry e = d.entries().get(i);
            if (e.name().equals("_") && (!byName || e.sourceName() == null)) continue;
            KType declared = e.type() == null ? null : resolveType(e.type(), ctx);
            Mode mode = declared != null ? Mode.expecting(declared) : Mode.INDEPENDENT;
            CallInfo.Receiver r = localValue(e.pos(), source);
            TExpr value = byName
                    ? completer.completeCall(resolved(variable(e.pos(), e.sourceName() != null ? e.sourceName() : e.name(),
                            r), ctx), mode)
                    : desugaredCall(e.pos(), "component" + (i + 1), r, ctx, mode);
            boolean isVar = (byName ? e.valOrVar() : d.valOrVar()) == Decl.ValOrVar.VAR;
            LocalVariable v = new LocalVariable(e.name(), declared != null ? declared : approximateLocalType(value.type()),
                    LocalVariable.Kind.LOCAL, isVar, null);
            if (!e.name().equals("_")) ctx.locals().getLast().add(v);
            out.add(new TExpr.LocalVarDecl(e.pos(), unit(), v, value));
            dfa.localVariableInitialized(v, value, declared != null, false);
        }
    }

    private static LocalVariable temporary(String name, KType type) {
        return new LocalVariable(name, type, LocalVariable.Kind.TEMPORARY, false, null);
    }

    private static CallInfo.Receiver localValue(int pos, LocalVariable v) {
        return new CallInfo.ValueReceiver(new TExpr.LocalGet(pos, v.type(), v));
    }

    /** A call of an operator convention without arguments, such as {@code iterator()} or {@code component1()}. */
    private TExpr desugaredCall(int pos, String name, CallInfo.Receiver receiver, Context ctx, Mode mode) {
        return completer.completeCall(resolved(function(pos, name, receiver, List.of(), List.of(),
                TExpr.CallOrigin.DESUGARED), ctx), mode);
    }

    KType approximateLocalType(KType type) {
        return TypeApproximator.approximateToSuperTypeOrSelf(ts, type, TypeApproximator.Config.LOCAL_DECLARATION);
    }

    // ==== Assignments ====

    /** {@code a = b}, {@code a[i] = b} (which is {@code a.set(i, b)}), and {@code a += b} and the like. */
    private TExpr assign(Expr.Assign a, Context ctx) {
        Expr target = assignable(a.target());
        if (a.op() != Expr.AssignOp.ASSIGN) {
            String operator = switch (a.op()) {
                case PLUS_ASSIGN -> "plus";
                case MINUS_ASSIGN -> "minus";
                case MUL_ASSIGN -> "times";
                case DIV_ASSIGN -> "div";
                default -> "rem";
            };
            if (target instanceof Expr.Index i) return indexedAugmentedAssign(a.pos(), i, operator, a.value(), ctx);
            return onVariable(target, ctx, Mode.RECEIVER, left -> augmentedAssign(a.pos(), left, operator,
                    expr(a.value(), ctx, Mode.DEPENDENT), ctx));
        }
        if (target instanceof Expr.Index i) {
            List<Expr> args = new ArrayList<>(i.indices());
            args.add(a.value());
            return operatorCall(a.pos(), "set", receiver(i.receiver(), ctx), args, TExpr.CallOrigin.OPERATOR, ctx,
                    Mode.INDEPENDENT);
        }
        return onVariable(target, ctx, Mode.INDEPENDENT,
                v -> assignment(a.pos(), v, expr(a.value(), ctx, Mode.expecting(withoutSmartCast(v).type()))));
    }

    private static TExpr withoutSmartCast(TExpr e) {
        return e instanceof TExpr.SmartCast s ? s.expr() : e;
    }

    /** The target of an assignment or increment, without parentheses, labels and annotations. */
    private static Expr assignable(Expr e) {
        while (true) {
            switch (e) {
                case Expr.Paren p -> e = p.expr();
                case Expr.Labeled l -> e = l.expr();
                case Expr.Annotated a -> e = a.expr();
                default -> {
                    return e;
                }
            }
        }
    }

    /**
     * Applies the action to the read of the variable a name or member
     * access refers to; for {@code a?.b}, inside the safe call.
     */
    private TExpr onVariable(Expr target, Context ctx, Mode mode, Function<TExpr, TExpr> action) {
        return switch (target) {
            case Expr.Name n -> action.apply(name(n, ctx, mode));
            case Expr.Member m -> onReceiver(m.pos(), receiver(m.receiver(), ctx), m.safe(), r -> action.apply(
                    completer.completeCall(resolved(variable(m.pos(), m.name(), r), ctx), mode)));
            default -> throw error(target.pos(), "Unsupported assignment target");
        };
    }

    /** The write of the variable the expression reads. */
    private TExpr assignment(int pos, TExpr variable, TExpr value) {
        TExpr target = withoutSmartCast(variable);
        TExpr result = switch (target) {
            case TExpr.LocalGet g -> new TExpr.LocalSet(pos, unit(), g.variable(), value);
            case TExpr.PropertyGet g -> new TExpr.PropertySet(pos, unit(), g.property(), g.typeArgs(),
                    g.dispatchReceiver(), g.extensionReceiver(), g.superCall(), value);
            case TExpr.FieldGet g -> new TExpr.FieldSet(pos, unit(), g.property(), g.receiver(), value);
            default -> throw error(pos, "Not assignable");
        };
        dfa.assigned(target, value);
        return result;
    }

    /**
     * {@code a += b}: {@code a.plusAssign(b)} or {@code a = a.plus(b)}, as
     * FIR chooses; the latter if only it resolves, or if both do, {@code a}
     * is a var and the result of plus fits it (else it is ambiguous).
     */
    private TExpr augmentedAssign(int pos, TExpr left, String operator, TExpr right, Context ctx) {
        CallInfo assignInfo = operator(pos, operator + "Assign", new CallInfo.ValueReceiver(left), List.of(right));
        CallResolver.Result assign = calls.resolve(assignInfo, ctx);
        List<TExpr> statements = new ArrayList<>();
        TExpr variable = withReceiverTemporaries(left, statements);
        CallInfo operatorInfo = operator(pos, operator, new CallInfo.ValueReceiver(variable), List.of(right));
        CallResolver.Result op = calls.resolve(operatorInfo, ctx);
        boolean isVar = switch (withoutSmartCast(left)) {
            case TExpr.LocalGet g -> g.variable().isVar();
            case TExpr.PropertyGet g -> g.property().isVar();
            case TExpr.FieldGet g -> true;
            default -> false;
        };
        boolean chooseAssign;
        if (isSuccess(assign) && !isVar) {
            chooseAssign = true;
        } else if (!isSuccess(assign) && !isSuccess(op)) {
            chooseAssign = !assign.candidates().isEmpty() || op.candidates().isEmpty();
        } else if (isSuccess(assign) != isSuccess(op)) {
            chooseAssign = isSuccess(assign);
        } else {
            Candidate c = op.candidates().getFirst();
            KType returnType = c.system.buildCurrentSubstitutor().apply(c.returnType());
            if (ts.isSubtype(returnType, left.type())) throw error(pos, "Ambiguous " + operator + "Assign and " + operator);
            chooseAssign = true;
        }
        if (chooseAssign) return completer.completeCall(resolved(assignInfo, assign), Mode.INDEPENDENT);
        TExpr value = completer.completeCall(resolved(operatorInfo, op),
                Mode.expecting(withoutSmartCast(variable).type()));
        statements.add(assignment(pos, variable, value));
        return statements.size() == 1 ? statements.getFirst() : new TExpr.Block(pos, unit(), statements);
    }

    /**
     * {@code a[i] += b}: {@code a.get(i).plusAssign(b)}, or with {@code a}
     * and {@code i} in temporaries, {@code a.set(i, a.get(i).plus(b))};
     * ambiguous if both resolve.
     */
    private TExpr indexedAugmentedAssign(int pos, Expr.Index target, String operator, Expr value, Context ctx) {
        CallInfo.Receiver array = receiver(target.receiver(), ctx);
        List<TExpr> indices = new ArrayList<>();
        for (Expr e : target.indices()) indices.add(expr(e, ctx, Mode.INDEPENDENT));
        TExpr get = completer.completeCall(resolved(operator(pos, "get", array, indices), ctx), Mode.INDEPENDENT);
        TExpr right = expr(value, ctx, Mode.DEPENDENT);
        CallInfo assignInfo = operator(pos, operator + "Assign", new CallInfo.ValueReceiver(get), List.of(right));
        CallResolver.Result assign = calls.resolve(assignInfo, ctx);

        List<TExpr> statements = new ArrayList<>();
        CallInfo.Receiver arrayTemp = array instanceof CallInfo.ValueReceiver v
                ? new CallInfo.ValueReceiver(temporaryFor("<array>", v.value(), statements)) : array;
        List<TExpr> indexTemps = new ArrayList<>();
        for (int k = 0; k < indices.size(); k++) indexTemps.add(temporaryFor("<index" + k + ">", indices.get(k), statements));
        TExpr tempGet = completer.completeCall(resolved(operator(pos, "get", arrayTemp, indexTemps), ctx),
                Mode.INDEPENDENT);
        CallInfo operatorInfo = operator(pos, operator, new CallInfo.ValueReceiver(tempGet), List.of(right));
        CallResolver.Result op = calls.resolve(operatorInfo, ctx);
        if (!isSuccess(op)) return completer.completeCall(resolved(assignInfo, assign), Mode.INDEPENDENT);

        List<TExpr> setArgs = new ArrayList<>(indexTemps);
        setArgs.add(completer.completeCall(resolved(operatorInfo, op), Mode.DEPENDENT));
        CallInfo setInfo = operator(pos, "set", arrayTemp, setArgs);
        CallResolver.Result set = calls.resolve(setInfo, ctx);
        if (isSuccess(assign) && isSuccess(set)) throw error(pos, "Ambiguous " + operator + "Assign and set");
        if (isSuccess(assign)) return completer.completeCall(resolved(assignInfo, assign), Mode.INDEPENDENT);
        if (!isSuccess(set)) throw error(pos, "Unresolved reference " + operator + "Assign");
        statements.add(completer.completeCall(resolved(setInfo, set), Mode.INDEPENDENT));
        return new TExpr.Block(pos, unit(), statements);
    }

    /**
     * {@code ++a} and {@code a++}, as FIR desugars them: {@code a = a.inc()}
     * then the new value, or the old value in a temporary. For {@code a[i]},
     * through get and set.
     */
    private TExpr incrementOrDecrement(int pos, Expr operand, boolean increment, boolean prefix, Context ctx) {
        String name = increment ? "inc" : "dec";
        Expr target = assignable(operand);
        if (target instanceof Expr.Index i) return indexedIncrementOrDecrement(pos, i, name, prefix, ctx);
        return onVariable(target, ctx, Mode.INDEPENDENT, read -> {
            List<TExpr> statements = new ArrayList<>();
            TExpr variable = withReceiverTemporaries(read, statements);
            if (prefix) {
                statements.add(assignment(pos, variable, unaryOperator(pos, name, variable, ctx)));
                statements.add(variable);
            } else {
                TExpr unary = temporaryFor("<unary>", variable, statements);
                statements.add(assignment(pos, variable, unaryOperator(pos, name, unary, ctx)));
                statements.add(unary);
            }
            return new TExpr.Block(pos, statements.getLast().type(), statements);
        });
    }

    /** {@code a[i]++} is {@code a.set(i, a.get(i).inc())} with {@code a}, {@code i} and the old value in temporaries. */
    private TExpr indexedIncrementOrDecrement(int pos, Expr.Index target, String name, boolean prefix, Context ctx) {
        List<TExpr> statements = new ArrayList<>();
        CallInfo.Receiver array = receiver(target.receiver(), ctx);
        if (array instanceof CallInfo.ValueReceiver v) {
            array = new CallInfo.ValueReceiver(temporaryFor("<array>", v.value(), statements));
        }
        List<TExpr> indices = new ArrayList<>();
        for (int k = 0; k < target.indices().size(); k++) {
            indices.add(temporaryFor("<index" + k + ">", expr(target.indices().get(k), ctx, Mode.INDEPENDENT),
                    statements));
        }
        TExpr get = completer.completeCall(resolved(operator(pos, "get", array, indices), ctx), Mode.INDEPENDENT);
        TExpr old = prefix ? get : temporaryFor("<unary>", get, statements);
        List<TExpr> setArgs = new ArrayList<>(indices);
        setArgs.add(unaryOperator(pos, name, old, ctx));
        statements.add(completer.completeCall(resolved(operator(pos, "set", array, setArgs), ctx), Mode.INDEPENDENT));
        statements.add(prefix ? completer.completeCall(resolved(operator(pos, "get", array, indices), ctx),
                Mode.INDEPENDENT) : old);
        return new TExpr.Block(pos, statements.getLast().type(), statements);
    }

    private TExpr unaryOperator(int pos, String name, TExpr receiver, Context ctx) {
        return completer.completeCall(resolved(operator(pos, name, new CallInfo.ValueReceiver(receiver), List.of()), ctx),
                Mode.INDEPENDENT);
    }

    /** An operator call with checked arguments. */
    private static CallInfo operator(int pos, String name, CallInfo.Receiver receiver, List<TExpr> args) {
        return function(pos, name, receiver, args.stream().map(a -> new CallInfo.Argument(a, null, false, false)).toList(),
                List.of(), TExpr.CallOrigin.OPERATOR);
    }

    private static boolean isSuccess(CallResolver.Result result) {
        return result.success() && result.candidates().size() == 1;
    }

    /**
     * The variable read with its explicit receiver, if any, in a temporary
     * added to the statements, so that it is evaluated once for both the
     * read and the write. Qualifiers and {@code this} need none.
     */
    private TExpr withReceiverTemporaries(TExpr variable, List<TExpr> statements) {
        if (!(withoutSmartCast(variable) instanceof TExpr.PropertyGet g)) return variable;
        TExpr dispatch = g.dispatchReceiver();
        TExpr extension = g.extensionReceiver();
        if (needsTemporary(dispatch)) dispatch = temporaryFor("<receiver>", dispatch, statements);
        if (needsTemporary(extension)) extension = temporaryFor("<receiver>", extension, statements);
        TExpr read = new TExpr.PropertyGet(g.pos(), g.type(), g.property(), g.typeArgs(), dispatch, extension,
                g.superCall());
        return variable instanceof TExpr.SmartCast s ? new TExpr.SmartCast(s.pos(), s.type(), read) : read;
    }

    private static boolean needsTemporary(TExpr receiver) {
        return receiver != null && !(receiver instanceof TExpr.This || receiver instanceof TExpr.ObjectValue);
    }

    /** Declares a temporary with the value, added to the statements, and returns its read. */
    private TExpr temporaryFor(String name, TExpr value, List<TExpr> statements) {
        LocalVariable v = temporary(name, value.type());
        statements.add(new TExpr.LocalVarDecl(value.pos(), unit(), v, value));
        return new TExpr.LocalGet(value.pos(), v.type(), v);
    }

    // ==== When and if ====

    private TExpr ifExpr(Expr.If i, Context ctx, Mode mode) {
        boolean exhaustive = i.otherwise() != null;
        Mode branchMode = branchMode(exhaustive ? 2 : 1, mode);
        TExpr condition = expr(i.condition(), ctx, Mode.expecting(bool()));
        DataFlowAnalyzer.State conditionExit = dfa.save();
        dfa.assume(condition, true);
        TExpr then = branch(i.pos(), i.then(), ctx, branchMode);
        DataFlowAnalyzer.State thenExit = dfa.save();
        dfa.restore(conditionExit);
        dfa.assume(condition, false);
        TExpr otherwise = exhaustive ? branch(i.pos(), i.otherwise(), ctx, branchMode) : null;
        dfa.join(List.of(thenExit, dfa.save()));
        if (branchMode == Mode.INDEPENDENT) {
            return new TExpr.If(i.pos(), exhaustive ? then.type() : unit(), condition, then, otherwise);
        }
        List<TExpr> bodies = exhaustive ? List.of(then, otherwise) : List.of(then);
        Candidate candidate = synthetic().whenCall(i.pos(), bodies, null);
        candidate.builder = w -> new TExpr.If(i.pos(), exhaustive ? w.callType(candidate, candidate.returnType()) : unit(),
                condition, w.writeArgument(then, SyntheticCalls.parameterType(candidate, 0)),
                otherwise == null ? null : w.writeArgument(otherwise, SyntheticCalls.parameterType(candidate, 1)));
        return completer.completeCall(candidate, exhaustive ? mode : Mode.INDEPENDENT);
    }

    /**
     * The mode for the branches of a when: a when with one branch, checked
     * on its own, has its branch checked on its own; otherwise the branches
     * are arguments of the when's synthetic call.
     */
    private static Mode branchMode(int branches, Mode mode) {
        if (branches == 1 && mode.forceFullCompletion() && !(mode instanceof Mode.WithExpectedType)) {
            return Mode.INDEPENDENT;
        }
        if (mode instanceof Mode.WithExpectedType w && !w.fromCast()) {
            return new Mode.WithExpectedType(w.type(), w.lastStatementInBlock(), false, false);
        }
        return Mode.DEPENDENT;
    }

    private TExpr whenExpr(Expr.When w, Context ctx, Mode mode) {
        Context inner = ctx.withLocalScope(new LocalScope());
        TExpr subject = null;
        LocalVariable subjectVariable = null;
        if (w.subjectVariable() != null) {
            subject = localVariable(w.subjectVariable(), inner);
            subjectVariable = ((TExpr.LocalVarDecl) subject).variable();
        } else if (w.subject() != null) {
            TExpr value = expr(w.subject(), inner, Mode.INDEPENDENT);
            subjectVariable = new LocalVariable("<subject>", value.type(), LocalVariable.Kind.TEMPORARY, false, null);
            subject = new TExpr.LocalVarDecl(w.subject().pos(), unit(), subjectVariable, value);
            dfa.localVariableInitialized(subjectVariable, value, false, true);
        }
        if (w.entries().isEmpty()) return new TExpr.When(w.pos(), unit(), subject, List.of(), false);

        Mode branchMode = branchMode(w.entries().size(), mode);
        List<TExpr> conditions = new ArrayList<>();
        List<TExpr> bodies = new ArrayList<>();
        List<DataFlowAnalyzer.State> exits = new ArrayList<>();
        // Each condition is checked where the ones before it were false.
        for (Expr.WhenEntry entry : w.entries()) {
            TExpr condition = whenCondition(entry, subjectVariable, inner);
            conditions.add(condition);
            DataFlowAnalyzer.State conditionExit = dfa.save();
            if (condition != null) dfa.assume(condition, true);
            bodies.add(branch(entry.pos(), entry.body(), inner, branchMode));
            exits.add(dfa.save());
            dfa.restore(conditionExit);
            if (condition != null) dfa.assume(condition, false);
        }
        boolean exhaustive = isExhaustive(w, subjectVariable, conditions);
        // Past the last condition only if no branch is always taken.
        if (!exhaustive) exits.add(dfa.save());
        dfa.join(exits);
        TExpr finalSubject = subject;
        if (branchMode == Mode.INDEPENDENT) {
            return new TExpr.When(w.pos(), exhaustive ? bodies.getFirst().type() : unit(), subject,
                    List.of(new TExpr.WhenBranch(conditions.getFirst(), bodies.getFirst())), exhaustive);
        }
        Candidate candidate = synthetic().whenCall(w.pos(), bodies, null);
        candidate.builder = wr -> {
            List<TExpr.WhenBranch> branches = new ArrayList<>();
            for (int i = 0; i < bodies.size(); i++) {
                branches.add(new TExpr.WhenBranch(conditions.get(i),
                        wr.writeArgument(bodies.get(i), SyntheticCalls.parameterType(candidate, i))));
            }
            KType type = exhaustive ? wr.callType(candidate, candidate.returnType()) : unit();
            return new TExpr.When(w.pos(), type, finalSubject, branches, exhaustive);
        };
        return completer.completeCall(candidate, exhaustive ? mode : Mode.INDEPENDENT);
    }

    /** The condition of a when entry as a Boolean expression; null for else. */
    private TExpr whenCondition(Expr.WhenEntry entry, LocalVariable subject, Context ctx) {
        TExpr condition = null;
        for (Expr.WhenCondition c : entry.conditions()) {
            condition = condition == null ? whenTest(c, subject, ctx)
                    : logical(c.pos(), false, condition, () -> whenTest(c, subject, ctx));
        }
        if (entry.guard() != null) {
            condition = condition == null ? expr(entry.guard(), ctx, Mode.expecting(bool()))
                    : logical(entry.guard().pos(), true, condition, () -> expr(entry.guard(), ctx, Mode.expecting(bool())));
        }
        return condition;
    }

    private TExpr whenTest(Expr.WhenCondition c, LocalVariable subject, Context ctx) {
        return switch (c) {
            case Expr.ExprCondition e when subject == null -> expr(e.expr(), ctx, Mode.expecting(bool()));
            case Expr.ExprCondition e -> equality(e.pos(), Expr.BinaryOp.EQ,
                    dfa.smartCast(new TExpr.LocalGet(e.pos(), subject.type(), subject)), e.expr(), ctx, Mode.INDEPENDENT);
            case Expr.IsCondition i when subject != null -> {
                TExpr operand = dfa.smartCast(new TExpr.LocalGet(i.pos(), subject.type(), subject));
                yield typeCheck(i.pos(), operand, typeOperand(i.type(), operand.type(), ctx), i.negated());
            }
            case Expr.InCondition i when subject != null -> throw error(i.pos(), "'in' conditions are not supported yet");
            default -> throw error(c.pos(), "A when without a subject needs Boolean conditions");
        };
    }

    // ==== Exhaustiveness ====

    /** What the conditions of a when match of its subject. */
    private static final class Coverage {
        boolean isNull;
        final Set<Boolean> booleans = new HashSet<>();
        /** Enum entries and objects compared with. */
        final Set<Object> values = new HashSet<>();
        /** Classes tested with is. */
        final List<ClassSymbol> classes = new ArrayList<>();
    }

    /**
     * Whether some branch is always taken, as FIR's
     * FirWhenExhaustivenessTransformer: there is an else, or the conditions
     * without guards match every value of the subject's type.
     */
    private boolean isExhaustive(Expr.When w, LocalVariable subject, List<TExpr> conditions) {
        for (Expr.WhenEntry entry : w.entries()) {
            if (entry.conditions().isEmpty() && entry.guard() == null) return true;
        }
        if (subject == null) return false;
        Coverage coverage = new Coverage();
        for (int i = 0; i < conditions.size(); i++) {
            if (w.entries().get(i).guard() == null) collect(conditions.get(i), subject, coverage);
        }
        return covers(subject.type(), coverage);
    }

    private static void collect(TExpr condition, LocalVariable subject, Coverage coverage) {
        switch (condition) {
            case TExpr.Logical l when !l.and() -> {
                collect(l.left(), subject, coverage);
                collect(l.right(), subject, coverage);
            }
            case TExpr.Equality e when (e.op() == TExpr.EqualityOp.EQ || e.op() == TExpr.EqualityOp.IDENTITY_EQ)
                    && withoutSmartCast(e.left()) instanceof TExpr.LocalGet g && g.variable() == subject -> {
                switch (e.right()) {
                    case TExpr.Const c when c.value() == null -> coverage.isNull = true;
                    case TExpr.Const c when c.value() instanceof Boolean b -> coverage.booleans.add(b);
                    case TExpr.PropertyGet p when p.property().isEnumEntry() -> coverage.values.add(p.property());
                    case TExpr.ObjectValue o -> coverage.values.add(o.object());
                    default -> {
                    }
                }
            }
            case TExpr.TypeCheck t when !t.negated() && withoutSmartCast(t.expr()) instanceof TExpr.LocalGet g
                    && g.variable() == subject -> {
                if (Types.isMarkedNullable(t.target())) coverage.isNull = true;
                if (Types.lowerBound(t.target()) instanceof KType.ClassType c) coverage.classes.add(c.symbol());
            }
            default -> {
            }
        }
    }

    private boolean covers(KType type, Coverage coverage) {
        return switch (type) {
            case KType.Flexible f -> covers(Types.makeDefinitelyNotNull(f.lower()), coverage);
            case KType.DefinitelyNotNull d -> covers(d.inner().withNullable(false), coverage);
            case KType.ClassType c -> (!c.nullable() || coverage.isNull) && covers(c.symbol(), coverage);
            case KType.Intersection i -> i.components().stream().anyMatch(t -> covers(t, coverage));
            case KType.TypeParamType t -> (!t.nullable() || coverage.isNull)
                    && t.symbol().bounds().stream().anyMatch(b -> covers(Types.makeDefinitelyNotNull(b), coverage));
            default -> false;
        };
    }

    private boolean covers(ClassSymbol cls, Coverage coverage) {
        for (ClassSymbol tested : coverage.classes) {
            if (VisibilityChecker.isSubclassOf(cls, tested)) return true;
        }
        if (cls.id().fqName().equals("kotlin.Boolean")) return coverage.booleans.size() == 2;
        if (cls.isObject()) return coverage.values.contains(cls);
        if (cls.kind() == ClassKind.ENUM_CLASS) {
            return cls.members().properties().values().stream().flatMap(List::stream)
                    .filter(PropertySymbol::isEnumEntry).allMatch(coverage.values::contains);
        }
        if (cls.modality() == Modality.SEALED) {
            return owner.sealedInheritors(cls).stream().allMatch(sub -> covers(sub, coverage));
        }
        return false;
    }

    private static Expr unparenthesized(Expr e) {
        while (e instanceof Expr.Paren p) e = p.expr();
        return e;
    }

    // ==== Try ====

    private TExpr tryExpr(Expr.Try t, Context ctx, Mode mode) {
        List<TExpr> bodies = new ArrayList<>();
        DataFlowAnalyzer.State tryEnter = dfa.save();
        bodies.add(block(t.body().pos(), t.body().statements(), ctx, Mode.DEPENDENT));
        DataFlowAnalyzer.State tryExit = dfa.save();
        List<DataFlowAnalyzer.State> exits = new ArrayList<>(List.of(tryExit));
        List<LocalVariable> params = new ArrayList<>();
        for (Expr.Catch c : t.catches()) {
            KType type = resolveType(c.param().type(), ctx);
            LocalVariable param = new LocalVariable(c.param().name(), type, LocalVariable.Kind.CATCH_PARAMETER, false, null);
            LocalScope scope = new LocalScope();
            scope.add(param);
            params.add(param);
            // The exception may be thrown anywhere in the try block.
            dfa.join(List.of(tryEnter, tryExit));
            bodies.add(block(c.body().pos(), c.body().statements(), ctx.withLocalScope(scope), Mode.DEPENDENT));
            exits.add(dfa.save());
        }
        dfa.join(exits);
        TExpr[] finallyBlock = new TExpr[1];
        Candidate candidate = synthetic().tryCall(t.pos(), bodies, null);
        candidate.builder = w -> {
            List<TExpr.CatchClause> catches = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                catches.add(new TExpr.CatchClause(params.get(i),
                        w.writeArgument(bodies.get(i + 1), SyntheticCalls.parameterType(candidate, i + 1))));
            }
            return new TExpr.Try(t.pos(), w.callType(candidate, candidate.returnType()),
                    w.writeArgument(bodies.getFirst(), SyntheticCalls.parameterType(candidate, 0)), catches,
                    finallyBlock[0]);
        };
        TExpr result = completer.completeCall(candidate, mode);
        if (t.finallyBlock() != null) {
            finallyBlock[0] = block(t.finallyBlock().pos(), t.finallyBlock().statements(), ctx, Mode.INDEPENDENT);
            // The builder ran already if the call was completed.
            if (result instanceof TExpr.Try r) {
                result = new TExpr.Try(r.pos(), r.type(), r.body(), r.catches(), finallyBlock[0]);
            }
        }
        return result;
    }

    // ==== Operators ====

    private TExpr binary(Expr.Binary b, Context ctx, Mode mode) {
        return switch (b.op()) {
            case ELVIS -> elvis(b, ctx, mode);
            case AND, OR -> logical(b.pos(), b.op() == Expr.BinaryOp.AND, expr(b.left(), ctx, Mode.expecting(bool())),
                    () -> expr(b.right(), ctx, Mode.expecting(bool())));
            case EQ, NE, IDENTITY_EQ, IDENTITY_NE ->
                    equality(b.pos(), b.op(), expr(b.left(), ctx, Mode.INDEPENDENT), b.right(), ctx, mode);
            case MUL, DIV, REM, PLUS, MINUS, RANGE, RANGE_UNTIL -> operatorCall(b.pos(), operatorName(b.op()),
                    receiver(b.left(), ctx), List.of(b.right()), TExpr.CallOrigin.OPERATOR, ctx, mode);
            case IN -> operatorCall(b.pos(), "contains", receiver(b.right(), ctx), List.of(b.left()),
                    TExpr.CallOrigin.OPERATOR, ctx, mode);
            // a !in b is !(b.contains(a)).
            case NOT_IN -> operatorCall(b.pos(), "not", new CallInfo.ValueReceiver(operatorCall(b.pos(), "contains",
                    receiver(b.right(), ctx), List.of(b.left()), TExpr.CallOrigin.OPERATOR, ctx, Mode.RECEIVER)),
                    List.of(), TExpr.CallOrigin.OPERATOR, ctx, mode);
            case LT, GT, LE, GE -> new TExpr.Comparison(b.pos(), bool(), comparisonOp(b.op()),
                    operatorCall(b.pos(), "compareTo", receiver(b.left(), ctx), List.of(b.right()),
                            TExpr.CallOrigin.OPERATOR, ctx, Mode.INDEPENDENT));
            default -> throw error(b.pos(), "Operator " + b.op() + " is not supported yet");
        };
    }

    /** {@code a && b} or {@code a || b}: the right side is checked where the left did not decide. */
    private TExpr logical(int pos, boolean and, TExpr left, Supplier<TExpr> right) {
        DataFlowAnalyzer.State leftExit = dfa.exitLeftOfLogical(left, and);
        TExpr.Logical result = new TExpr.Logical(pos, bool(), and, left, right.get());
        dfa.exitLogical(result, leftExit);
        return result;
    }

    private static String operatorName(Expr.BinaryOp op) {
        return switch (op) {
            case MUL -> "times";
            case DIV -> "div";
            case REM -> "rem";
            case PLUS -> "plus";
            case MINUS -> "minus";
            case RANGE -> "rangeTo";
            case RANGE_UNTIL -> "rangeUntil";
            default -> throw new IllegalArgumentException(op.toString());
        };
    }

    private static TExpr.ComparisonOp comparisonOp(Expr.BinaryOp op) {
        return switch (op) {
            case LT -> TExpr.ComparisonOp.LT;
            case GT -> TExpr.ComparisonOp.GT;
            case LE -> TExpr.ComparisonOp.LE;
            default -> TExpr.ComparisonOp.GE;
        };
    }

    /** A call of an operator or infix function: {@code a + b} is {@code a.plus(b)}. */
    private TExpr operatorCall(int pos, String name, CallInfo.Receiver receiver, List<Expr> args,
                               TExpr.CallOrigin origin, Context ctx, Mode mode) {
        List<CallInfo.Argument> arguments = new ArrayList<>();
        String outerCallee = calleeNameForLambda;
        // Only infix calls label their lambdas.
        calleeNameForLambda = origin == TExpr.CallOrigin.INFIX ? name : null;
        try {
            for (Expr a : args) arguments.add(new CallInfo.Argument(expr(a, ctx, Mode.DEPENDENT), null, false, false));
        } finally {
            calleeNameForLambda = outerCallee;
        }
        return completer.completeCall(resolved(function(pos, name, receiver, arguments, List.of(), origin), ctx), mode);
    }

    private TExpr prefix(Expr.Prefix p, Context ctx, Mode mode) {
        String name = switch (p.op()) {
            case MINUS -> "unaryMinus";
            case PLUS -> "unaryPlus";
            case NOT -> "not";
            default -> throw new IllegalArgumentException(p.op().toString());
        };
        if ((p.op() == Expr.PrefixOp.MINUS || p.op() == Expr.PrefixOp.PLUS)
                && unparenthesized(p.expr()) instanceof Expr.IntLit l && isUnsuffixed(l)) {
            return integerLiteral(l, p.op() == Expr.PrefixOp.MINUS, mode);
        }
        return operatorCall(p.pos(), name, receiver(p.expr(), ctx), List.of(), TExpr.CallOrigin.OPERATOR, ctx, mode);
    }

    private static boolean isUnsuffixed(Expr.IntLit l) {
        return !l.text().matches(".*[lLuU]$");
    }

    /**
     * {@code ==} and the like, with the left side checked already. The right
     * side is checked as an argument, and completed through a synthetic call
     * if its type is not known yet.
     */
    private TExpr equality(int pos, Expr.BinaryOp op, TExpr left, Expr rightExpr, Context ctx, Mode mode) {
        TExpr.EqualityOp equalityOp = switch (op) {
            case EQ -> TExpr.EqualityOp.EQ;
            case NE -> TExpr.EqualityOp.NE;
            case IDENTITY_EQ -> TExpr.EqualityOp.IDENTITY_EQ;
            default -> TExpr.EqualityOp.IDENTITY_NE;
        };
        DataFlowAnalyzer.State lhsExit = dfa.save();
        TExpr right = expr(rightExpr, ctx, Mode.DEPENDENT);
        TExpr result;
        if (!Types.contains(right.type(), t -> t instanceof KType.TypeVariableType || t instanceof KType.IntegerLiteral)) {
            result = new TExpr.Equality(pos, bool(), equalityOp, left, right);
        } else {
            Candidate candidate = synthetic().equalityCall(pos, left, right, null);
            candidate.builder = w -> new TExpr.Equality(pos, bool(), equalityOp,
                    w.writeArgument(left, ts.nullableAny()), w.writeArgument(right, ts.nullableAny()));
            result = completer.completeCall(candidate, mode);
        }
        dfa.equality(result, equalityOp, left, right, lhsExit);
        return result;
    }

    private TExpr elvis(Expr.Binary b, Context ctx, Mode mode) {
        // The expected type of a when branch is not used: K1 did not.
        Mode data = mode instanceof Mode.WithExpectedType w && !w.forceFullCompletion() ? Mode.DEPENDENT : mode;
        KType expected = data instanceof Mode.WithExpectedType w ? w.type() : null;
        boolean last = data instanceof Mode.WithExpectedType w && w.lastStatementInBlock();
        Mode lhsMode = last && isUnitOrFlexibleUnit(expected) || expected == null ? Mode.DEPENDENT
                : Mode.expecting(expected.withNullable(true));
        TExpr lhs = expr(b.left(), ctx, lhsMode);
        DataFlowAnalyzer.State lhsNotNull = dfa.exitElvisLhs(lhs);
        Mode rhsMode = expected == null ? Mode.DEPENDENT : new Mode.WithExpectedType(expected, last, false, true);
        TExpr rhs = expr(b.right(), ctx, rhsMode);

        Candidate candidate = synthetic().elvisCall(b.pos(), lhs, rhs, null);
        candidate.builder = w -> new TExpr.Elvis(b.pos(), w.callType(candidate, candidate.returnType()),
                w.writeArgument(lhs, SyntheticCalls.parameterType(candidate, 0)),
                w.writeArgument(rhs, SyntheticCalls.parameterType(candidate, 1)));
        TExpr result = completer.completeCall(candidate, data);

        // As kotlinc, which adjusts the type of the call after completion.
        KType lhsType = result instanceof TExpr.Elvis e ? e.left().type() : lhs.type();
        KType rhsType = result instanceof TExpr.Elvis e ? e.right().type() : rhs.type();
        KType type = result.type();
        if (ts.isNothing(rhsType)) type = Types.makeDefinitelyNotNull(lhsType);
        if (!(type instanceof KType.TypeVariableType) && Types.canBeNull(type)) {
            if (!Types.canBeNull(rhsType)) {
                type = Types.makeDefinitelyNotNull(type);
            } else if (rhsType instanceof KType.Flexible f && !Types.canBeNull(f.lower())) {
                type = withNotNullableLowerBound(type);
            }
        }
        TExpr elvis = switch (result) {
            case TExpr.Elvis e -> new TExpr.Elvis(e.pos(), type, e.left(), e.right());
            case TExpr.Unresolved u -> new TExpr.Unresolved(u.pos(), type, u.state());
            default -> result;
        };
        dfa.exitElvis(elvis, lhs, rhs, lhsNotNull, ts.isNothing(rhs.type()));
        return elvis;
    }

    private static KType withNotNullableLowerBound(KType type) {
        return switch (type) {
            case KType.Flexible f -> Types.canBeNull(f.lower())
                    ? Types.flexible(Types.makeDefinitelyNotNull(f.lower()), f.upper()) : f;
            case KType.Intersection i -> new KType.Intersection(
                    i.components().stream().map(BodyChecker::withNotNullableLowerBound).toList());
            default -> Types.flexible(Types.makeDefinitelyNotNull(type), type);
        };
    }

    private TExpr checkNotNull(Expr.Postfix p, Context ctx, Mode mode) {
        TExpr arg = expr(p.expr(), ctx, Mode.DEPENDENT);
        Candidate candidate = synthetic().checkNotNullCall(p.pos(), arg, null);
        candidate.builder = w -> new TExpr.NotNull(p.pos(), w.callType(candidate, candidate.returnType()),
                w.writeArgument(arg, SyntheticCalls.parameterType(candidate, 0)));
        TExpr result = completer.completeCall(candidate, mode);
        dfa.checkNotNull(arg);
        return result;
    }

    private TExpr cast(Expr.As a, Context ctx) {
        // TODO: the expected type from the cast for calls, as kotlinc's fromCast mode
        TExpr operand = expr(a.expr(), ctx, Mode.INDEPENDENT);
        KType target = typeOperand(a.type(), operand.type(), ctx);
        TExpr.Cast cast = new TExpr.Cast(a.pos(), a.safe() ? target.withNullable(true) : target, operand, target,
                a.safe());
        dfa.cast(cast);
        return cast;
    }

    private TExpr typeCheck(int pos, TExpr operand, KType target, boolean negated) {
        TExpr.TypeCheck check = new TExpr.TypeCheck(pos, bool(), operand, target, negated);
        dfa.typeCheck(check);
        return check;
    }

    // ==== Jumps and loops ====

    private TExpr returnExpr(Expr.Return r, Context ctx) {
        Context.FunctionFrame target = ctx.function();
        while (target != null && (r.label() == null ? target.isLambda : !r.label().equals(target.label))) {
            target = target.outer;
        }
        if (target == null) {
            throw error(r.pos(), r.label() == null ? "'return' is not allowed here" : "Unresolved label " + r.label());
        }
        KType returnType = target.function.returnType();
        // A lambda whose return type is not known yet returns values completed with its call.
        boolean isLambda = target.function.symbol() == null;
        TExpr value = null;
        if (r.value() != null) {
            value = expr(r.value(), ctx, returnType != null ? Mode.expecting(returnType)
                    : isLambda ? Mode.DEPENDENT : Mode.INDEPENDENT);
        }
        if (isLambda) target.returns.add(value);
        dfa.returnFrom(target.function, isLambda);
        return new TExpr.Return(r.pos(), ts.nothing(), target.function, value);
    }

    private TExpr.Loop loop(int pos, String label, Context ctx) {
        for (Context.LoopFrame f = ctx.loop(); f != null; f = f.outer()) {
            if (label == null || label.equals(f.loop().label())) return f.loop();
        }
        throw error(pos, label == null ? "'break' and 'continue' are only allowed in loops" : "Unresolved label " + label);
    }

    private TExpr labeled(Expr.Labeled l, Context ctx, Mode mode) {
        Expr inner = l.expr();
        while (inner instanceof Expr.Annotated a) inner = a.expr();
        return switch (inner) {
            case Expr.While w -> whileLoop(w, l.label(), ctx);
            case Expr.DoWhile w -> doWhileLoop(w, l.label(), ctx);
            case Expr.For f -> forLoop(f, l.label(), ctx);
            case Expr.Lambda lambda -> lambda(lambda, l.label(), ctx, mode);
            case Expr.AnonymousFunction f -> lambda(f, l.label(), ctx, mode);
            default -> expr(inner, ctx, mode);
        };
    }

    private TExpr whileLoop(Expr.While w, String label, Context ctx) {
        TExpr.Loop loop = new TExpr.Loop(label);
        DataFlowAnalyzer.LoopState l = dfa.enterWhileLoop(loop, AssignedVariables.in(ctx, w.condition(), w.body()));
        TExpr condition = expr(w.condition(), ctx, Mode.expecting(bool()));
        dfa.exitWhileLoopCondition(l, condition);
        TExpr body = branch(w.pos(), w.body(), ctx.withLoop(loop), Mode.INDEPENDENT);
        dfa.exitWhileLoop(l, condition);
        return new TExpr.While(w.pos(), unit(), loop, condition, body, false);
    }

    /**
     * {@code for (x in r) body}, desugared as kotlinc does: a temporary for
     * {@code r.iterator()}, and a while loop on its {@code hasNext()} whose
     * body begins with {@code val x = it.next()}.
     */
    private TExpr forLoop(Expr.For f, String label, Context ctx) {
        TExpr.Loop loop = new TExpr.Loop(label);
        TExpr iterator = desugaredCall(f.pos(), "iterator", receiver(f.iterable(), ctx), ctx, Mode.INDEPENDENT);
        LocalVariable it = temporary("<iterator>", approximateLocalType(iterator.type()));
        dfa.localVariableInitialized(it, iterator, false, false);
        DataFlowAnalyzer.LoopState l = dfa.enterWhileLoop(loop, AssignedVariables.in(ctx, f.body()));
        TExpr hasNext = desugaredCall(f.pos(), "hasNext", localValue(f.pos(), it), ctx, Mode.expecting(bool()));
        dfa.exitWhileLoopCondition(l, hasNext);

        Context inner = ctx.withLocalScope(new LocalScope()).withLoop(loop);
        Expr.Binding b = f.variable();
        KType declared = b.type() == null ? null : resolveType(b.type(), inner);
        TExpr next = desugaredCall(b.pos(), "next", localValue(b.pos(), it), inner,
                declared != null ? Mode.expecting(declared) : Mode.INDEPENDENT);
        List<TExpr> statements = new ArrayList<>();
        KType type = declared != null ? declared : approximateLocalType(next.type());
        if (b.destructuring() != null) {
            LocalVariable v = temporary("<destruct>", type);
            statements.add(new TExpr.LocalVarDecl(b.pos(), unit(), v, next));
            dfa.localVariableInitialized(v, next, declared != null, false);
            destructure(b.destructuring(), v, inner, statements);
        } else {
            LocalVariable v = new LocalVariable(b.name(), type, LocalVariable.Kind.LOCAL, false, null);
            if (!b.name().equals("_")) inner.locals().getLast().add(v);
            statements.add(new TExpr.LocalVarDecl(b.pos(), unit(), v, next));
            dfa.localVariableInitialized(v, next, declared != null, false);
        }
        statements.add(branch(f.pos(), f.body(), inner, Mode.INDEPENDENT));
        dfa.exitWhileLoop(l, hasNext);
        TExpr body = new TExpr.Block(f.pos(), unit(), statements);
        return new TExpr.Block(f.pos(), unit(), List.of(
                new TExpr.LocalVarDecl(f.pos(), unit(), it, iterator),
                new TExpr.While(f.pos(), unit(), loop, hasNext, body, false)));
    }

    /** The body and condition of a do-while share a scope: the condition sees the body's variables. */
    private TExpr doWhileLoop(Expr.DoWhile w, String label, Context ctx) {
        TExpr.Loop loop = new TExpr.Loop(label);
        Context inner = ctx.withLocalScope(new LocalScope()).withLoop(loop);
        List<Stmt> statements = w.body() == null ? List.of()
                : w.body() instanceof Expr.Block b ? b.statements() : List.of(w.body());
        DataFlowAnalyzer.LoopState l = dfa.enterDoWhileLoop(loop, AssignedVariables.in(ctx, w.body(), w.condition()));
        TExpr body = blockInScope(w.pos(), statements, inner, Mode.INDEPENDENT);
        dfa.enterDoWhileLoopCondition(l);
        TExpr condition = expr(w.condition(), inner, Mode.expecting(bool()));
        dfa.exitDoWhileLoop(l, condition);
        return new TExpr.While(w.pos(), unit(), loop, condition, body, true);
    }

    // ==== Lambdas ====

    /**
     * A lambda or anonymous function. As an argument, it is analyzed with
     * its call; otherwise, as FIR's ResolveTopLevelLambdasAsSyntheticCallArgument,
     * as the argument of a call of {@code fun accept(p: expected)}.
     *
     * @param label the explicit label, if any
     */
    private TExpr lambda(Expr e, String label, Context ctx, Mode mode) {
        LambdaExpression lambda = lambdaExpression(e, label != null ? label : calleeNameForLambda, ctx);
        lambda.creation = dfa.save();
        TExpr.Unresolved argument = new TExpr.Unresolved(e.pos(), null, lambda);
        if (mode instanceof Mode.ContextDependent) return argument;
        KType expected = mode instanceof Mode.WithExpectedType w ? adaptExpectedTypeForLambda(w.type(), lambda) : null;
        KType parameterType = expected != null && !isUnitOrFlexibleUnit(expected) ? expected : ts.any();
        Candidate candidate = synthetic().acceptCall(e.pos(), parameterType, argument,
                w -> w.writeArgument(argument, parameterType));
        return completer.completeCall(candidate, Mode.INDEPENDENT);
    }

    /** {@code val f: A.(B) -> C = { a, b -> ... }}: the receiver is a parameter, so expected as {@code (A, B) -> C}. */
    private static KType adaptExpectedTypeForLambda(KType expected, LambdaExpression lambda) {
        if (!lambda.hasExplicitParameterList || !FunctionTypes.isFunctionType(expected)) return expected;
        if (!(Types.lowerBound(expected) instanceof KType.ClassType c)) return expected;
        if (!c.extensionFunction() && c.contextParams() == 0) return expected;
        if (lambda.declaredParamTypes.size() != c.args().size() - 1) return expected;
        return new KType.ClassType(c.symbol(), c.args(), c.nullable(), false, 0);
    }

    /** The lambda with the types it declares resolved. */
    private LambdaExpression lambdaExpression(Expr e, String label, Context ctx) {
        List<KType> paramTypes = new ArrayList<>();
        if (e instanceof Expr.Lambda l) {
            for (Expr.Binding b : l.params()) paramTypes.add(b.type() == null ? null : resolveType(b.type(), ctx));
            return new LambdaExpression(e, label, ctx, null, Collections.unmodifiableList(paramTypes), null, true,
                    l.hasArrow(), false);
        }
        Decl.Function f = ((Expr.AnonymousFunction) e).function();
        if (!f.modifiers().contextParams().isEmpty()) {
            throw error(e.pos(), "Anonymous functions with context parameters are not supported yet");
        }
        for (Decl.Param p : f.params()) paramTypes.add(p.type() == null ? null : resolveType(p.type(), ctx));
        KType receiver = f.receiver() == null ? null : resolveType(f.receiver(), ctx);
        KType returnType = f.returnType() != null ? resolveType(f.returnType(), ctx)
                : f.body() == null || f.body() instanceof Expr.Block ? unit() : null;
        return new LambdaExpression(e, label, ctx, receiver, Collections.unmodifiableList(paramTypes), returnType, false,
                true, f.modifiers().has(Modifier.SUSPEND));
    }

    /** Analyzes a lambda or callable reference during the completion of its call. */
    void analyzePostponed(ConstraintSystem system, PostponedArgument argument, Candidate candidate) {
        switch (argument) {
            case LambdaAtom l -> analyzeLambda(system, l, candidate);
            case LambdaWithTypeVariableAtom l ->
                    analyzeLambda(system, arguments().transformToResolvedLambda(system, l, null), candidate);
            case CallableReferenceAtom r -> processCallableReference(r, candidate);
            default -> throw new IllegalStateException("Unexpected postponed argument " + argument);
        }
    }

    /**
     * Checks the body of a lambda whose parameter types are known, as FIR's
     * PostponedArgumentsAnalyzer.analyzeLambda: the values it returns are
     * arguments for its return type. With the return type not known yet,
     * they are checked as arguments of the call, to be completed with it.
     */
    private void analyzeLambda(ConstraintSystem system, LambdaAtom atom, Candidate candidate) {
        applyLambdaResults(system, atom, candidate, analyzeLambdaBody(system, atom));
    }

    /**
     * What checking a lambda's body gives.
     *
     * @param returnValues the values it returns, explicitly or as its last expression
     * @param last         the last expression, if returned
     */
    record LambdaResults(TExpr.Lambda lambda, List<TExpr> returnValues, TExpr last, boolean explicitEmptyReturn) {
    }

    /** Checks the body of a lambda, with the types the system gives it now. */
    LambdaResults analyzeLambdaBody(ConstraintSystem system, LambdaAtom atom) {
        LambdaExpression lambda = atom.expression;
        Substitution current = system.buildCurrentSubstitutor();
        KType receiver = atom.receiverType == null ? null : current.apply(atom.receiverType);
        List<KType> parameters = new ArrayList<>();
        if (atom.coerceFirstParameterToExtensionReceiver) parameters.add(receiver);
        for (KType t : atom.parameterTypes) parameters.add(current.apply(t));
        if (atom.coerceFirstParameterToExtensionReceiver) receiver = null;
        KType expectedReturnType;
        if (system.containsOnlyFixedVariables(atom.returnType)) {
            expectedReturnType = current.apply(atom.returnType);
        } else if (willEventuallyBecomeUnit(system, atom.returnType)) {
            expectedReturnType = unit();
        } else {
            expectedReturnType = null;
        }

        // The parameters.
        Context outer = lambda.context;
        LocalScope scope = new LocalScope();
        List<LocalVariable> params = new ArrayList<>();
        List<Expr.Binding> bindings = lambda.expr instanceof Expr.Lambda l ? l.params() : List.of();
        List<Decl.Param> declared = lambda.expr instanceof Expr.AnonymousFunction f ? f.function().params() : List.of();
        int count = lambda.isLambda ? bindings.size() : declared.size();
        if (lambda.isLambda && count == 0 && atom.parameterTypes.size() == 1) {
            LocalVariable it = new LocalVariable("it", lambdaInputType(lambda, parameters.getFirst(), false),
                    LocalVariable.Kind.PARAMETER, false, null);
            params.add(it);
            scope.add(it);
        }
        for (int i = 0; i < count; i++) {
            int pos = lambda.isLambda ? bindings.get(i).pos() : declared.get(i).pos();
            if (i >= parameters.size()) throw error(pos, "The lambda has more parameters than expected");
            String name = lambda.isLambda && bindings.get(i).destructuring() != null ? "<destruct>"
                    : lambda.isLambda ? bindings.get(i).name() : declared.get(i).name();
            LocalVariable v = new LocalVariable(name, lambdaInputType(lambda, parameters.get(i), false),
                    LocalVariable.Kind.PARAMETER, false, null);
            params.add(v);
            if (!name.equals("_") && !name.equals("<destruct>")) scope.add(v);
        }
        if (receiver != null) receiver = lambdaInputType(lambda, receiver, true);

        CheckedFunction function = new CheckedFunction(null, lambda.label, receiver, List.of(), params, expectedReturnType);
        Context ctx = outer.withLocalScope(scope);
        if (receiver != null) ctx = ctx.withElement(new Context.ImplicitReceiver(receiver, function, lambda.label));
        Context.FunctionFrame frame = new Context.FunctionFrame(function, lambda.label, lambda.isLambda, outer.function());
        ctx = ctx.withFunction(frame);

        // The body; a lambda returns the value of its last statement.
        String outerCallee = calleeNameForLambda;
        calleeNameForLambda = null;
        TExpr body;
        TExpr last = null;
        Contract.InvocationKind invocationKind = invocationKind(atom);
        DataFlowAnalyzer.LambdaFrame dataFlow = dfa.enterLambda(lambda.creation, invocationKind,
                AssignedVariables.in(outer, lambda.expr));
        try {
            if (lambda.expr instanceof Expr.Lambda l) {
                List<TExpr> prologue = new ArrayList<>();
                for (int i = 0; i < bindings.size(); i++) {
                    Decl.Destructuring d = bindings.get(i).destructuring();
                    if (d != null) destructure(d, params.get(i), ctx, prologue);
                }
                TExpr block = block(l.pos(), l.body(), ctx,
                        expectedReturnType != null ? Mode.expecting(expectedReturnType) : Mode.DEPENDENT);
                List<TExpr> statements = new ArrayList<>(prologue);
                statements.addAll(((TExpr.Block) block).statements());
                body = new TExpr.Block(block.pos(), block.type(), statements);
                if (!l.body().isEmpty()) {
                    TExpr s = statements.getLast();
                    if (TExprs.isStatement(s)) {
                        // Not the last expression, which may be coerced to Unit, but a Unit value.
                        frame.returns.add(unitValue(s.pos()));
                    } else if (!(s instanceof TExpr.Return r && r.target() == function)) {
                        last = s;
                        frame.returns.add(s);
                    }
                }
            } else {
                Decl.Function f = ((Expr.AnonymousFunction) lambda.expr).function();
                if (f.body() == null) throw error(f.pos(), "An anonymous function needs a body");
                if (f.body() instanceof Expr.Block b) {
                    body = block(b.pos(), b.statements(), ctx, Mode.INDEPENDENT);
                } else {
                    TExpr value = expr(f.body(), ctx,
                            expectedReturnType != null ? Mode.expecting(expectedReturnType) : Mode.DEPENDENT);
                    frame.returns.add(value);
                    body = new TExpr.Return(f.body().pos(), ts.nothing(), function, value);
                }
            }
        } finally {
            calleeNameForLambda = outerCallee;
            dfa.exitLambda(dataFlow, function, lambda.creation, invocationKind);
        }
        function.setBody(body);

        boolean explicitEmptyReturn = frame.returns.contains(null);
        List<TExpr> returned = new ArrayList<>();
        for (TExpr value : frame.returns) returned.add(value != null ? value : unitValue(lambda.pos()));
        FunctionTypes.Kind kind = atom.kind != null ? atom.kind.nonReflect() : FunctionTypes.Kind.FUNCTION;
        KType type = FunctionTypes.create(ts.table(), kind, List.of(), receiver,
                params.stream().map(LocalVariable::type).toList(), current.apply(atom.returnType));
        return new LambdaResults(new TExpr.Lambda(lambda.pos(), type, function, !lambda.isLambda), returned, last,
                explicitEmptyReturn);
    }

    /** How the call a lambda is an argument of calls it, by its contract; null if not in place. */
    private Contract.InvocationKind invocationKind(LambdaAtom atom) {
        Candidate call = atom.containingCandidate;
        if (call == null || call.argumentMapping == null || !(call.symbol instanceof FunctionSymbol f)) return null;
        for (int i = 0; i < call.arguments.size(); i++) {
            if (call.arguments.get(i) instanceof TExpr.Unresolved u && u.state() == atom.expression) {
                int param = call.argumentMapping[i];
                return param < 0 ? null : owner.contract(f).callsInPlace().get(param);
            }
        }
        return null;
    }

    /**
     * Adds the constraints the values a lambda returns give its return
     * type, as FIR's applyResultsOfAnalyzedLambdaToCandidateSystem.
     */
    void applyLambdaResults(ConstraintSystem system, LambdaAtom atom, Candidate candidate, LambdaResults results) {
        Substitution current = system.buildCurrentSubstitutor();
        CheckedFunction function = results.lambda().function();
        KType returnType = function.returnType() != null ? function.returnType() : current.apply(atom.returnType);
        boolean lastCoercedToUnit = isUnitOrFlexibleUnit(returnType) || willEventuallyBecomeUnit(system, returnType)
                || atom.expression.isLambda && results.explicitEmptyReturn();
        ConstraintPosition position = ConstraintPosition.of(ConstraintPosition.Kind.LAMBDA_ARGUMENT);
        boolean hasExpression = false;
        for (TExpr r : results.returnValues()) {
            boolean haveSubsystem = ArgumentChecker.addSubsystemFromArgument(system, r);
            if (r == results.last() && lastCoercedToUnit) {
                // A call's type is not forced to Unit, as put in buildMap { put(k, v) }.
                if (haveSubsystem) ArgumentChecker.addSubtypeConstraintIfCompatible(system, r.type(), returnType, position);
                continue;
            }
            hasExpression = true;
            // Nested lambdas are analyzed even with a contradiction.
            if (!system.hasContradiction() || r instanceof TExpr.Unresolved u && u.state() instanceof LambdaExpression) {
                arguments().resolveArgument(candidate, atom.containingCandidate, r, current.apply(atom.returnType),
                        false, false);
            }
        }
        if (!hasExpression) addLambdaReturnTypeUnitConstraint(system, atom, candidate, current);
        atom.returnValues = results.returnValues();
        atom.explicitEmptyReturn = results.explicitEmptyReturn();
        atom.setResult(results.lambda());
    }

    private void addLambdaReturnTypeUnitConstraint(ConstraintSystem system, LambdaAtom atom, Candidate candidate,
                                                   Substitution current) {
        if (system.hasContradiction()) return;
        KType returnType = current.apply(atom.returnType);
        ConstraintPosition position = ConstraintPosition.of(ConstraintPosition.Kind.LAMBDA_ARGUMENT);
        if (ArgumentChecker.addSubtypeConstraintIfCompatible(system, unit(), returnType, position)) return;
        if (atom.expectedType != null) {
            candidate.addDiagnostic(Applicability.INAPPLICABLE, "The lambda returns Unit, not " + returnType);
        } else {
            system.addSubtypeConstraint(unit(), returnType, position);
        }
    }

    private static boolean willEventuallyBecomeUnit(ConstraintSystem system, KType type) {
        return !Types.isMarkedNullable(type) && system.hasUpperOrEqualUnitConstraint(type);
    }

    /**
     * The type of a parameter or receiver of a lambda, approximated; it
     * must not be a variable, as one that could not be inferred before.
     */
    private KType lambdaInputType(LambdaExpression lambda, KType type, boolean isReceiver) {
        if (type instanceof KType.TypeVariableType) {
            throw error(lambda.pos(), isReceiver ? "Cannot infer the receiver type of the lambda"
                    : "Cannot infer a parameter type of the lambda");
        }
        return TypeApproximator.approximateToSuperTypeOrSelf(ts, type,
                TypeApproximator.Config.INTERMEDIATE_APPROXIMATION_AFTER_COMPLETION);
    }

    private TExpr unitValue(int pos) {
        return new TExpr.ObjectValue(pos, unit(), ts.table().requireClass("kotlin.Unit"));
    }

    // ==== Types ====

    KType resolveType(TypeRef ref, Context ctx) {
        return owner.resolver().types().resolve(ref, ctx.typeScope(), ctx.file());
    }

    /** The type after {@code is} or {@code as}, a bare type with the arguments the operand's type implies. */
    private KType typeOperand(TypeRef ref, KType operandType, Context ctx) {
        KType type = owner.resolver().types().resolveAllowingBare(ref, ctx.typeScope(), ctx.file());
        if (!BareTypes.isBare(type)) return type;
        KType.ClassType bare = (KType.ClassType) Types.lowerBound(type);
        KType.ClassType result = BareTypes.representative(ts, owner.resolver().types(), bare, operandType);
        if (result == null) throw error(ref.pos(), "No type arguments for " + bare.symbol());
        return result;
    }

    private KType.ClassType type(String fqName) {
        return ts.classType(fqName);
    }

    private KType bool() {
        return type("kotlin.Boolean");
    }

    KType unit() {
        return type("kotlin.Unit");
    }

    static boolean isUnitOrFlexibleUnit(KType t) {
        return t != null && Types.lowerBound(t) instanceof KType.ClassType c && !c.nullable()
                && c.symbol().id().fqName().equals("kotlin.Unit");
    }
}
