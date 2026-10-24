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
            collect(member, cls, Substitution.of(List.of(), List.of()), result, new HashSet<>());
        }
        return result;
    }

    private static void collect(CallableSymbol member, ClassSymbol cls, Substitution subst,
                                List<CallableSymbol> result, Set<ClassSymbol> seen) {
        for (KType t : cls.supertypes()) {
            if (!(t instanceof KType.ClassType st) || !seen.add(st.symbol())) continue;
            KType.ClassType supertype = (KType.ClassType) subst.apply(st);
            ClassSymbol sup = supertype.symbol();
            Substitution supSubst = supertype.args().size() == allTypeParams(sup).size()
                    ? Substitution.of(allTypeParams(sup), supertype.args()) : Substitution.of(List.of(), List.of());
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
        List<TypeParamSymbol> ours = member.typeParams();
        List<TypeParamSymbol> theirs = candidate.typeParams();
        if (ours.size() != theirs.size()) return false;
        List<TypeArg> oursAsArgs = new ArrayList<>();
        for (TypeParamSymbol tp : ours) oursAsArgs.add(TypeArg.of(new KType.TypeParamType(tp, false)));
        Substitution fnSubst = Substitution.of(theirs, oursAsArgs);

        CallableSymbol.Signature a = member.signature();
        CallableSymbol.Signature b = candidate.signature();
        if ((a.receiver() == null) != (b.receiver() == null)) return false;
        if (a.params().size() != b.params().size() || a.contextParams().size() != b.contextParams().size()) {
            return false;
        }
        if (a.receiver() != null && !sameType(a.receiver(), substitute(b.receiver(), classSubst, fnSubst))) {
            return false;
        }
        for (int i = 0; i < a.contextParams().size(); i++) {
            if (!sameType(a.contextParams().get(i), substitute(b.contextParams().get(i), classSubst, fnSubst))) {
                return false;
            }
        }
        for (int i = 0; i < a.params().size(); i++) {
            if (!sameType(a.params().get(i).type(), substitute(b.params().get(i).type(), classSubst, fnSubst))) {
                return false;
            }
        }
        return true;
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
            case KType.Flexible f -> throw new AssertionError();
        };
    }

    private static boolean sameFlexible(KType t, KType.Flexible f) {
        KType notNull = t instanceof KType.Flexible ? t : t.withNullable(false);
        return sameType(notNull, f.lower().withNullable(false)) || sameType(notNull, f.upper().withNullable(false));
    }

    /**
     * Raw Java types have no arguments; they match any arguments. A
     * projection that repeats the parameter's declared variance is the
     * same as none: {@code Collection<out E>} is {@code Collection<E>}.
     */
    private static boolean sameArgs(List<TypeParamSymbol> params, List<TypeArg> a, List<TypeArg> b) {
        if (a.isEmpty() || b.isEmpty()) return true;
        if (a.size() != b.size() || a.size() != params.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            TypeArg x = a.get(i);
            TypeArg y = b.get(i);
            if (x.isStar() || y.isStar()) {
                if (x.isStar() != y.isStar()) return false;
            } else if (effective(x, params.get(i)) != effective(y, params.get(i)) || !sameType(x.type(), y.type())) {
                return false;
            }
        }
        return true;
    }

    private static Variance effective(TypeArg arg, TypeParamSymbol param) {
        return arg.variance() == param.variance() ? Variance.INVARIANT : arg.variance();
    }
}
