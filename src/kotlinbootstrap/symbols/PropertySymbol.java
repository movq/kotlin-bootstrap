package kotlinbootstrap.symbols;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.types.KType;

/** A property, a Java field, or a Java getter and setter seen as a property. */
public final class PropertySymbol extends CallableSymbol {
    private final boolean isVar;
    private final boolean isJavaField;
    private final boolean isEnumEntry;
    private final Visibility setterVisibility;
    private FunctionSymbol javaGetter;
    private FunctionSymbol javaSetter;

    public PropertySymbol(String name, Origin origin, Symbol owner, Visibility visibility, Modality modality,
                          Set<Modifier> modifiers, boolean isStatic, Object source, boolean isVar,
                          boolean isJavaField, boolean isEnumEntry, Visibility setterVisibility,
                          Supplier<List<TypeParamSymbol>> typeParams, Supplier<Signature> signature,
                          Supplier<KType> type) {
        super(name, origin, owner, visibility, modality, modifiers, isStatic, source, typeParams, signature, type);
        this.isVar = isVar;
        this.isJavaField = isJavaField;
        this.isEnumEntry = isEnumEntry;
        this.setterVisibility = setterVisibility;
    }

    /**
     * A synthetic Java property, as kotlinc's: {@code x.foo} for
     * {@code x.getFoo()}, and {@code x.foo = v} for {@code x.setFoo(v)}.
     *
     * @param setter nullable
     */
    public static PropertySymbol syntheticJavaProperty(String name, FunctionSymbol getter, FunctionSymbol setter) {
        PropertySymbol p = new PropertySymbol(name, Origin.SYNTHETIC, getter.owner(), getter.visibility(),
                getter.modality(), Set.of(), false, null, setter != null, false, false,
                setter == null ? null : setter.visibility(), List::of, () -> Signature.NONE, getter::returnType);
        p.javaGetter = getter;
        p.javaSetter = setter;
        return p;
    }

    /** For a synthetic Java property, its getter; otherwise null. */
    public FunctionSymbol javaGetter() {
        return javaGetter;
    }

    /** For a synthetic Java property with a setter, the setter; otherwise null. */
    public FunctionSymbol javaSetter() {
        return javaSetter;
    }

    public boolean isVar() {
        return isVar;
    }

    public boolean isJavaField() {
        return isJavaField;
    }

    /** An entry of a Kotlin enum class, or a constant of a Java enum. */
    public boolean isEnumEntry() {
        return isEnumEntry;
    }

    /** Null for a {@code val}; that of the property if not given for a {@code var}. */
    public Visibility setterVisibility() {
        return isVar && setterVisibility == null ? visibility() : setterVisibility;
    }

    public KType type() {
        return returnType();
    }
}
