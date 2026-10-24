package kotlinbootstrap.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kotlinbootstrap.symbols.TypeParamSymbol;

/**
 * Replaces type parameters and type variables in a type by type
 * arguments.
 */
public final class Substitution {
    public static final Substitution EMPTY = new Substitution(Map.of());

    /** Keys are {@link TypeParamSymbol}s and {@link TypeVariable}s. */
    private final Map<Object, TypeArg> map;

    public Substitution(Map<?, TypeArg> map) {
        this.map = Map.copyOf(map);
    }

    /** Pairs up parameters with arguments, which must be as many. */
    public static Substitution of(List<TypeParamSymbol> params, List<TypeArg> args) {
        if (params.size() != args.size()) throw new IllegalArgumentException(params + " vs " + args);
        if (params.isEmpty()) return EMPTY;
        Map<Object, TypeArg> map = new HashMap<>();
        for (int i = 0; i < params.size(); i++) map.put(params.get(i), args.get(i));
        return new Substitution(map);
    }

    /** Maps each parameter to the type. */
    public static Substitution ofTypes(List<TypeParamSymbol> params, List<? extends KType> types) {
        return of(params, types.stream().map(TypeArg::of).toList());
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    /** This substitution followed by the other one, for the types this one maps to; plus the other's own keys. */
    public Substitution andThen(Substitution other) {
        if (other.isEmpty()) return this;
        Map<Object, TypeArg> result = new HashMap<>(other.map);
        map.forEach((k, v) -> result.put(k, other.applyArg(v)));
        return new Substitution(result);
    }

    public TypeArg get(Object key) {
        return map.get(key);
    }

    /**
     * Substitutes into a type in a position where a projection cannot occur
     * (not a type argument). A star or projected argument there is an error.
     */
    public KType apply(KType type) {
        if (map.isEmpty()) return type;
        TypeArg arg = applyArg(TypeArg.of(type));
        if (arg.isStar() || arg.variance() != Variance.INVARIANT) {
            throw new IllegalArgumentException("Projection " + arg + " substituted for " + type);
        }
        return arg.type();
    }

    public List<KType> applyAll(List<KType> types) {
        if (map.isEmpty()) return types;
        return types.stream().map(this::apply).toList();
    }

    public TypeArg applyArg(TypeArg arg) {
        if (arg.isStar() || map.isEmpty()) return arg;
        return switch (arg.type()) {
            case KType.TypeParamType t -> replace(arg, map.get(t.symbol()), t.nullable());
            case KType.TypeVariableType t -> replace(arg, map.get(t.variable()), t.nullable());
            case KType.ClassType c -> {
                List<TypeArg> args = new ArrayList<>(c.args().size());
                boolean changed = false;
                for (TypeArg a : c.args()) {
                    TypeArg s = applyArg(a);
                    changed |= s != a;
                    args.add(s);
                }
                yield !changed ? arg : new TypeArg(arg.variance(),
                        new KType.ClassType(c.symbol(), args, c.nullable(), c.extensionFunction(), c.contextParams()));
            }
            case KType.DefinitelyNotNull d -> {
                TypeArg replacement = applyArg(TypeArg.of(d.inner()));
                if (replacement.isStar()) yield replacement;
                if (replacement.type() == d.inner()) yield arg;
                yield projection(combine(arg.variance(), replacement.variance()),
                        Types.makeDefinitelyNotNull(replacement.type()));
            }
            case KType.Flexible f -> {
                KType lower = apply(f.lower());
                KType upper = apply(f.upper());
                yield lower == f.lower() && upper == f.upper() ? arg
                        : new TypeArg(arg.variance(), Types.flexible(lower, upper));
            }
            case KType.Intersection i -> new TypeArg(arg.variance(), Types.intersect(applyAll(i.components())));
            case KType.Captured c -> arg;
            case KType.IntegerLiteral l -> arg;
        };
    }

    private static TypeArg replace(TypeArg arg, TypeArg replacement, boolean nullable) {
        if (replacement == null) return arg;
        if (replacement.isStar()) return replacement;
        KType type = nullable ? Types.makeNullable(replacement.type()) : replacement.type();
        return projection(combine(arg.variance(), replacement.variance()), type);
    }

    private static TypeArg projection(Variance variance, KType type) {
        return variance == null ? TypeArg.STAR : new TypeArg(variance, type);
    }

    /** {@code out (out T)} is {@code out T}; {@code in (out T)} is not representable (null), so a star. */
    private static Variance combine(Variance outer, Variance inner) {
        if (outer == Variance.INVARIANT) return inner;
        if (inner == Variance.INVARIANT || inner == outer) return outer;
        return null;
    }

    @Override
    public String toString() {
        return map.toString();
    }
}
