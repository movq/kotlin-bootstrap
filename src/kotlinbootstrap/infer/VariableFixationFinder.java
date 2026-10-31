package kotlinbootstrap.infer;

import java.util.List;

import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.SubtypeChecker;
import kotlinbootstrap.types.TypeVariable;

/**
 * Chooses the variable to fix next, as kotlinc's VariableFixationFinder
 * with the (lexicographic) VariableReadinessCalculator: each variable gets
 * a readiness of qualities in order of priority, and the first variable
 * with the highest readiness is chosen.
 */
final class VariableFixationFinder {
    /** Earlier qualities have higher priority. */
    enum Quality {
        // Strong de-prioritizers: normally true.
        ALLOWED,
        // A proper constraint does not depend on variables not fixed.
        HAS_PROPER_CONSTRAINTS,
        HAS_NO_OUTER_TYPE_VARIABLE_DEPENDENCY,
        // With T <: Captured(...) and T <: K, fixing T before K avoids T <: Captured(...) & SomeRegularType.
        HAS_CAPTURED_UPPER_BOUND_WITH_SELF_TYPES,
        HAS_PROPER_NON_SELF_TYPE_BASED_CONSTRAINT,
        HAS_NO_DEPENDENCIES_TO_OTHER_VARIABLES,
        // Proper constraints other than T >: Nothing(?).
        HAS_PROPER_NON_TRIVIAL_CONSTRAINTS,
        HAS_PROPER_NON_TRIVIAL_CONSTRAINTS_OTHER_THAN_INCORPORATED_FROM_DECLARED_UPPER_BOUND,
        // For materialize-like functions, as `val x: Base = run { materialize() ?: derived }`.
        REIFIED,
        // Fixing to the flexible type before a nullable one comes from other variables.
        HAS_PROPER_FLEXIBLE_LOWER_CONSTRAINT,
        HAS_PROPER_NON_ILT_CONSTRAINT,
        // An explicit Nothing lower constraint would fix to Nothing and poison dependent variables.
        HAS_NO_EXPLICIT_LOWER_NOTHING_CONSTRAINT,
        HAS_PROPER_EQUALITY_CONSTRAINT,
        // Lower constraints tend to give more specific results than upper ones.
        HAS_PROPER_NON_NOTHING_NON_UPPER_CONSTRAINT;

        int mask() {
            return 1 << (30 - ordinal());
        }
    }

    record VariableForFixation(TypeVariable variable, boolean hasProperConstraint,
                               boolean hasDependencyOnOuterTypeVariable) {
        boolean isReady() {
            return hasProperConstraint && !hasDependencyOnOuterTypeVariable;
        }
    }

    private final ConstraintSystem c;

    VariableFixationFinder(ConstraintSystem c) {
        this.c = c;
    }

    VariableForFixation findFirstVariableForFixation(List<TypeVariable> allTypeVariables,
                                                     List<? extends PostponedAtom> postponedArguments,
                                                     CompletionMode mode, KType topLevelType) {
        if (allTypeVariables.isEmpty()) return null;
        // Only partial completion keeps the variables of the return type for the outer call.
        TypeVariableDependencies dependencies = new TypeVariableDependencies(c.notFixedTypeVariables(),
                postponedArguments, mode.preventFixingTypeVariablesRelatedToReturnType ? topLevelType : null,
                c.outerTypeVariables());
        TypeVariable candidate = null;
        int best = 0;
        for (TypeVariable v : allTypeVariables) {
            int readiness = readiness(v, dependencies);
            if (candidate == null || readiness > best) {
                candidate = v;
                best = readiness;
            }
        }
        if ((best & Quality.ALLOWED.mask()) == 0) return null;
        return new VariableForFixation(candidate, (best & Quality.HAS_PROPER_CONSTRAINTS.mask()) != 0,
                (best & Quality.HAS_NO_OUTER_TYPE_VARIABLE_DEPENDENCY.mask()) == 0);
    }

