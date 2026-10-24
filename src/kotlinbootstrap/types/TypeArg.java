package kotlinbootstrap.types;

/**
 * A type argument: a type with use-site variance, or a star projection.
 *
 * @param type null for a star projection
 */
public record TypeArg(Variance variance, KType type) {
    public static final TypeArg STAR = new TypeArg(Variance.INVARIANT, null);

    public static TypeArg of(KType type) {
        return new TypeArg(Variance.INVARIANT, type);
    }

    public boolean isStar() {
        return type == null;
    }

    @Override
    public String toString() {
        return type == null ? "*" : variance.prefix() + type;
    }
}
