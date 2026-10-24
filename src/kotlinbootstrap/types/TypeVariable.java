package kotlinbootstrap.types;

import kotlinbootstrap.symbols.TypeParamSymbol;

/**
 * A variable of a constraint system: a type to be inferred, usually the
 * argument for a type parameter in a call. Compared by identity.
 *
 * @see KType.TypeVariableType
 */
public final class TypeVariable {
    private final String name;
    private final TypeParamSymbol typeParam;

    /** @param typeParam the type parameter the variable stands for an argument of, or null */
    public TypeVariable(String name, TypeParamSymbol typeParam) {
        this.name = name;
        this.typeParam = typeParam;
    }

    public String name() {
        return name;
    }

    public TypeParamSymbol typeParam() {
        return typeParam;
    }

    public KType.TypeVariableType type() {
        return new KType.TypeVariableType(this, false);
    }

    @Override
    public String toString() {
        return name;
    }
}
