package kotlinbootstrap.infer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import kotlinbootstrap.types.CapturedTypeConstructor;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;

/**
 * A system of constraints on type variables, as kotlinc's
 * NewConstraintSystemImpl with its storage. Constraints are simplified to
 * bounds on single variables and incorporated with the others as they are
 * added (see {@link ConstraintInjector}); completion (see
 * {@link ConstraintSystemCompleter}) then fixes the variables one by one.
 */
public final class ConstraintSystem {
    /** A constraint for a variable, not yet added: from a branch of a fork point. */
    record VariableConstraint(TypeVariable variable, Constraint constraint) {
    }

    /**
     * Alternative sets of constraints, of which one must hold: e.g. for
     * {@code A<Int> & A<T> <: A<X>}, either X = Int or X = T.
     */
    record ForkPoint(IncorporationPosition position, List<List<VariableConstraint>> branches) {
    }

    final TypeSystem ts;
    final ConstraintInjector injector;
    final ResultTypeResolver resultTypeResolver;

    private final Set<TypeVariable> allTypeVariables = new LinkedHashSet<>();
    private final Map<TypeVariable, VariableWithConstraints> notFixed = new LinkedHashMap<>();
    private final Map<TypeVariable, KType> fixed = new LinkedHashMap<>();
    /** For each variable, the variables with constraints that mention it. */
    private final Map<TypeVariable, Set<TypeVariable>> typeVariableDependencies = new LinkedHashMap<>();
    private final List<InitialConstraint> initialConstraints = new ArrayList<>();
    private final List<ConstraintError> errors = new ArrayList<>();
    private final List<ForkPoint> forkPoints = new ArrayList<>();
    /** Function types built for postponed arguments, by their expected type's variable. */
    private final Map<TypeVariable, KType> builtFunctionTypesByExpectedType = new HashMap<>();
    /** Function types built for postponed arguments, by a variable of the call's type and the path to them in it. */
    private final Map<TypeVariable, Map<List<PostponedArgumentInputTypes.PathStep>, KType>> builtFunctionTypesByTopLevelVariable =
            new HashMap<>();

    /** Whether the system started as a copy of a PCLA lambda's shared system, as kotlinc's usesOuterCs. */
    private boolean usesOuterSystem;
    /** How many of the first variables are those of the outer system. */
    private int outerSystemVariablesPrefixSize;
    /** Variables taken as proper types for now, as kotlinc's typeVariablesThatAreCountedAsProperTypes; nullable. */
    private Set<TypeVariable> typeVariablesCountedAsProperTypes;

    private boolean inTransaction;
    private final List<TypeVariable> typeVariablesTransaction = new ArrayList<>();
    private Boolean hasContradictionInForkPointsCache;
    private boolean atCompletionState;

    public ConstraintSystem(TypeSystem ts) {
        this.ts = ts;
        this.injector = new ConstraintInjector(ts);
        this.resultTypeResolver = new ResultTypeResolver(ts);
    }

    public TypeSystem ts() {
        return ts;
    }

    // ==== State ====

    public Set<TypeVariable> allTypeVariables() {
        return allTypeVariables;
    }

    public Map<TypeVariable, VariableWithConstraints> notFixedTypeVariables() {
        return notFixed;
    }

    public Map<TypeVariable, KType> fixedTypeVariables() {
        return fixed;
    }

    public List<ConstraintError> errors() {
        return errors;
    }

    public List<InitialConstraint> initialConstraints() {
        return initialConstraints;
    }

    List<ForkPoint> forkPoints() {
        return forkPoints;
    }

    boolean atCompletionState() {
        return atCompletionState;
    }

    /** From now on, fork points are resolved as soon as they appear. */
    public void startCompletion() {
        atCompletionState = true;
    }

    /** Whether the system uses the shared system of a lambda analyzed with PCLA. */
    public boolean usesOuterSystem() {
        return usesOuterSystem;
    }

