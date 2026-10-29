package kotlinbootstrap.check;

import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.KType;

/**
 * A variable local to a function body: a local {@code val} or {@code var},
 * a value parameter of the function or of a lambda, a catch parameter, a
 * temporary the checker introduces for a desugaring, {@code field} in a
 * property accessor, or a local delegated property. Compared by identity.
 */
public final class LocalVariable {
    public enum Kind { LOCAL, PARAMETER, CATCH_PARAMETER, TEMPORARY, BACKING_FIELD, DELEGATED }

    private final String name;
    private final Kind kind;
    private final boolean isVar;
    private final ValueParamSymbol param;
    private final PropertySymbol property;
    private KType type;

    /** @param param for a parameter of a function, its symbol; otherwise null */
    public LocalVariable(String name, KType type, Kind kind, boolean isVar, ValueParamSymbol param) {
        this(name, type, kind, isVar, param, null);
    }

    private LocalVariable(String name, KType type, Kind kind, boolean isVar, ValueParamSymbol param,
                          PropertySymbol property) {
        this.name = name;
        this.type = type;
        this.kind = kind;
        this.isVar = isVar;
        this.param = param;
        this.property = property;
    }

    /** {@code field}, the backing field of the property, in its accessors. */
    static LocalVariable backingField(PropertySymbol property, KType type) {
        return new LocalVariable("field", type, Kind.BACKING_FIELD, true, null, property);
    }

    /** A local delegated property, with the symbol its accessors and {@code ::name} refer to. */
    static LocalVariable delegated(PropertySymbol property, KType type) {
        return new LocalVariable(property.name(), type, Kind.DELEGATED, property.isVar(), null, property);
    }

    public String name() {
        return name;
    }

    public KType type() {
        return type;
    }

    /** For a variable whose type is only known after its declaration is checked, e.g. a lambda parameter. */
    void setType(KType type) {
        this.type = type;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isVar() {
        return isVar;
    }

    public ValueParamSymbol param() {
        return param;
    }

    /** For a backing field or a local delegated property, its property; otherwise null. */
    public PropertySymbol property() {
        return property;
    }

    @Override
    public String toString() {
        return name;
    }
}
