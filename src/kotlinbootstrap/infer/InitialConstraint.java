package kotlinbootstrap.infer;

import kotlinbootstrap.types.KType;

/**
 * A constraint as added to a system: {@code a <: b} (kind UPPER) or, with
 * a type variable as {@code a}, {@code a == b}. Compared by identity.
 */
public final class InitialConstraint {
    private final KType a;
    private final KType b;
    private final ConstraintKind kind;
    private final ConstraintPosition position;

    InitialConstraint(KType a, KType b, ConstraintKind kind, ConstraintPosition position) {
        this.a = a;
        this.b = b;
        this.kind = kind;
        this.position = position;
    }

    public KType a() {
        return a;
    }

    public KType b() {
        return b;
    }

    public ConstraintKind kind() {
        return kind;
    }

    public ConstraintPosition position() {
        return position;
    }

    @Override
    public String toString() {
        String sign = switch (kind) {
            case EQUALITY -> "==";
            case LOWER -> ":>";
            case UPPER -> "<:";
        };
        return a + " " + sign + " " + b + " from " + position;
    }
}
