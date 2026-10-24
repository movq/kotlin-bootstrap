package kotlinbootstrap.check;

import java.util.List;

import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.types.KType;

/**
 * A checked function body: of a declared function, accessor or constructor,
 * of a local function, or of a lambda or anonymous function. Compared by
 * identity; it is the target of the returns in its body, so the body is set
 * once checked.
 */
public final class CheckedFunction {
    private final CallableSymbol symbol;
    private final String label;
    private final KType receiverType;
    private final List<LocalVariable> contextParams;
    private final List<LocalVariable> params;
    private KType returnType;
    private TExpr body;

    /**
     * @param symbol       the declaration, or null for a lambda or anonymous function
     * @param label        the label returns can use ({@code return@label}), nullable
     * @param receiverType the extension receiver's type, nullable
     */
    public CheckedFunction(CallableSymbol symbol, String label, KType receiverType, List<LocalVariable> contextParams,
                           List<LocalVariable> params, KType returnType) {
        this.symbol = symbol;
        this.label = label;
        this.receiverType = receiverType;
        this.contextParams = contextParams;
        this.params = params;
        this.returnType = returnType;
    }

    public CallableSymbol symbol() {
        return symbol;
    }

    public String label() {
        return label;
    }

    public KType receiverType() {
        return receiverType;
    }

    public List<LocalVariable> contextParams() {
        return contextParams;
    }

    public List<LocalVariable> params() {
        return params;
    }

    public KType returnType() {
        return returnType;
    }

    /** For a lambda, whose return type is inferred from its body. */
    void setReturnType(KType returnType) {
        this.returnType = returnType;
    }

    /** The body, null for one without a body or not checked yet. */
    public TExpr body() {
        return body;
    }

    void setBody(TExpr body) {
        this.body = body;
    }

    @Override
    public String toString() {
        return symbol != null ? symbol.toString() : label != null ? "lambda@" + label : "lambda";
    }
}
