package kotlinbootstrap.types;

import java.util.List;

import kotlinbootstrap.symbols.TypeParamSymbol;

/**
 * The identity of a captured type: one projection of one type argument,
 * captured at one place. Compared by identity.
 */
public final class CapturedTypeConstructor {
    /** Why the type was captured; types captured for subtyping are approximated before they leak into results. */
    public enum Status { FOR_SUBTYPING, FROM_EXPRESSION, FOR_INCORPORATION }

    private final TypeArg projection;
    private final TypeParamSymbol typeParam;
    private final Status status;
    private List<KType> supertypes;

    public CapturedTypeConstructor(TypeArg projection, TypeParamSymbol typeParam, Status status) {
        this.projection = projection;
        this.typeParam = typeParam;
        this.status = status;
    }

    public TypeArg projection() {
        return projection;
    }

    /** The type parameter the projection is an argument for, or null. */
    public TypeParamSymbol typeParam() {
        return typeParam;
    }

    public Status status() {
        return status;
    }

    /**
     * The upper bounds: the projected type of an {@code out} projection and
     * the parameter's bounds (substituted). Set once after creation, since
     * the bounds can mention the captured type itself.
     */
    public List<KType> supertypes() {
        return supertypes;
    }

    public void setSupertypes(List<KType> supertypes) {
        this.supertypes = supertypes;
    }

    @Override
    public String toString() {
        return "Captured(" + projection + ")";
    }
}
