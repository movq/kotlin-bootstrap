package kotlinbootstrap.infer;

/** How a constraint relates a type variable to a type: {@code T :> type}, {@code T <: type} or {@code T == type}. */
public enum ConstraintKind {
    LOWER, UPPER, EQUALITY;

    public ConstraintKind opposite() {
        return switch (this) {
            case LOWER -> UPPER;
            case UPPER -> LOWER;
            case EQUALITY -> EQUALITY;
        };
    }
}
