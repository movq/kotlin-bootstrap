package kotlinbootstrap.ast;

/**
 * A call argument, e.g. {@code name = *value}.
 *
 * @param name argument name, nullable
 */
public record ValueArg(int pos, String name, boolean spread, Expr value) implements Node {
}
