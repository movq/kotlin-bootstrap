package kotlinbootstrap.symbols;

import java.util.List;
import java.util.function.Supplier;

import kotlinbootstrap.types.KType;

/** A type alias: {@code typealias Name<T> = Expansion<T>}. */
public final class TypeAliasSymbol implements ClassifierSymbol {
    private final ClassId id;
    private final Visibility visibility;
    private final List<TypeParamSymbol> typeParams;
    private final Lazy<KType> expansion;
    private final Object source;

    public TypeAliasSymbol(ClassId id, Visibility visibility, List<TypeParamSymbol> typeParams,
                           Supplier<KType> expansion, Object source) {
        this.id = id;
        this.visibility = visibility;
        this.typeParams = typeParams;
        this.expansion = new Lazy<>(expansion);
        this.source = source;
    }

    public ClassId id() {
        return id;
    }

    @Override
    public String name() {
        return id.shortName();
    }

    @Override
    public Origin origin() {
        return Origin.KOTLIN_SOURCE;
    }

    public Visibility visibility() {
        return visibility;
    }

    public List<TypeParamSymbol> typeParams() {
        return typeParams;
    }

    /** The aliased type, in terms of this alias's type parameters. */
    public KType expansion() {
        return expansion.get();
    }

    /** The declaring AST node. */
    public Object source() {
        return source;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
