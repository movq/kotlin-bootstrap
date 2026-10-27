package kotlinbootstrap.infer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.types.CapturedTypeConstructor;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.SubtypeChecker;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Variance;

/**
 * Derives new constraints from a new one, as kotlinc's
 * ConstraintIncorporator: with the other bounds of its variable
 * ({@code A <: T <: B} gives {@code A <: B}), and inside the constraints of
 * other variables that mention it ({@code T <: Number, S <: Inv<T>} gives
 * {@code S <: Inv<out Number>}).
 */
final class ConstraintIncorporator {
    private final TypeSystem ts;

    ConstraintIncorporator(TypeSystem ts) {
        this.ts = ts;
    }

    void incorporate(ConstraintInjector.State c, TypeVariable variable, Constraint constraint, boolean causedByFixation) {
        // Recursive constraints are too dangerous to incorporate.
        if (InferenceTypes.contains(constraint.type(), t -> InferenceTypes.typeVariable(t) == variable)) return;
        directWithVariable(c, variable, constraint);
        insideOtherConstraint(c, variable, constraint);
    }

    /** {@code A <:(=) T <:(=) B} gives {@code A <: B}. */
    private void directWithVariable(ConstraintInjector.State c, TypeVariable variable, Constraint constraint) {
        boolean shouldBeFlexible = variable.typeParam() != null
                && variable.typeParam().bounds().stream().anyMatch(b -> b instanceof KType.Flexible);

        // T <: constraint.type
        if (constraint.kind() != ConstraintKind.LOWER) {
            List<Constraint> constraints = constraintsFor(c, variable);
            // Indexed, since constraints are appended meanwhile.
            for (int i = 0; i < constraints.size(); i++) {
                Constraint other = constraints.get(i);
                if (other != constraint && other.kind() != ConstraintKind.UPPER) {
                    c.processNewInitialConstraintFromIncorporation(other.type(), constraint.type(), shouldBeFlexible,
                            newDerivedFrom(constraint, other), other.isNullabilityConstraint(), false);
                }
            }
        }

        // constraint.type <: T
        if (constraint.kind() != ConstraintKind.UPPER) {
            List<Constraint> constraints = constraintsFor(c, variable);
            for (int i = 0; i < constraints.size(); i++) {
                Constraint other = constraints.get(i);
                if (other != constraint && other.kind() != ConstraintKind.LOWER) {
                    boolean fromDeclaredUpperBound = other.position().fromKind() == ConstraintPosition.Kind.DECLARED_UPPER_BOUND
                            && InferenceTypes.typeVariable(other.type()) == null;
                    c.processNewInitialConstraintFromIncorporation(constraint.type(), other.type(), shouldBeFlexible,
                            newDerivedFrom(constraint, other), false, fromDeclaredUpperBound);
                }
            }
        }
    }

    private static List<Constraint> constraintsFor(ConstraintInjector.State c, TypeVariable variable) {
        VariableWithConstraints v = c.c.notFixedTypeVariables().get(variable);
        if (v == null) throw c.fixedTypeVariable(variable);
        return v.constraints();
    }

    private static Set<TypeVariable> newDerivedFrom(Constraint a, Constraint b) {
        if (a.derivedFrom().isEmpty()) return b.derivedFrom();
        if (b.derivedFrom().isEmpty()) return a.derivedFrom();
        Set<TypeVariable> result = new HashSet<>(a.derivedFrom());
        result.addAll(b.derivedFrom());
        return result;
    }

    /** {@code T <: Number, S <: Inv<T>} gives {@code S <: Inv<out Number>}. */
    private void insideOtherConstraint(ConstraintInjector.State c, TypeVariable variable, Constraint constraint) {
        if (constraint.derivedFrom().contains(variable)) return;
        for (VariableWithConstraints other : c.c.variablesWithConstraintsContaining(variable)) {
            for (Constraint otherConstraint : List.copyOf(other.constraintsContaining(variable))) {
                generateNewConstraint(c, variable, constraint, other.variable(), otherConstraint);
            }
        }
    }

