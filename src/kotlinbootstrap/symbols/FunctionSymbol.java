package kotlinbootstrap.symbols;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.types.KType;

/** A function or constructor. */
public final class FunctionSymbol extends CallableSymbol {
    public static final String CONSTRUCTOR_NAME = "<init>";

    public FunctionSymbol(String name, Origin origin, Symbol owner, Visibility visibility, Modality modality,
                          Set<Modifier> modifiers, boolean isStatic, Object source,
                          Supplier<List<TypeParamSymbol>> typeParams, Supplier<Signature> signature,
                          Supplier<KType> returnType) {
        super(name, origin, owner, visibility, modality, modifiers, isStatic, source, typeParams, signature,
                returnType);
    }

    public boolean isConstructor() {
        return name().equals(CONSTRUCTOR_NAME);
    }
}