    /**
     * The variables of the outer system, as kotlinc's outerTypeVariables:
     * those of the calls a PCLA lambda's calls share; null if none.
     */
    public Set<TypeVariable> outerTypeVariables() {
        if (outerSystemVariablesPrefixSize == 0) return null;
        Set<TypeVariable> result = new LinkedHashSet<>();
        for (TypeVariable v : allTypeVariables) {
            if (result.size() == outerSystemVariablesPrefixSize) break;
            result.add(v);
        }
        return result;
    }

    // ==== Building ====

    public void registerVariable(TypeVariable variable) {
        if (inTransaction && !allTypeVariables.contains(variable)) typeVariablesTransaction.add(variable);
        if (!allTypeVariables.add(variable)) throw new IllegalStateException("Type variable already registered: " + variable);
        notFixed.put(variable, new VariableWithConstraints(variable));
    }

    public void addSubtypeConstraint(KType lower, KType upper, ConstraintPosition position) {
        injector.addInitialSubtypeConstraint(this, lower, upper, position);
    }

    public void addEqualityConstraint(KType a, KType b, ConstraintPosition position) {
        injector.addInitialEqualityConstraint(this, a, b, position);
    }

    void addInitialConstraint(InitialConstraint c) {
        initialConstraints.add(c);
    }

    void addError(ConstraintError error) {
        errors.add(error);
    }

    void recordTypeVariableReferenceInConstraint(TypeVariable owner, TypeVariable referenced) {
        typeVariableDependencies.computeIfAbsent(referenced, k -> new LinkedHashSet<>()).add(owner);
    }

    /** The not fixed variables with constraints that mention the variable. */
    List<VariableWithConstraints> variablesWithConstraintsContaining(TypeVariable variable) {
        Set<TypeVariable> owners = typeVariableDependencies.get(variable);
        if (owners == null) return List.of();
        List<VariableWithConstraints> result = new ArrayList<>();
        for (TypeVariable owner : owners) {
            VariableWithConstraints v = notFixed.get(owner);
            if (v != null) result.add(v);
        }
        return result;
    }

    void addForkPoint(ForkPoint forkPoint) {
        forkPoints.add(forkPoint);
    }

    void onNewConstraintOrForkPoint() {
        hasContradictionInForkPointsCache = null;
    }

    /**
     * Adds the variables, constraints and errors of another system, e.g. of
     * an argument's call into the system of the call it is an argument of.
     */
    public void addOtherSystem(ConstraintSystem other) {
        for (TypeVariable v : other.allTypeVariables) {
            if (inTransaction && !allTypeVariables.contains(v)) typeVariablesTransaction.add(v);
        }
        allTypeVariables.addAll(other.allTypeVariables);
        other.notFixed.forEach((v, c) -> notFixed.put(v, new VariableWithConstraints(c)));
        other.typeVariableDependencies.forEach((v, owners) -> typeVariableDependencies.put(v, new LinkedHashSet<>(owners)));
        initialConstraints.addAll(other.initialConstraints);
        forkPoints.addAll(other.forkPoints);
        errors.addAll(other.errors);
        fixed.putAll(other.fixed);
        builtFunctionTypesByExpectedType.putAll(other.builtFunctionTypesByExpectedType);
        builtFunctionTypesByTopLevelVariable.putAll(other.builtFunctionTypesByTopLevelVariable);
        hasContradictionInForkPointsCache = null;
    }

    /**
     * Starts this empty system as a copy of an outer one, all of whose
     * variables are outer variables, as kotlinc's addOuterSystem.
     */
    public void addOuterSystem(ConstraintSystem outer) {
        usesOuterSystem = true;
        outerSystemVariablesPrefixSize = outer.allTypeVariables.size();
        addOtherSystem(outer);
    }

    /** Starts this empty system as a copy of another, with its outer variables, as kotlinc's setBaseSystem. */
    public void setBaseSystem(ConstraintSystem base) {
        usesOuterSystem = base.usesOuterSystem;
        outerSystemVariablesPrefixSize = base.outerSystemVariablesPrefixSize;
        addOtherSystem(base);
    }

