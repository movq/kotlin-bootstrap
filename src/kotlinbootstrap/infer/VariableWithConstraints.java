package kotlinbootstrap.infer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeVariable;

/**
 * The constraints on a variable not yet fixed, as kotlinc's
 * MutableVariableWithConstraints: new constraints are dropped if an
 * existing one implies them, and a lower and upper constraint with the
 * same type become an equality.
 */
public final class VariableWithConstraints {
    private final TypeVariable variable;
    private final List<Constraint> mutableConstraints;
    /**
     * The constraints without those that others make redundant. Either the
     * same list as mutableConstraints (as long as only appending happened),
     * a list kept in step by appending to both, or null to recompute.
     * Incorporation iterates over it by index while constraints are appended.
     */
    private List<Constraint> simplifiedConstraints;
    private Map<TypeVariable, List<Constraint>> byContainedVariable;

    VariableWithConstraints(TypeVariable variable) {
        this.variable = variable;
        this.mutableConstraints = new ArrayList<>();
        this.simplifiedConstraints = mutableConstraints;
    }

    /** A copy for another system. */
    VariableWithConstraints(VariableWithConstraints other) {
        this.variable = other.variable;
        this.mutableConstraints = new ArrayList<>(other.constraints());
        this.simplifiedConstraints = mutableConstraints;
    }

    public TypeVariable variable() {
        return variable;
    }

    public List<Constraint> constraints() {
        if (simplifiedConstraints == null) simplifiedConstraints = simplify(mutableConstraints);
        return simplifiedConstraints;
    }

    int rawConstraintsCount() {
        return mutableConstraints.size();
    }

    /** The constraints whose types mention the variable, also in captured type projections. */
    List<Constraint> constraintsContaining(TypeVariable other) {
        if (byContainedVariable == null) {
            byContainedVariable = new HashMap<>();
            for (Constraint c : constraints()) {
                for (TypeVariable v : InferenceTypes.extractAllContainingTypeVariables(c.type())) {
                    byContainedVariable.computeIfAbsent(v, k -> new ArrayList<>()).add(c);
                }
            }
        }
        return byContainedVariable.getOrDefault(other, List.of());
    }

    /** The added constraint, or the existing one that makes it redundant; whether something was added. */
    record Added(Constraint constraint, boolean wasAdded) {
    }

    Added addConstraint(Constraint constraint) {
        boolean lowerFlexibleDnn = isLowerAndFlexibleTypeWithDnnLowerBound(constraint);
        int hash = constraint.typeHashCode();
        for (Constraint previous : constraints()) {
            if (previous.typeHashCode() != hash) continue;
            if (previous.type().equals(constraint.type())
                    && previous.isNullabilityConstraint() == constraint.isNullabilityConstraint()) {
                if (newConstraintIsUseless(previous, constraint)) return new Added(previous, false);

                boolean matching = switch (previous.kind()) {
                    case LOWER -> constraint.kind() == ConstraintKind.UPPER;
                    case UPPER -> constraint.kind() == ConstraintKind.LOWER;
                    case EQUALITY -> true;
                };
                if (matching) {
                    Constraint actual = constraint;
                    if (constraint.kind() != ConstraintKind.EQUALITY) {
                        IncorporationPosition position =
                                constraint.position().fromKind() != ConstraintPosition.Kind.DECLARED_UPPER_BOUND
                                        ? constraint.position() : previous.position();
                        actual = new Constraint(ConstraintKind.EQUALITY, constraint.type(), position,
                                constraint.derivedFrom(), false);
                    }
                    mutableConstraints.add(actual);
                    byContainedVariable = null;
                    simplifiedConstraints = null;
                    return new Added(actual, true);
                }
            }
            if (lowerFlexibleDnn && isStrongerThanLowerAndFlexibleTypeWithDnnLowerBound(previous, constraint)) {
                return new Added(previous, false);
            }
        }

        mutableConstraints.add(constraint);
        if (simplifiedConstraints != null && simplifiedConstraints != mutableConstraints) {
            simplifiedConstraints.add(constraint);
        }
        byContainedVariable = null;
        return new Added(constraint, true);
    }

