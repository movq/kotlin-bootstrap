package kotlinbootstrap.symbols;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.types.KType;

/** A property, or a Java field. */
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

    /** Null for a {@code val}; that of the property if not given for a {@code var}. */
    public Visibility setterVisibility() {
        return isVar && setterVisibility == null ? visibility() : setterVisibility;
    }

    public KType type() {
        return returnType();
    }
}
