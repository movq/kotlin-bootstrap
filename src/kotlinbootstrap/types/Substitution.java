package kotlinbootstrap.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kotlinbootstrap.symbols.TypeParamSymbol;

/** Replaces type parameters in a type by type arguments. */
public final class Substitution {
    private final Map<TypeParamSymbol, TypeArg> map;

    public Substitution(Map<TypeParamSymbol, TypeArg> map) {
        this.map = map;
    }

    /** Pairs up parameters with arguments, which must be as many. */
    public static Substitution of(List<TypeParamSymbol> params, List<TypeArg> args) {
        if (params.size() != args.size()) throw new IllegalArgumentException(params + " vs " + args);
        Map<TypeParamSymbol, TypeArg> map = new HashMap<>();
        for (int i = 0; i < params.size(); i++) map.put(params.get(i), args.get(i));
        return new Substitution(map);
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    /**
     * Substitutes into a type in a position where a projection cannot occur
     * (not a type argument). A star or projected argument there is an error.
     */
    public KType apply(KType type) {
        TypeArg arg = applyArg(TypeArg.of(type));
        if (arg.isStar() || arg.variance() != Variance.INVARIANT) {
            throw new IllegalArgumentException("Projection " + arg + " substituted for " + type);
        }
        return arg.type();
    }

    private TypeArg applyArg(TypeArg arg) {
        if (arg.isStar()) return arg;
        return switch (arg.type()) {
            case KType.TypeParamType t -> {
                TypeArg replacement = map.get(t.symbol());
                if (replacement == null) yield arg;
                if (replacement.isStar()) yield replacement;
                KType type = t.nullable() ? replacement.type().withNullable(true) : replacement.type();
                yield projection(combine(arg.variance(), replacement.variance()), type);
            }
            case KType.ClassType c -> {
                List<TypeArg> args = new ArrayList<>(c.args().size());
                for (TypeArg a : c.args()) args.add(applyArg(a));
                yield new TypeArg(arg.variance(),
                        new KType.ClassType(c.symbol(), args, c.nullable(), c.extensionFunction(), c.contextParams()));
            }
            case KType.DefinitelyNotNull d -> {
                TypeArg replacement = applyArg(TypeArg.of(d.inner()));
                if (replacement.isStar()) yield replacement;
                KType type = replacement.type() instanceof KType.TypeParamType tp
                        ? new KType.DefinitelyNotNull(tp.withNullable(false))
                        : replacement.type().withNullable(false);
                yield projection(combine(arg.variance(), replacement.variance()), type);
            }
            case KType.Flexible f -> new TypeArg(arg.variance(), new KType.Flexible(apply(f.lower()), apply(f.upper())));
        };
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
}