    boolean typeVariableHasProperConstraint(TypeVariable variable) {
        int readiness = readiness(variable,
                new TypeVariableDependencies(c.notFixedTypeVariables(), List.of(), null, c.outerTypeVariables()));
        int mask = Quality.ALLOWED.mask() | Quality.HAS_PROPER_CONSTRAINTS.mask();
        return (readiness & mask) == mask;
    }

    private int readiness(TypeVariable v, TypeVariableDependencies dependencies) {
        boolean forbidden = !c.notFixedTypeVariables().containsKey(v)
                || dependencies.isVariableRelatedToTopLevelType(v)
                || hasUnprocessedConstraintsInForks(v);
        if (forbidden) return 0;
        List<Constraint> constraints = c.notFixedTypeVariables().get(v).constraints();
        boolean allSelfTypeBased = areAllProperConstraintsSelfTypeBased(v, constraints);
        boolean hasProperConstraints = hasProperArgumentConstraints(constraints) || allSelfTypeBased;

        int readiness = 0;
        readiness |= bit(Quality.ALLOWED, true);
        readiness |= bit(Quality.HAS_PROPER_CONSTRAINTS, hasProperConstraints);
        readiness |= bit(Quality.HAS_NO_OUTER_TYPE_VARIABLE_DEPENDENCY, !dependencies.isRelatedToOuterTypeVariable(v));
        readiness |= bit(Quality.HAS_CAPTURED_UPPER_BOUND_WITH_SELF_TYPES,
                allSelfTypeBased && !isReified(v) && hasDirectConstraintToNotFixedVariable(constraints));
        readiness |= bit(Quality.HAS_PROPER_NON_SELF_TYPE_BASED_CONSTRAINT, hasProperConstraints && !allSelfTypeBased);
        readiness |= bit(Quality.HAS_NO_DEPENDENCIES_TO_OTHER_VARIABLES,
                constraints.stream().noneMatch(x -> hasDependencyToOtherTypeVariable(x, v)));
        readiness |= bit(Quality.HAS_PROPER_NON_TRIVIAL_CONSTRAINTS, !constraints.stream().allMatch(x ->
                ConstraintIncorporator.isNotInterestingConstraint(c.ts(), x) || !isProperArgumentConstraint(x)));
        readiness |= bit(Quality.HAS_PROPER_NON_TRIVIAL_CONSTRAINTS_OTHER_THAN_INCORPORATED_FROM_DECLARED_UPPER_BOUND,
                !hasOnlyIncorporatedConstraintsFromDeclaredUpperBound(constraints));
        readiness |= bit(Quality.REIFIED, isReified(v));
        readiness |= bit(Quality.HAS_PROPER_FLEXIBLE_LOWER_CONSTRAINT, constraints.stream().anyMatch(x ->
                x.kind() == ConstraintKind.LOWER && isProperArgumentConstraint(x) && x.type() instanceof KType.Flexible));
        readiness |= bit(Quality.HAS_PROPER_NON_ILT_CONSTRAINT, constraints.stream().anyMatch(x ->
                isProperArgumentConstraint(x) && !InferenceTypes.contains(x.type(), InferenceTypes::isIntegerLiteral)));
        readiness |= bit(Quality.HAS_NO_EXPLICIT_LOWER_NOTHING_CONSTRAINT, constraints.stream().noneMatch(x ->
                x.kind() == ConstraintKind.LOWER && InferenceTypes.isNothingConstructor(c.ts(), x.type())));
        readiness |= bit(Quality.HAS_PROPER_EQUALITY_CONSTRAINT, constraints.stream().anyMatch(x ->
                x.kind() == ConstraintKind.EQUALITY && isProperArgumentConstraint(x)));
        readiness |= bit(Quality.HAS_PROPER_NON_NOTHING_NON_UPPER_CONSTRAINT, constraints.stream().anyMatch(x ->
                x.kind() != ConstraintKind.UPPER && isProperArgumentConstraint(x)
                        && !InferenceTypes.isNothingConstructor(c.ts(), x.type())));
        return readiness;
    }