    private void generateNewConstraint(ConstraintInjector.State c, TypeVariable causeVariable, Constraint cause,
                                       TypeVariable otherVariable, Constraint otherConstraint) {
        if (otherConstraint.derivedFrom().contains(causeVariable)) return;

        boolean isBaseGenericType = InferenceTypes.argumentsCount(otherConstraint.type()) != 0;
        boolean isBaseOrOtherCapturedType = isCapturedType(otherConstraint.type()) || isCapturedType(cause.type());
        boolean simple = !isBaseGenericType && !isBaseOrOtherCapturedType;
        KType replacement;
        boolean needsApproximation = false;
        switch (cause.kind()) {
            case EQUALITY -> replacement = cause.type();
            case UPPER -> {
                // Approximating Captured(out Number) would give Nothing or itself anyway.
                if (simple && otherConstraint.kind() == ConstraintKind.LOWER) {
                    replacement = ts.nothing();
                } else if (simple && otherConstraint.kind() == ConstraintKind.UPPER) {
                    replacement = cause.type();
                } else {
                    CapturedTypeConstructor constructor = new CapturedTypeConstructor(
                            new TypeArg(Variance.OUT, cause.type()), null, CapturedTypeConstructor.Status.FOR_INCORPORATION);
                    constructor.setSupertypes(List.of(cause.type()));
                    replacement = new KType.Captured(constructor, null, false);
                    needsApproximation = true;
                }
            }
            case LOWER -> {
                // Approximating Captured(in Number) would give Any? or itself anyway.
                if (simple && otherConstraint.kind() == ConstraintKind.UPPER) {
                    replacement = ts.nullableAny();
                } else if (simple && otherConstraint.kind() == ConstraintKind.LOWER) {
                    replacement = cause.type();
                } else {
                    CapturedTypeConstructor constructor = new CapturedTypeConstructor(
                            new TypeArg(Variance.IN, cause.type()), null, CapturedTypeConstructor.Status.FOR_INCORPORATION);
                    constructor.setSupertypes(List.of());
                    replacement = new KType.Captured(constructor, cause.type(), false);
                    needsApproximation = true;
                }
            }
            default -> throw new IllegalStateException();
        }
        KType type = new Substitution(Map.of(causeVariable, TypeArg.of(replacement))).apply(otherConstraint.type());

        if (otherConstraint.kind() != ConstraintKind.LOWER) {
            KType prepared = needsApproximation ? approximate(type, true) : type;
            addNewConstraint(c, causeVariable, cause, otherVariable, otherConstraint, prepared, false);
        }
        if (otherConstraint.kind() != ConstraintKind.UPPER) {
            KType prepared = needsApproximation ? approximate(type, false) : type;
            addNewConstraint(c, causeVariable, cause, otherVariable, otherConstraint, prepared, true);
        }
    }

    private static boolean isCapturedType(KType t) {
        return !(t instanceof KType.Flexible) && InferenceTypes.capturedUnwrappingDnn(t) != null;
    }

    private KType approximate(KType type, boolean toSuper) {
        KType result = toSuper
                ? TypeApproximator.approximateToSuperType(ts, type, TypeApproximator.Config.INCORPORATION)
                : TypeApproximator.approximateToSubType(ts, type, TypeApproximator.Config.INCORPORATION);
        return result != null ? result : type;
    }