    /**
     * Makes this system, of a call that was to be completed on its own,
     * start with the shared system of a PCLA lambda after all.
     */
    public void integrateIntoOuterSystem(ConstraintSystem shared) {
        ConstraintSystem own = new ConstraintSystem(ts);
        own.addOtherSystem(this);
        replaceContentWith(shared);
        usesOuterSystem = shared.usesOuterSystem;
        outerSystemVariablesPrefixSize = shared.outerSystemVariablesPrefixSize;
        addOtherSystem(own);
    }

    /**
     * Makes this system a copy of another, as kotlinc's replaceContentWith:
     * of one that started as a copy of this and has had more added.
     */
    public void replaceContentWith(ConstraintSystem other) {
        allTypeVariables.clear();
        notFixed.clear();
        fixed.clear();
        typeVariableDependencies.clear();
        initialConstraints.clear();
        errors.clear();
        forkPoints.clear();
        builtFunctionTypesByExpectedType.clear();
        builtFunctionTypesByTopLevelVariable.clear();
        addOtherSystem(other);
    }

    KType builtFunctionType(TypeVariable expectedType) {
        return builtFunctionTypesByExpectedType.get(expectedType);
    }

    void putBuiltFunctionType(TypeVariable expectedType, KType functionType) {
        builtFunctionTypesByExpectedType.put(expectedType, functionType);
    }

    KType builtFunctionType(TypeVariable topLevelVariable, List<PostponedArgumentInputTypes.PathStep> path) {
        return builtFunctionTypesByTopLevelVariable.getOrDefault(topLevelVariable, Map.of()).get(path);
    }

    void putBuiltFunctionType(TypeVariable topLevelVariable, List<PostponedArgumentInputTypes.PathStep> path,
                              KType functionType) {
        builtFunctionTypesByTopLevelVariable.computeIfAbsent(topLevelVariable, k -> new HashMap<>()).put(path, functionType);
    }

    // ==== Transactions ====

    /**
     * Runs the operations and keeps their effects only if they return true,
     * as kotlinc's runTransaction. Variables fixed meanwhile stay fixed.
     */
    public boolean runTransaction(BooleanSupplier operations) {
        boolean beforeInTransaction = inTransaction;
        int beforeInitialConstraints = initialConstraints.size();
        int beforeErrors = errors.size();
        int beforeTypeVariablesTransaction = typeVariablesTransaction.size();
        Map<TypeVariable, Integer> beforeConstraintCounts = new HashMap<>();
        notFixed.forEach((v, c) -> beforeConstraintCounts.put(v, c.rawConstraintsCount()));
        int beforeForkPoints = forkPoints.size();
        Boolean beforeCache = hasContradictionInForkPointsCache;
        inTransaction = true;

        boolean keep = operations.getAsBoolean();
        List<TypeVariable> added = typeVariablesTransaction.subList(beforeTypeVariablesTransaction, typeVariablesTransaction.size());
        if (!keep) {
            for (TypeVariable v : added) {
                allTypeVariables.remove(v);
                notFixed.remove(v);
            }
            errors.subList(beforeErrors, errors.size()).clear();
            forkPoints.subList(beforeForkPoints, forkPoints.size()).clear();
            notFixed.forEach((v, c) -> {
                Integer since = beforeConstraintCounts.get(v);
                if (since != null) c.removeLastConstraints(since);
            });
            initialConstraints.subList(beforeInitialConstraints, initialConstraints.size()).clear();
            hasContradictionInForkPointsCache = beforeCache;
        }
        added.clear();
        inTransaction = beforeInTransaction;
        return keep;
    }

    /** Whether the constraint {@code lower <: upper} could be added without a contradiction; it is not added. */
    public boolean isSubtypeConstraintCompatible(KType lower, KType upper) {
        return isConstraintCompatible(lower, upper, ConstraintKind.LOWER);
    }

