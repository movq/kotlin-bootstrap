package kotlinbootstrap.symbols;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.types.KType;

/**
 * A property, or a Java field.
 *
 * @see #setterVisibility() null for a {@code val}
 */
public final class PropertySymbol extends CallableSymbol {
    private final boolean isVar;
    private final boolean isJavaField;
    private final Visibility setterVisibility;

    public PropertySymbol(String name, Origin origin, Symbol owner, Visibility visibility, Modality modality,
                          Set<Modifier> modifiers, boolean isStatic, Object source, boolean isVar,
                          boolean isJavaField, Visibility setterVisibility,
                          Supplier<List<TypeParamSymbol>> typeParams, Supplier<Signature> signature,
                          Supplier<KType> type) {
        super(name, origin, owner, visibility, modality, modifiers, isStatic, source, typeParams, signature, type);
        this.isVar = isVar;
        this.isJavaField = isJavaField;
        this.setterVisibility = setterVisibility;
    }

    public boolean isVar() {
        return isVar;
    }

    public boolean isJavaField() {
        return isJavaField;
    }

    public Visibility setterVisibility() {
        return setterVisibility;
    }

    public KType type() {
        return returnType();
    }
}
