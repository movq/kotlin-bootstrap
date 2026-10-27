package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import kotlinbootstrap.resolve.TypeResolver;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;
import kotlinbootstrap.types.Variance;

/**
 * The type arguments of a bare type, {@code x is List} for
 * {@code x is List<T>}, from the type of the expression tested, as FIR's
 * computeRepresentativeTypeForBareType.
 */
final class BareTypes {
    private BareTypes() {
    }

    /** Whether a type is a generic class written without type arguments. */
    static boolean isBare(KType type) {
        return Types.lowerBound(type) instanceof KType.ClassType c && c.args().isEmpty()
                && !c.symbol().typeParams().isEmpty();
    }

    /** The bare type with the arguments the original type implies; null if it implies none. */
    static KType.ClassType representative(TypeSystem ts, TypeResolver types, KType.ClassType bare, KType original) {
        original = Types.lowerBound(original);
        if (original instanceof KType.Intersection i) {
            KType.ClassType first = null;
            for (KType component : i.components()) {
                KType.ClassType t = representative(ts, types, bare, component);
                if (t != null && !t.args().isEmpty()) return t;
                if (first == null) first = t;
            }
            return first;
        }
        if (!(original instanceof KType.ClassType o)) return null;
        ClassSymbol cls = bare.symbol();
        KType supertype = ts.asSupertype(types.defaultType(cls), o.symbol());
        if (supertype == null) return null;
        if (o.nullable()) supertype = supertype.withNullable(true);
        Map<TypeParamSymbol, TypeArg> result = new HashMap<>();
        if (!unify(ts, TypeArg.of(o), TypeArg.of(supertype), Set.copyOf(cls.typeParams()), result)) return null;
        List<TypeArg> args = new ArrayList<>();
        for (TypeParamSymbol tp : cls.typeParams()) {
            TypeArg arg = result.get(tp);
            if (arg == null) return null;
            args.add(arg);
        }
        return new KType.ClassType(cls, args, bare.nullable());
    }

    /**
     * As FIR's doUnify: binds the type parameters in the second type to
     * the parts of the first in the same places; false if contradictory.
     */
    private static boolean unify(TypeSystem ts, TypeArg original, TypeArg withParams, Set<TypeParamSymbol> params,
                                 Map<TypeParamSymbol, TypeArg> result) {
        KType originalType = original.type() == null ? null : Types.lowerBound(original.type());
        KType paramsType = withParams.type() == null ? null : Types.lowerBound(withParams.type());

        if (originalType instanceof KType.Intersection i) {
            Map<TypeParamSymbol, TypeArg> fromIntersection = new HashMap<>();
            for (KType component : i.components()) {
                Map<TypeParamSymbol, TypeArg> local = new HashMap<>();
                if (!unify(ts, new TypeArg(original.variance(), component), withParams, params, local)) return false;
                // A later binding replaces an earlier one only if more specific.
                local.forEach((tp, arg) -> {
                    TypeArg existing = fromIntersection.get(tp);
                    if (existing == null || !arg.isStar() && !existing.isStar()
                            && arg.variance() == Variance.INVARIANT && existing.variance() == Variance.INVARIANT
                            && ts.isSubtype(arg.type(), existing.type())) {
                        fromIntersection.put(tp, arg);
                    }
                });
            }
            result.putAll(fromIntersection);
            return true;
        }
        // in Foo ~ in X, out Foo ~ out X
        if (!original.isStar() && !withParams.isStar() && original.variance() == withParams.variance()
                && original.variance() != Variance.INVARIANT) {
            return unify(ts, TypeArg.of(originalType), TypeArg.of(paramsType), params, result);
        }
        // Foo? ~ X?
        if (originalType != null && paramsType != null && Types.isMarkedNullable(originalType)
                && Types.isMarkedNullable(paramsType)) {
            return unify(ts, new TypeArg(original.variance(), originalType.withNullable(false)),
                    new TypeArg(withParams.variance(), paramsType.withNullable(false)), params, result);
        }
        // in Foo ~ out X
        if (original.variance() != withParams.variance()
                && withParams.variance() != Variance.INVARIANT) {
            return true;
        }
        if (paramsType instanceof KType.DefinitelyNotNull d) {
            return unify(ts, original, new TypeArg(withParams.variance(), d.inner()), params, result);
        }
        // Foo ~ X?
        if (!original.isStar() && originalType != null && !Types.isMarkedNullable(originalType)
                && paramsType != null && Types.isMarkedNullable(paramsType)) {
            return true;
        }
        // Foo ~ X  =>  X := Foo
        if (paramsType instanceof KType.TypeParamType p && params.contains(p.symbol())) {
            if (result.containsKey(p.symbol()) && !result.get(p.symbol()).equals(original)) return false;
            result.put(p.symbol(), original);
            return true;
        }
        if (originalType == null || paramsType == null) return true;
        if (Types.isMarkedNullable(originalType) != Types.isMarkedNullable(paramsType)) return true;
        if (original.variance() != withParams.variance()) return true;
        if (!(originalType instanceof KType.ClassType o) || !(paramsType instanceof KType.ClassType w)
                || !Objects.equals(o.symbol(), w.symbol())) {
            return true;
        }
        if (o.args().size() != w.args().size()) return true;
        for (int i = 0; i < o.args().size(); i++) {
            if (!unify(ts, o.args().get(i), w.args().get(i), params, result)) return false;
        }
        return true;
    }
}