    public boolean isEqualityConstraintCompatible(KType a, KType b) {
        return isConstraintCompatible(a, b, ConstraintKind.EQUALITY);
    }

    private boolean isConstraintCompatible(KType lower, KType upper, ConstraintKind kind) {
        boolean[] compatible = {false};
        runTransaction(() -> {
            if (!hasContradiction()) {
                ConstraintPosition position = ConstraintPosition.of(ConstraintPosition.Kind.OTHER);
                if (kind == ConstraintKind.EQUALITY) {
                    addEqualityConstraint(lower, upper, position);
                } else {
                    addSubtypeConstraint(lower, upper, position);
                }
            }
            compatible[0] = !hasContradiction();
            return false;
        });
        return compatible[0];
    }

    // ==== Contradictions and fork points ====

    public boolean hasContradiction() {
        return !errors.isEmpty() || areThereContradictionsInForks();
    }

    /** Whether some fork point has no branch without contradictions; leaves the system as it was. */
    boolean areThereContradictionsInForks() {
        if (forkPoints.isEmpty()) return false;
        if (hasContradictionInForkPointsCache != null) return hasContradictionInForkPointsCache;
        List<ForkPoint> all = List.copyOf(forkPoints);
        forkPoints.clear();
        boolean[] anyUnsuccessful = {false};
        runTransaction(() -> {
            anyUnsuccessful[0] = all.stream().anyMatch(f -> !applyTheBestBranch(f));
            return false;
        });
        forkPoints.addAll(all);
        hasContradictionInForkPointsCache = anyUnsuccessful[0];
        return anyUnsuccessful[0];
    }

    /** Applies, for each fork point, the first branch without contradictions (or just the first). */
    public void resolveForkPointsConstraints() {
        if (forkPoints.isEmpty()) return;
        List<ForkPoint> all = List.copyOf(forkPoints);
        forkPoints.clear();
        for (ForkPoint f : all) applyTheBestBranch(f);
    }

    /** Whether some branch has no contradictions. */
    private boolean applyTheBestBranch(ForkPoint forkPoint) {
        boolean successful = false;
        for (List<VariableConstraint> branch : forkPoint.branches()) {
            if (runTransaction(() -> {
                applyForkPointBranch(branch, forkPoint.position());
                return errors.isEmpty();
            })) {
                successful = true;
                break;
            }
        }
        if (!successful) applyForkPointBranch(forkPoint.branches().getFirst(), forkPoint.position());
        return successful;
    }

    private void applyForkPointBranch(List<VariableConstraint> branch, IncorporationPosition position) {
        injector.processGivenForkPointBranchConstraints(this, branch, position);
        // New fork points from the branch are applied right away too.
        resolveForkPointsConstraints();
    }

    /** Applies all fork points if there are contradictions anyway, as before kotlinc freezes a system. */
    public void prepareForReading() {
        if (areThereContradictionsInForks()) resolveForkPointsConstraints();
    }

    // ==== Proper types ====

    /** A type without variables of this system (other than in captured types from expressions). */
    public boolean isProperType(KType type) {
        if (allTypeVariables.isEmpty()) return true;
        return !InferenceTypes.contains(type, t -> {
            KType.Captured captured = t instanceof KType.Flexible ? null : InferenceTypes.capturedUnwrappingDnn(t);
            KType toCheck = captured != null
                    && captured.constructor().status() == CapturedTypeConstructor.Status.FROM_EXPRESSION
                    ? captured.constructor().projection().type() : t;
            if (toCheck == null) return false;
            TypeVariable v = InferenceTypes.typeVariable(toCheck);
            return allTypeVariables.contains(v)
                    && (typeVariablesCountedAsProperTypes == null || !typeVariablesCountedAsProperTypes.contains(v));
        });
    }

