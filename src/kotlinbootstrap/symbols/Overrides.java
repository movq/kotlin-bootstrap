package kotlinbootstrap.symbols;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.Variance;

/**
 * Finds the members of supertypes that a member overrides: those with the
 * same name and kind whose receiver and parameter types are the same once
 * the supertype's arguments are substituted. Java types match a Kotlin
 * type if either bound of the flexible type does, ignoring nullability.
 */
public final class Overrides {
    private Overrides() {
    }

    /** The members that a member of a class directly or indirectly overrides, nearest first. */
    public static List<CallableSymbol> overridden(CallableSymbol member) {
        List<CallableSymbol> result = new ArrayList<>();
        if (member.owner() instanceof ClassSymbol cls && !member.isStatic()) {
            collect(member, cls, Substitution.EMPTY, result, new HashSet<>());
        }
        return result;
    }

    /** Whether a parameter has a default value, declared or in a function the function overrides. */
    public static boolean hasDefaultValue(FunctionSymbol f, int index) {
        if (f.signature().params().get(index).hasDefault()) return true;
        for (CallableSymbol o : overridden(f)) {
            if (o instanceof FunctionSymbol of && index < of.signature().params().size() && hasDefaultValue(of, index)) {
                return true;
            }
        }
        return false;
    }

    private static void collect(CallableSymbol member, ClassSymbol cls, Substitution subst,
                                List<CallableSymbol> result, Set<ClassSymbol> seen) {
        for (KType t : cls.supertypes()) {
            if (!(t instanceof KType.ClassType st) || !seen.add(st.symbol())) continue;
            KType.ClassType supertype = (KType.ClassType) subst.apply(st);
            ClassSymbol sup = supertype.symbol();
            Substitution supSubst = supertype.args().size() == allTypeParams(sup).size()
                    ? Substitution.of(allTypeParams(sup), supertype.args()) : Substitution.EMPTY;
            boolean found = false;
            for (CallableSymbol candidate : candidates(sup, member)) {
                if (matches(member, candidate, supSubst)) {
                    result.add(candidate);
                    found = true;
                }
            }
            if (!found) collect(member, sup, supSubst, result, seen);
        }
    }

    private static List<? extends CallableSymbol> candidates(ClassSymbol cls, CallableSymbol member) {
        List<? extends CallableSymbol> named = member instanceof FunctionSymbol
                ? cls.members().functions(member.name()) : cls.members().properties(member.name());
        return named.stream().filter(c -> !c.isStatic() && c.visibility() != Visibility.PRIVATE).toList();
    }

    /** The type parameters of a class followed by those of its outer classes, if inner. */
    public static List<TypeParamSymbol> allTypeParams(ClassSymbol cls) {
        List<TypeParamSymbol> result = new ArrayList<>();
        for (ClassSymbol c = cls; c != null; c = c.isInner() ? c.outer() : null) result.addAll(c.typeParams());
        return result;
    }

    private static boolean matches(CallableSymbol member, CallableSymbol candidate, Substitution classSubst) {
        return sameSignature(member, Substitution.EMPTY, candidate, classSubst);
    }

