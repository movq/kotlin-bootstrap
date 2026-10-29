package kotlinbootstrap.check;

/**
 * A checked delegated property: its delegate, kept in a field, and the
 * accessors that call the delegate's {@code getValue} and {@code setValue}.
 *
 * @param delegate the delegate expression, or the {@code provideDelegate} call on it
 * @param setter   for a {@code var}; null otherwise
 */
public record CheckedDelegate(TExpr delegate, CheckedFunction getter, CheckedFunction setter) {
}
