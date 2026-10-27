package kotlinbootstrap.infer;

/**
 * The initial constraint a constraint was derived from.
 *
 * @param fromDeclaredUpperBound derived by incorporating a declared upper bound
 *                               of a type parameter, which makes it less proper
 */
public record IncorporationPosition(InitialConstraint initial, boolean fromDeclaredUpperBound) {
    public ConstraintPosition from() {
        return initial.position();
    }

    public ConstraintPosition.Kind fromKind() {
        return initial.position().kind();
    }
}
