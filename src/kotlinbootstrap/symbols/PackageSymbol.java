package kotlinbootstrap.symbols;

/**
 * A package, as the owner of top-level declarations. Its contents live in
 * the {@link SymbolTable}, since they come from several sources.
 */
public record PackageSymbol(String name) implements Symbol {
    @Override
    public Origin origin() {
        return Origin.SYNTHETIC;
    }

    @Override
    public String toString() {
        return name;
    }
}
