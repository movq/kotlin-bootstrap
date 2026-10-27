package kotlinbootstrap.infer;

import java.util.Objects;
import java.util.Set;

import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeVariable;

/**
 * A bound on one type variable, as kotlinc's Constraint. The type may be
 * flexible.
 *
 * @param derivedFrom            the variables whose constraints were incorporated to
 *                               give this one, to stop incorporation going in circles
 * @param isNullabilityConstraint {@code Nothing? <: T} derived from {@code K? <: T} for
 *                               another variable K: not proper, and only gives nullability
 */
public record Constraint(ConstraintKind kind, KType type, IncorporationPosition position,
                         Set<TypeVariable> derivedFrom, boolean isNullabilityConstraint) {
    /**
     * The hash code of the type as in FIR, where a definitely non-null type
     * hashes as its original and a flexible type as its lower bound; types
     * of constraints are only compared if their hash codes match.
     */
    int typeHashCode() {
        return typeHashCode(type);
    }

    static int typeHashCode(KType t) {
        return switch (t) {
            case KType.Flexible f -> typeHashCode(f.lower());
            case KType.DefinitelyNotNull d -> typeHashCode(d.inner());
            default -> t.hashCode();
        };
    }

    // As kotlinc: the derivation and nullability do not take part.
    @Override
    public boolean equals(Object o) {
        return o instanceof Constraint c && kind == c.kind && position.equals(c.position) && type.equals(c.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, type);
    }

    @Override
    public String toString() {
        return kind + "(" + type + ") from " + position.initial();
    }
}
