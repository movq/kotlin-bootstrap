package kotlinbootstrap.check;

import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.KType;

/**
 * A variable local to a function body: a local {@code val} or {@code var},
 * a value parameter of the function or of a lambda, a catch parameter, or a
 * temporary the checker introduces for a desugaring. Compared by identity.
 */
public final class LocalVariable {
    public enum Kind { LOCAL, PARAMETER, CATCH_PARAMETER, TEMPORARY }

    private final String name;
    private final Kind kind;
    private final boolean isVar;
    private final ValueParamSymbol param;
    private KType type;

    /** @param param for a parameter of a function, its symbol; otherwise null */
    public LocalVariable(String name, KType type, Kind kind, boolean isVar, ValueParamSymbol param) {
        this.name = name;
        this.type = type;
        this.kind = kind;
        this.isVar = isVar;
        this.param = param;
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

    @Override
    public String toString() {
        return name;
    }
}
