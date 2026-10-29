package kotlinbootstrap.dfa;

/** What an operation statement says about a variable's value. */
public enum Operation {
    EQ_TRUE, EQ_FALSE, EQ_NULL, NOT_EQ_NULL;

    /** Whether this holds given that {@code given} does; null if unknown. */
    Boolean valueIfKnown(Operation given) {
        return switch (this) {
            case EQ_TRUE, EQ_FALSE -> given == NOT_EQ_NULL ? null : given == this;
            case EQ_NULL -> given == EQ_NULL;
            case NOT_EQ_NULL -> given != EQ_NULL;
        };
    }

    @Override
    public String toString() {
        return switch (this) {
            case EQ_TRUE -> "== True";
            case EQ_FALSE -> "== False";
            case EQ_NULL -> "== Null";
            case NOT_EQ_NULL -> "!= Null";
        };
    }
}
