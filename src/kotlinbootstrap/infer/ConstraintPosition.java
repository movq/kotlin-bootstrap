package kotlinbootstrap.infer;

/**
 * Where an initial constraint comes from. Inference treats some origins
 * specially: declared upper bounds are not proper constraints, expected
 * type constraints are not intersected away, and fixation is the end.
 *
 * @param detail for FIX_VARIABLE and SEMI_FIX_VARIABLE, the type variable;
 *               otherwise whatever helps to explain the constraint, or null
 */
public record ConstraintPosition(Kind kind, Object detail) {
    public enum Kind {
        DECLARED_UPPER_BOUND, EXPLICIT_TYPE_ARGUMENT, EXPECTED_TYPE, RECEIVER, ARGUMENT, LAMBDA_ARGUMENT,
        CALLABLE_REFERENCE, DELEGATED_PROPERTY, FIX_VARIABLE, SEMI_FIX_VARIABLE, OTHER
    }

    public static ConstraintPosition of(Kind kind) {
        return new ConstraintPosition(kind, null);
    }

    @Override
    public String toString() {
        return detail == null ? kind.toString() : kind + " " + detail;
    }
}
