package kotlinbootstrap.infer;

import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeVariable;

/**
 * A contradiction found by a constraint system. All of these make a
 * candidate inapplicable; the kinds kotlinc only warns about are not
 * recorded.
 *
 * @param variable for CAPTURED_TYPE_FROM_SUBTYPING and NOT_ENOUGH_INFORMATION
 * @param lower    for NEW_CONSTRAINT, the type that is not a subtype of upper;
 *                 for CAPTURED_TYPE_FROM_SUBTYPING, the type of the constraint
 * @param position null for NOT_ENOUGH_INFORMATION
 */
public record ConstraintError(Kind kind, TypeVariable variable, KType lower, KType upper,
                              IncorporationPosition position) {
    public enum Kind { NEW_CONSTRAINT, CAPTURED_TYPE_FROM_SUBTYPING, NOT_ENOUGH_INFORMATION }

    /** A mismatch of the receiver, which makes a candidate less applicable than one with other mismatches. */
    public boolean isWrongReceiver() {
        return kind == Kind.NEW_CONSTRAINT && position.fromKind() == ConstraintPosition.Kind.RECEIVER;
    }

    @Override
    public String toString() {
        return switch (kind) {
            case NEW_CONSTRAINT -> lower + " is not a subtype of " + upper + " (" + position.initial() + ")";
            case CAPTURED_TYPE_FROM_SUBTYPING -> "Captured type " + lower + " in a constraint for " + variable;
            case NOT_ENOUGH_INFORMATION -> "Not enough information to infer " + variable;
        };
    }
}