    /** Whether the variable is taken as a proper type for now. */
    boolean isCountedAsProperType(TypeVariable v) {
        return typeVariablesCountedAsProperTypes != null && typeVariablesCountedAsProperTypes.contains(v);
    }

    /**
     * Runs the operation with the variables taken as proper types (though
     * not at the top level), as kotlinc's withTypeVariablesThatAreCountedAsProperTypes.
     */
    public <T> T withTypeVariablesCountedAsProperTypes(Set<TypeVariable> variables, Supplier<T> operation) {
        Set<TypeVariable> before = typeVariablesCountedAsProperTypes;
        typeVariablesCountedAsProperTypes = variables;
        try {
            return operation.get();
        } finally {
            typeVariablesCountedAsProperTypes = before;
        }
    }

    public boolean isTypeVariable(KType type) {
        return notFixed.containsKey(InferenceTypes.typeVariable(type));
    }

    /** Whether the type mentions no variable that is not fixed. */
    public boolean containsOnlyFixedVariables(KType type) {
        return !InferenceTypes.contains(type, t -> notFixed.containsKey(InferenceTypes.typeVariable(t)));
    }

    public boolean hasUpperOrEqualUnitConstraint(KType type) {
        VariableWithConstraints v = notFixed.get(InferenceTypes.typeVariable(type));
        if (v == null) return false;
        return v.constraints().stream().anyMatch(c -> c.kind() != ConstraintKind.LOWER
                && Types.lowerBound(c.type()) instanceof KType.ClassType ct
                && ct.symbol() == ts.table().requireClass("kotlin.Unit") && !ct.nullable());
    }

    // ==== Fixation ====

    /** Fixes the variable to the type: no longer a variable, it equals the type in all constraints. */
    public void fixVariable(TypeVariable variable, KType resultType, ConstraintPosition position) {
        Set<TypeVariable> outer = outerTypeVariables();
        if (outer != null && outer.contains(variable)) {
            throw new IllegalStateException("Outer variable " + variable + " is not fixed in a nested call");
        }
        injector.addInitialEqualityConstraint(this, variable.type(), resultType, position);
        if (notFixed.remove(variable) == null) throw new IllegalStateException(variable + " is being fixed a second time");
        for (VariableWithConstraints other : notFixed.values()) {
            other.removeConstraints(c -> InferenceTypes.containsTypeVariable(c.type(), variable));
        }
        fixed.put(variable, resultType);
    }

    /**
     * The type a variable would be fixed to now, with all variables not
     * fixed taken as proper types, made equal to it without fixing it, as
     * FIR's semiFixCurrentResultIfTypeVariableAndReturnBinding in PCLA;
     * null if there is no proper constraint to give it.
     */
    public KType semiFixVariable(TypeVariable variable) {
        VariableWithConstraints v = notFixed.get(variable);
        if (v == null) return null;
        KType resultType = withTypeVariablesCountedAsProperTypes(Set.copyOf(notFixed.keySet()), () -> {
            if (!new VariableFixationFinder(this).typeVariableHasProperConstraint(variable)) return null;
            return resultTypeResolver.findResultType(this, v, ResultTypeResolver.Direction.UNKNOWN);
        });
        if (resultType == null) return null;
        addEqualityConstraint(variable.type(), resultType,
                new ConstraintPosition(ConstraintPosition.Kind.SEMI_FIX_VARIABLE, variable));
        return resultType;
    }

    /** Replaces the fixed variables by their types. */
    public Substitution buildCurrentSubstitutor() {
        Map<TypeVariable, TypeArg> map = new HashMap<>();
        fixed.forEach((v, t) -> map.put(v, TypeArg.of(t)));
        return new Substitution(map);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        notFixed.values().forEach(v -> sb.append(v.variable()).append(": ").append(v.constraints()).append('\n'));
        fixed.forEach((v, t) -> sb.append(v).append(" := ").append(t).append('\n'));
        errors.forEach(e -> sb.append("error: ").append(e).append('\n'));
        return sb.toString();
    }
}