    /**
     * Whether two members of the same kind have the same receiver and
     * parameter types, each with its class's type parameters substituted.
     */
    public static boolean sameSignature(CallableSymbol a, Substitution aSubst, CallableSymbol b, Substitution bSubst) {
        if ((a instanceof FunctionSymbol) != (b instanceof FunctionSymbol)) return false;
        List<TypeParamSymbol> ours = a.typeParams();
        List<TypeParamSymbol> theirs = b.typeParams();
        if (ours.size() != theirs.size()) return false;
        List<TypeArg> oursAsArgs = new ArrayList<>();
        for (TypeParamSymbol tp : ours) oursAsArgs.add(TypeArg.of(new KType.TypeParamType(tp, false)));
        Substitution fnSubst = Substitution.of(theirs, oursAsArgs);
        for (int i = 0; i < ours.size(); i++) {
            if (!sameBounds(ours.get(i), aSubst, theirs.get(i), bSubst, fnSubst)) return false;
        }

        CallableSymbol.Signature sa = a.signature();
        CallableSymbol.Signature sb = b.signature();
        if ((sa.receiver() == null) != (sb.receiver() == null)) return false;
        if (sa.params().size() != sb.params().size() || sa.contextParams().size() != sb.contextParams().size()) {
            return false;
        }
        if (sa.receiver() != null
                && !sameType(aSubst.apply(sa.receiver()), substitute(sb.receiver(), bSubst, fnSubst))) {
            return false;
        }
        for (int i = 0; i < sa.contextParams().size(); i++) {
            if (!sameType(aSubst.apply(sa.contextParams().get(i)),
                    substitute(sb.contextParams().get(i), bSubst, fnSubst))) {
                return false;
            }
        }
        for (int i = 0; i < sa.params().size(); i++) {
            // A vararg parameter has an array type.
            if (sa.params().get(i).isVararg() != sb.params().get(i).isVararg()) return false;
            if (!sameType(aSubst.apply(sa.params().get(i).type()),
                    substitute(sb.params().get(i).type(), bSubst, fnSubst))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether type parameters have the same bounds, in any order, as FIR's
     * isEqualBound: functions differing only in them are overloads.
     */
    private static boolean sameBounds(TypeParamSymbol a, Substitution aSubst, TypeParamSymbol b, Substitution bSubst,
                                      Substitution fnSubst) {
        List<KType> ours = a.bounds().stream().map(aSubst::apply).toList();
        List<KType> theirs = b.bounds().stream().map(t -> substitute(t, bSubst, fnSubst)).toList();
        return ours.stream().allMatch(t -> theirs.stream().anyMatch(u -> sameType(t, u)))
                && theirs.stream().allMatch(t -> ours.stream().anyMatch(u -> sameType(t, u)));
    }

    private static KType substitute(KType type, Substitution classSubst, Substitution fnSubst) {
        return fnSubst.apply(classSubst.apply(type));
    }

    /** Whether two parameter types are the same for overriding. */
    static boolean sameType(KType a, KType b) {
        if (a instanceof KType.Flexible f) return sameFlexible(b, f);
        if (b instanceof KType.Flexible f) return sameFlexible(a, f);
        if (a.nullable() != b.nullable()) return false;
        return switch (a) {
            case KType.ClassType ca -> b instanceof KType.ClassType cb && ca.symbol() == cb.symbol()
                    && sameArgs(allTypeParams(ca.symbol()), ca.args(), cb.args());
            case KType.TypeParamType ta -> b instanceof KType.TypeParamType tb && ta.symbol() == tb.symbol();
            case KType.DefinitelyNotNull da -> b instanceof KType.DefinitelyNotNull db
                    && sameType(da.inner(), db.inner());
            default -> a.equals(b);
        };
    }

    private static boolean sameFlexible(KType t, KType.Flexible f) {
        KType notNull = t instanceof KType.Flexible ? t : t.withNullable(false);
        return sameType(notNull, f.lower().withNullable(false)) || sameType(notNull, f.upper().withNullable(false));
    }

    /**
     * Raw Java types have no arguments; they match any arguments. A
     * projection that repeats the parameter's declared variance is the
     * same as none: {@code Collection<out E>} is {@code Collection<E>}. A
     * star is the same as {@code out} the parameter's bound, as they are equal types.
     */
    private static boolean sameArgs(List<TypeParamSymbol> params, List<TypeArg> a, List<TypeArg> b) {
        if (a.isEmpty() || b.isEmpty()) return true;
        if (a.size() != b.size() || a.size() != params.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            TypeArg x = a.get(i);
            TypeArg y = b.get(i);
            if (x.isStar() && y.isStar()) {
                continue;
            } else if (x.isStar() || y.isStar()) {
                if (!isOutBound(x.isStar() ? y : x, params.get(i))) return false;
            } else if (effective(x, params.get(i)) != effective(y, params.get(i)) || !sameType(x.type(), y.type())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOutBound(TypeArg arg, TypeParamSymbol param) {
        if (arg.variance() != Variance.OUT && param.variance() != Variance.OUT) return false;
        List<KType> bounds = param.bounds();
        return bounds.size() == 1 ? sameType(arg.type(), bounds.getFirst())
                : bounds.isEmpty() && arg.type() instanceof KType.ClassType c && c.nullable()
                        && c.args().isEmpty() && c.symbol().id().fqName().equals("kotlin.Any");
    }

    private static Variance effective(TypeArg arg, TypeParamSymbol param) {
        return arg.variance() == param.variance() ? Variance.INVARIANT : arg.variance();
    }
}
