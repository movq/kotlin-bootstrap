package kotlinbootstrap.ast;

/**
 * A type with an optional name: a function type parameter ({@code (x: Int) -> Unit}),
 * or a context parameter ({@code context(x: Foo)}, or the older {@code context(Foo)}).
 *
 * @param name nullable
 */
public record NamedType(int pos, String name, TypeRef type) implements Node {
}