    private void addNewConstraint(ConstraintInjector.State c, TypeVariable causeVariable, Constraint cause,
                                  TypeVariable target, Constraint otherConstraint, KType newType, boolean isSubtype) {
        if (containsNestedTypeVariable(c, newType, target)) return;

        boolean usefulForNullability = isPotentialUsefulNullabilityConstraint(newType, cause.type(), cause.kind());
        boolean fromFixation = otherConstraint.position().fromKind() == ConstraintPosition.Kind.FIX_VARIABLE
                || cause.position().fromKind() == ConstraintPosition.Kind.FIX_VARIABLE;
        if (cause.kind() != ConstraintKind.EQUALITY && !usefulForNullability && !fromFixation
                && !containsConstrainingTypeWithoutProjection(newType, cause)) {
            return;
        }
        if (isGeneratedConstraintTrivial(otherConstraint, cause, newType, isSubtype)) return;

        Set<TypeVariable> derivedFrom = new HashSet<>(otherConstraint.derivedFrom());
        derivedFrom.addAll(cause.derivedFrom());
        derivedFrom.add(causeVariable);

        boolean isNullabilityConstraint = usefulForNullability && InferenceTypes.isNullableNothing(ts, newType)
                || cause.isNullabilityConstraint() && InferenceTypes.isNullableNothing(ts, cause.type());
        c.addNewIncorporatedConstraint(target, newType, isSubtype ? ConstraintKind.LOWER : ConstraintKind.UPPER,
                derivedFrom, isNullabilityConstraint);
    }

    private static boolean containsConstrainingTypeWithoutProjection(KType type, Constraint other) {
        Object constructor = SubtypeChecker.constructor(other.type());
        return InferenceTypes.anyNestedArgument(type,
                a -> SubtypeChecker.constructor(a.type()).equals(constructor) && a.variance() == Variance.INVARIANT);
    }

    private boolean isPotentialUsefulNullabilityConstraint(KType type, KType otherType, ConstraintKind kind) {
        if (!InferenceTypes.isNothingConstructor(ts, type)) return false;
        boolean nullable = InferenceTypes.isNullableType(type, false);
        boolean otherNullable = InferenceTypes.isNullableType(otherType, false);
        return !nullable && otherNullable && kind == ConstraintKind.LOWER
                || nullable && !otherNullable && kind == ConstraintKind.UPPER;
    }

    private static boolean containsNestedTypeVariable(ConstraintInjector.State c, KType type, TypeVariable target) {
        return InferenceTypes.anyNestedArgument(type, a -> {
            TypeVariable v = InferenceTypes.typeVariable(a.type());
            if (v == null || !c.c.allTypeVariables().contains(v)) return false;
            if (!c.c.notFixedTypeVariables().containsKey(v)) throw c.fixedTypeVariable(v);
            return v == target;
        });
    }

    // ==== TrivialConstraintTypeInferenceOracle ====

    /**
     * Nothing-like constraints that incorporation generates, e.g.
     * {@code Nothing <: K} from {@code T <: K}, add nothing but can change
     * the result; they are dropped.
     */
    private boolean isGeneratedConstraintTrivial(Constraint base, Constraint other, KType generated, boolean isSubtype) {
        if (isSubtype && (InferenceTypes.isNothing(ts, generated) || InferenceTypes.isFlexibleNothing(ts, generated))) {
            return true;
        }
        if (!isSubtype && InferenceTypes.isNullableAny(ts, generated)) return true;
        // If the constraints already mention Nothing(?), the result may matter.
        if (InferenceTypes.contains(base.type(), t -> InferenceTypes.isNothingConstructor(ts, t))) return false;
        if (InferenceTypes.contains(other.type(), t -> InferenceTypes.isNothingConstructor(ts, t))) return false;
        // Nothing? <: T is kept.
        return InferenceTypes.contains(generated,
                t -> InferenceTypes.isNothing(ts, t) || InferenceTypes.isFlexibleNothing(ts, t));
    }

    static boolean isNotInterestingConstraint(TypeSystem ts, Constraint c) {
        return c.kind() == ConstraintKind.LOWER && InferenceTypes.isNothingConstructor(ts, c.type());
    }

    /** Nothing(?) is the most specific type, but no useful result; a supertype is preferred. */
    static boolean isSuitableResultedType(TypeSystem ts, KType t) {
        return !InferenceTypes.isNothingConstructor(ts, t);
    }
}