    private static int bit(Quality quality, boolean value) {
        return value ? quality.mask() : 0;
    }

    private boolean hasUnprocessedConstraintsInForks(TypeVariable v) {
        for (ConstraintSystem.ForkPoint forkPoint : c.forkPoints()) {
            for (List<ConstraintSystem.VariableConstraint> branch : forkPoint.branches()) {
                for (ConstraintSystem.VariableConstraint vc : branch) {
                    if (vc.variable() == v) return true;
                    if (InferenceTypes.containsTypeVariable(vc.constraint().type(), v)) return true;
                }
            }
        }
        return false;
    }

    private boolean hasProperArgumentConstraints(List<Constraint> constraints) {
        return constraints.stream().anyMatch(this::isProperArgumentConstraint);
    }

    private boolean isProperArgumentConstraint(Constraint constraint) {
        return isProperType(constraint.type())
                && constraint.position().fromKind() != ConstraintPosition.Kind.DECLARED_UPPER_BOUND
                && !constraint.isNullabilityConstraint();
    }

    private boolean isProperType(KType type) {
        return InferenceTypes.isProperTypeForFixation(type, c.notFixedTypeVariables().keySet(),
                t -> !InferenceTypes.contains(t, this::isNotFixedVariable));
    }

    private boolean isNotFixedVariable(KType t) {
        TypeVariable v = InferenceTypes.typeVariable(t);
        return c.notFixedTypeVariables().containsKey(v) && !c.isCountedAsProperType(v);
    }

    private boolean hasDirectConstraintToNotFixedVariable(List<Constraint> constraints) {
        return constraints.stream().anyMatch(x -> isNotFixedVariable(x.type()));
    }

    private boolean isReified(TypeVariable v) {
        TypeParamSymbol typeParam = v.typeParam();
        return typeParam != null && typeParam.reified();
    }

    private boolean hasOnlyIncorporatedConstraintsFromDeclaredUpperBound(List<Constraint> constraints) {
        return constraints.stream()
                .filter(x -> isProperArgumentConstraint(x) && !isTrivial(x))
                .allMatch(x -> x.position().fromDeclaredUpperBound());
    }

    private boolean isTrivial(Constraint x) {
        return x.kind() == ConstraintKind.LOWER && InferenceTypes.isNothing(c.ts(), x.type())
                || x.kind() == ConstraintKind.UPPER && InferenceTypes.isNullableAny(c.ts(), x.type());
    }

    private boolean hasDependencyToOtherTypeVariable(Constraint constraint, TypeVariable owner) {
        return InferenceTypes.argumentsCount(constraint.type()) != 0
                && InferenceTypes.contains(constraint.type(), t -> {
                    TypeVariable v = InferenceTypes.typeVariable(t);
                    return v != owner && c.notFixedTypeVariables().containsKey(v);
                });
    }

    private boolean isProperSelfTypeConstraint(Constraint constraint, TypeVariable owner) {
        Object constructor = SubtypeChecker.constructor(constraint.type());
        return constraint.position().fromKind() == ConstraintPosition.Kind.DECLARED_UPPER_BOUND
                && (InferenceTypes.hasRecursiveTypeParametersWithGivenSelfType(constructor)
                || InferenceTypes.isRecursiveTypeParameter(constructor))
                && !hasDependencyToOtherTypeVariable(constraint, owner);
    }

    private boolean areAllProperConstraintsSelfTypeBased(TypeVariable v, List<Constraint> constraints) {
        if (constraints.isEmpty()) return false;
        boolean hasSelfTypeConstraint = false;
        boolean hasOtherProperConstraint = false;
        for (Constraint constraint : constraints) {
            if (isProperSelfTypeConstraint(constraint, v)) hasSelfTypeConstraint = true;
            if (isProperArgumentConstraint(constraint)) hasOtherProperConstraint = true;
            if (hasSelfTypeConstraint && hasOtherProperConstraint) break;
        }
        return hasSelfTypeConstraint && !hasOtherProperConstraint;
    }
}
