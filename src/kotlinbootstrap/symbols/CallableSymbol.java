package kotlinbootstrap.symbols;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.types.KType;

/**
 * A function or property. The header is known up front; type parameters
 * and types are computed on demand.
 */
public abstract sealed class CallableSymbol implements Symbol permits FunctionSymbol, PropertySymbol {
    /** The receiver and parameter types. */
    public record Signature(KType receiver, List<KType> contextParams, List<ValueParamSymbol> params) {
        public static final Signature NONE = new Signature(null, List.of(), List.of());
    }

    private final String name;
    private final Origin origin;
    private final Symbol owner;
    private Visibility visibility;
    private final Modality modality;
    private final Set<Modifier> modifiers;
    private final boolean isStatic;
    private final Object source;
    private final Lazy<List<TypeParamSymbol>> typeParams;
    private final Lazy<Signature> signature;
    private final Lazy<KType> returnType;

    /**
     * @param owner      the containing class, or the {@link PackageSymbol} of a
     *                   top-level declaration; null for a local declaration
     * @param visibility null for an override without a visibility modifier,
     *                   which has that of the member it overrides
     * @param isStatic   a Java static member
     * @param source     the declaring AST node or element
     * @param returnType for a property, its type
     */
    protected CallableSymbol(String name, Origin origin, Symbol owner, Visibility visibility, Modality modality,
                             Set<Modifier> modifiers, boolean isStatic, Object source,
                             Supplier<List<TypeParamSymbol>> typeParams, Supplier<Signature> signature,
                             Supplier<KType> returnType) {
        this.name = name;
        this.origin = origin;
        this.owner = owner;
        this.visibility = visibility;
        this.modality = modality;
        this.modifiers = modifiers;
        this.isStatic = isStatic;
        this.source = source;
        this.typeParams = new Lazy<>(typeParams);
        this.signature = new Lazy<>(signature);
        this.returnType = new Lazy<>(returnType);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Origin origin() {
        return origin;
    }

    public Symbol owner() {
        return owner;
    }

    public Visibility visibility() {
        if (visibility == null) {
            List<CallableSymbol> overridden = Overrides.overridden(this);
            visibility = overridden.isEmpty() ? Visibility.PUBLIC : overridden.getFirst().visibility();
        }
        return visibility;
    }

    public Modality modality() {
        return modality;
    }

    public Set<Modifier> modifiers() {
        return modifiers;
    }

    public boolean has(Modifier m) {
        return modifiers.contains(m);
    }

    public boolean isStatic() {
        return isStatic;
    }

    public Object source() {
        return source;
    }

    public List<TypeParamSymbol> typeParams() {
        return typeParams.get();
    }

    public Signature signature() {
        return signature.get();
    }

    public KType returnType() {
        return returnType.get();
    }

    @Override
    public String toString() {
        return (owner == null ? "" : owner + ".") + name;
    }
}