    /** For rolling back a transaction: removes the constraints added since. */
    void removeLastConstraints(int sinceIndex) {
        mutableConstraints.subList(sinceIndex, mutableConstraints.size()).clear();
        if (simplifiedConstraints != mutableConstraints) simplifiedConstraints = null;
        byContainedVariable = null;
    }

    void removeConstraints(Predicate<Constraint> shouldRemove) {
        mutableConstraints.removeIf(shouldRemove);
        if (simplifiedConstraints != mutableConstraints) simplifiedConstraints = null;
        byContainedVariable = null;
    }

    private static boolean newConstraintIsUseless(Constraint old, Constraint added) {
        // Constraints from declared upper bounds are not proper ones; user-written ones must not be lost to them.
        if (old.position().fromKind() == ConstraintPosition.Kind.DECLARED_UPPER_BOUND
                && added.position().fromKind() != ConstraintPosition.Kind.DECLARED_UPPER_BOUND) {
            return false;
        }
        // The result type resolver discriminates upper expected type constraints, so others must be kept.
        if (old.position().fromKind() == ConstraintPosition.Kind.EXPECTED_TYPE
                && added.position().fromKind() != ConstraintPosition.Kind.EXPECTED_TYPE
                && old.kind() == ConstraintKind.UPPER && added.kind() == ConstraintKind.UPPER) {
            return false;
        }
        return switch (old.kind()) {
            case EQUALITY -> true;
            case LOWER -> added.kind() == ConstraintKind.LOWER;
            case UPPER -> added.kind() == ConstraintKind.UPPER;
        };
    }

    private static List<Constraint> simplify(List<Constraint> constraints) {
        return simplifyEqualityConstraints(simplifyLowerConstraints(constraints));
    }

    /** {@code (T & Any..T?) <: K} is useless next to {@code (T..T?) <: K}, since their common supertype is the latter. */
    private static List<Constraint> simplifyLowerConstraints(List<Constraint> constraints) {
        List<Constraint> useful = new ArrayList<>();
        for (Constraint c : constraints) {
            if (!isLowerAndFlexibleTypeWithDnnLowerBound(c)
                    || constraints.stream().noneMatch(o -> isStrongerThanLowerAndFlexibleTypeWithDnnLowerBound(o, c))) {
                useful.add(c);
            }
        }
        return useful;
    }

    private static boolean isLowerAndFlexibleTypeWithDnnLowerBound(Constraint c) {
        return c.kind() == ConstraintKind.LOWER && c.type() instanceof KType.Flexible f
                && f.lower() instanceof KType.DefinitelyNotNull;
    }

    private static boolean isStrongerThanLowerAndFlexibleTypeWithDnnLowerBound(Constraint c, Constraint other) {
        if (c == other) return false;
        if (c.typeHashCode() != other.typeHashCode() || c.kind() == ConstraintKind.UPPER) return false;
        if (!(c.type() instanceof KType.Flexible f) || !(other.type() instanceof KType.Flexible o)) return false;
        if (!(o.lower() instanceof KType.DefinitelyNotNull dnn)) return false;
        return f.lower().equals(dnn.inner()) && f.upper().equals(o.upper());
    }

    /** Lower and upper constraints with the type of an equality constraint add nothing. */
    private static List<Constraint> simplifyEqualityConstraints(List<Constraint> constraints) {
        Map<Integer, List<Constraint>> equalities = new LinkedHashMap<>();
        for (Constraint c : constraints) {
            if (c.kind() == ConstraintKind.EQUALITY) {
                equalities.computeIfAbsent(c.typeHashCode(), k -> new ArrayList<>()).add(c);
            }
        }
        if (equalities.isEmpty()) return constraints;
        List<Constraint> result = new ArrayList<>();
        for (Constraint c : constraints) {
            List<Constraint> same = equalities.get(c.typeHashCode());
            if (c.kind() == ConstraintKind.EQUALITY || same == null
                    || same.stream().noneMatch(e -> e.type().equals(c.type()))) {
                result.add(c);
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "Constraints for " + variable;
    }
}
