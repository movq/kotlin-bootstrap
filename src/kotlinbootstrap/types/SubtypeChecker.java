package kotlinbootstrap.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.TypeParamSymbol;

/**
 * Decides subtyping, as kotlinc's AbstractTypeChecker with a
 * TypeCheckerState. A constraint system subclasses this to record
 * constraints on type variables met along the way instead of failing.
 */
public class SubtypeChecker {
    /** For a supertype {@code Captured(in X)}: whether to check against X. */
    public enum LowerCapturedTypePolicy { CHECK_ONLY_LOWER, CHECK_SUBTYPE_AND_LOWER, SKIP_LOWER }

    protected final TypeSystem ts;
    private int argumentsDepth;

    public SubtypeChecker(TypeSystem ts) {
        this.ts = ts;
    }

    // ==== Hooks ====

    /**
     * Called for each subtyping question that involves a type variable (and
     * some others); a non-null answer overrides the regular algorithm.
     */
    protected Boolean addSubtypeConstraint(KType sub, KType sup, boolean fromNullability) {
        return null;
    }

    protected LowerCapturedTypePolicy lowerCapturedTypePolicy(KType sub, KType.Captured sup) {
        return LowerCapturedTypePolicy.CHECK_SUBTYPE_AND_LOWER;
    }

    /**
     * When a subtype has several supertypes with the class of the supertype,
     * e.g. {@code A<Int> & A<T> <: A<X>}: by default, whether any of them works.
     */
    protected boolean runForkingPoint(List<BooleanSupplier> forks) {
        for (BooleanSupplier f : forks) {
            if (f.getAsBoolean()) return true;
        }
        return false;
    }

    // ==== Subtyping ====

    public boolean isSubtype(KType sub, KType sup) {
        return isSubtype(sub, sup, false);
    }

    public boolean isSubtype(KType sub, KType sup, boolean fromNullability) {
        if (sub == sup) return true;
        KType subLower = Types.lowerBound(sub);
        KType supUpper = Types.upperBound(sup);
        Boolean special = checkSpecialCases(subLower, supUpper);
        if (special != null) {
            addSubtypeConstraint(sub, sup, fromNullability);
            return special;
        }
        // Constraints keep flexible types, otherwise inference never yields a flexible type.
        Boolean constraint = addSubtypeConstraint(sub, sup, fromNullability);
        if (constraint != null) return constraint;
        return isSubtypeOfSingleClassifier(subLower, supUpper);
    }

    public boolean equalTypes(KType a, KType b) {
        if (a == b) return true;
        if (isCommonDenotable(a) && isCommonDenotable(b)) {
            KType lowerA = Types.lowerBound(a);
            if (!constructor(a).equals(constructor(b))) return false;
            if (parameterCount(constructor(a)) == 0) {
                if (hasFlexibleNullability(a) || hasFlexibleNullability(b)) return true;
                return lowerA.nullable() == Types.lowerBound(b).nullable();
            }
        }
        return isSubtype(a, b) && isSubtype(b, a);
    }

    private boolean isCommonDenotable(KType t) {
        Object c = constructor(t);
        if (!(c instanceof ClassSymbol || c instanceof TypeParamSymbol)) return false;
        if (t instanceof KType.DefinitelyNotNull) return false;
        return !(t instanceof KType.Flexible f) || constructor(f.lower()).equals(constructor(f.upper()));
    }

    private static boolean hasFlexibleNullability(KType t) {
        return Types.lowerBound(t).nullable() != Types.upperBound(t).nullable();
    }

    private Boolean checkSpecialCases(KType sub, KType sup) {
        KType.Captured supCaptured = capturedUnwrappingDnn(sup);
        if (supCaptured != null && supCaptured.lowerType() != null) {
            // Foo? <: Captured(in Foo)? is checked as Foo? <: Foo?.
            KType lower = supCaptured.lowerType();
            KType nullableLower = sup.nullable() ? lower.withNullable(true)
                    : sup instanceof KType.DefinitelyNotNull ? Types.makeDefinitelyNotNull(lower) : lower;
            switch (lowerCapturedTypePolicy(sub, supCaptured)) {
                case CHECK_ONLY_LOWER -> {
                    return isSubtype(sub, nullableLower);
                }
                case CHECK_SUBTYPE_AND_LOWER -> {
                    if (isSubtype(sub, nullableLower)) return true;
                }
                case SKIP_LOWER -> {
                }
            }
        }

        if (sup instanceof KType.Intersection i) {
            for (KType c : i.components()) {
                if (!isSubtype(sub, c)) return false;
            }
            return true;
        }

        // Captured(out Bar) <: Foo<Captured(out Bar)> for a self type Foo<E : Foo<E>>; checking it
        // the regular way may not terminate.
        if (sub instanceof KType.Captured || sub instanceof KType.Intersection i
                && i.components().stream().allMatch(c -> c instanceof KType.Captured)) {
            TypeParamSymbol tp = typeParamForArgumentEqualTo(sup, sub);
            if (tp != null && hasRecursiveBounds(tp, constructor(sup))) return true;
        }
        return null;
    }

    private static KType.Captured capturedUnwrappingDnn(KType t) {
        if (t instanceof KType.DefinitelyNotNull d) t = d.inner();
        return t instanceof KType.Captured c ? c : null;
    }

    private static TypeParamSymbol typeParamForArgumentEqualTo(KType base, KType target) {
        if (!(base instanceof KType.ClassType c)) return null;
        List<TypeParamSymbol> params = Overrides.allTypeParams(c.symbol());
        for (int i = 0; i < c.args().size() && i < params.size(); i++) {
            TypeArg arg = c.args().get(i);
            if (arg.isStar()) continue;
            KType argLower = Types.lowerBound(arg.type());
            boolean bothCaptured = argLower instanceof KType.Captured && Types.lowerBound(target) instanceof KType.Captured;
            if (arg.type().equals(target) || bothCaptured && constructor(arg.type()).equals(constructor(target))) {
                return params.get(i);
            }
            TypeParamSymbol nested = typeParamForArgumentEqualTo(arg.type(), target);
            if (nested != null) return nested;
        }
        return null;
    }

    /** Whether a bound of the parameter mentions it, e.g. {@code E : Enum<E>}, and has the given constructor if any. */
    static boolean hasRecursiveBounds(TypeParamSymbol tp, Object selfConstructor) {
        for (KType bound : tp.bounds()) {
            if (Types.contains(bound, t -> t instanceof KType.TypeParamType p && p.symbol() == tp)
                    && (selfConstructor == null || constructor(bound).equals(selfConstructor))) {
                return true;
            }
        }
        return false;
    }

    private boolean isSubtypeOfSingleClassifier(KType sub, KType sup) {
        if (!isPossibleSubtype(sub, sup)) return false;

        Boolean literal = checkIntegerLiteral(sub, sup);
        if (literal != null) {
            addSubtypeConstraint(sub, sup, false);
            return literal;
        }

        Object supConstructor = constructor(sup);
        if (constructor(sub).equals(supConstructor) && parameterCount(supConstructor) == 0) return true;
        if (supConstructor == ts.anyClass()) return true;

        List<KType> supertypes = new ArrayList<>(new LinkedHashSet<>(findCorrespondingSupertypes(sub, supConstructor)));
        if (supertypes.isEmpty()) return hasNothingSupertype(sub);
        // A type parameter or the like, without arguments to compare.
        if (!(sup instanceof KType.ClassType supClass)) return true;
        return switch (supertypes.size()) {
            case 1 -> isSubtypeForSameConstructor(((KType.ClassType) supertypes.getFirst()).args(), supClass);
            default -> {
                if (isSubtypeWithIntersectedArguments(supClass, supertypes)) yield true;
                List<BooleanSupplier> forks = new ArrayList<>();
                for (KType s : supertypes) forks.add(() -> isSubtypeForSameConstructor(((KType.ClassType) s).args(), supClass));
                yield runForkingPoint(forks);
            }
        };
    }

    /** {@code A <: List<Int>, A <: List<Double>} gives {@code A <: List<Int & Double>}, for out parameters only. */
    private boolean isSubtypeWithIntersectedArguments(KType.ClassType sup, List<KType> supertypes) {
        List<TypeParamSymbol> params = Overrides.allTypeParams(sup.symbol());
        List<TypeArg> args = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).variance() != Variance.OUT) return false;
            List<KType> types = new ArrayList<>();
            for (KType s : supertypes) {
                TypeArg a = ((KType.ClassType) s).args().get(i);
                if (a.isStar() || a.variance() != Variance.INVARIANT) {
                    throw new IllegalStateException("Incorrect type " + s + " for " + sup);
                }
                types.add(a.type());
            }
            args.add(TypeArg.of(ts.intersect(types)));
        }
        return isSubtypeForSameConstructor(args, sup);
    }

    private boolean isPossibleSubtype(KType sub, KType sup) {
        if (sup.nullable()) return true;
        if (sub instanceof KType.DefinitelyNotNull) return true;
        if (hasNotNullSupertype(sub, Types::lowerBound)) return true;
        if (sup instanceof KType.DefinitelyNotNull) return false;
        if (hasNotNullSupertype(sup, Types::upperBound)) return false;
        if (sub instanceof KType.ClassType) return false;
        return hasPathByNotMarkedNullableNodes(sub, constructor(sup));
    }

    private boolean hasNotNullSupertype(KType type, UnaryOperator<KType> policy) {
        return anySupertype(type, t -> t instanceof KType.ClassType c && !c.nullable() || t instanceof KType.DefinitelyNotNull,
                t -> t.nullable() ? null : policy);
    }

    /** Whether some chain of non-nullable supertypes leads to the constructor (or to Nothing). */
    boolean hasPathByNotMarkedNullableNodes(KType start, Object end) {
        return anySupertype(start,
                t -> ts.isNothing(t) || !t.nullable() && constructor(t).equals(end),
                t -> t.nullable() ? null : Types::lowerBound);
    }

    private Boolean checkIntegerLiteral(KType sub, KType sup) {
        if (!(sub instanceof KType.IntegerLiteral) && !(sup instanceof KType.IntegerLiteral)) return null;
        if (isIntegerLiteralOrCaptured(sub) && isIntegerLiteralOrCaptured(sup)) return true;
        if (sub instanceof KType.IntegerLiteral l) {
            if (isTypeInIntegerLiteral(l, sup, false)) return true;
        } else if (sup instanceof KType.IntegerLiteral l) {
            // Also true for intersections such as {Int & String} <: ILT.
            boolean inComponents = sub instanceof KType.Intersection i
                    && i.components().stream().anyMatch(c -> c instanceof KType.IntegerLiteral);
            if (inComponents || isTypeInIntegerLiteral(l, sub, true)) return true;
        }
        return null;
    }

    private boolean isTypeInIntegerLiteral(KType.IntegerLiteral literal, KType type, boolean checkSupertypes) {
        for (KType.ClassType possible : literal.possibleTypes()) {
            if (constructor(possible).equals(constructor(type)) || checkSupertypes && isSubtype(type, possible)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIntegerLiteralOrCaptured(KType t) {
        if (t instanceof KType.IntegerLiteral) return true;
        return t instanceof KType.Captured c && !c.constructor().projection().isStar()
                && Types.upperBound(c.constructor().projection().type()) instanceof KType.IntegerLiteral;
    }

    private boolean hasNothingSupertype(KType type) {
        if (constructor(type) instanceof ClassSymbol cls) return cls == ts.nothingClass();
        return anySupertype(type, t -> constructor(t) == ts.nothingClass(),
                t -> t instanceof KType.ClassType ? null : Types::lowerBound);
    }

    /** Whether the arguments, of a supertype of the subtype with the supertype's class, fit the supertype's. */
    private boolean isSubtypeForSameConstructor(List<TypeArg> subArgs, KType.ClassType sup) {
        List<TypeParamSymbol> params = Overrides.allTypeParams(sup.symbol());
        // Classes from different modules can disagree on the number of parameters.
        if (subArgs.size() != params.size() || sup.args().size() != params.size()) return false;
        for (int i = 0; i < params.size(); i++) {
            TypeArg supArg = sup.args().get(i);
            if (supArg.isStar()) continue;
            TypeArg subArg = subArgs.get(i);
            if (subArg.isStar()) return false;
            Variance variance = effectiveVariance(params.get(i).variance(), supArg.variance());
            if (variance == null) return false;
            if (variance == Variance.INVARIANT
                    && (isTypeVariableAgainstStarForSelfType(subArg.type(), supArg.type(), sup.symbol())
                    || isTypeVariableAgainstStarForSelfType(supArg.type(), subArg.type(), sup.symbol()))) {
                continue;
            }
            if (argumentsDepth > 100) throw new IllegalStateException("Arguments depth is too high: " + subArg);
            argumentsDepth++;
            boolean ok = switch (variance) {
                case INVARIANT -> equalTypes(subArg.type(), supArg.type());
                case OUT -> isSubtype(subArg.type(), supArg.type());
                case IN -> isSubtype(supArg.type(), subArg.type());
            };
            argumentsDepth--;
            if (!ok) return false;
        }
        return true;
    }

    /** The variance of an argument for a parameter; null for a conflict ({@code in} with {@code out}). */
    public static Variance effectiveVariance(Variance declared, Variance useSite) {
        if (declared == Variance.INVARIANT) return useSite;
        if (useSite == Variance.INVARIANT) return declared;
        return declared == useSite ? declared : null;
    }

    /**
     * {@code Captured(*) <: E} for a variable for {@code E : Enum<E>} is not
     * checked: the captured type would be approximated to Nothing in a
     * constraint. The supertypes are compared instead.
     */
    private static boolean isTypeVariableAgainstStarForSelfType(KType sub, KType sup, ClassSymbol self) {
        if (!(sub instanceof KType.Captured c) || !c.constructor().projection().isStar()
                || c.constructor().status() != CapturedTypeConstructor.Status.FOR_SUBTYPING) {
            return false;
        }
        return sup instanceof KType.TypeVariableType v && v.variable().typeParam() != null
                && hasRecursiveBounds(v.variable().typeParam(), self);
    }

    // ==== Supertypes ====

    /**
     * The supertypes of the subtype with the given constructor, with
     * arguments captured. Nullability is assumed to have been checked.
     */
    public List<KType> findCorrespondingSupertypes(KType sub, Object supConstructor) {
        if (sub instanceof KType.ClassType) return collectAndFilter(sub, supConstructor);
        if (!(supConstructor instanceof ClassSymbol) && !(supConstructor instanceof KType.IntegerLiteral)) {
            return collectAllSupertypesWithConstructor(sub, supConstructor);
        }
        List<KType> classSupertypes = new ArrayList<>();
        anySupertype(sub, t -> false, t -> {
            if (t instanceof KType.ClassType) {
                classSupertypes.add(t);
                return null;
            }
            return Types::lowerBound;
        });
        List<KType> result = new ArrayList<>();
        for (KType t : classSupertypes) result.addAll(collectAndFilter(t, supConstructor));
        return result;
    }

    /** Prefers paths through Kotlin supertypes: for {@code MyList : AbstractList<String>(), MutableList<String>}, String. */
    private List<KType> collectAndFilter(KType type, Object constructor) {
        List<KType> all = collectAllSupertypesWithConstructor(type, constructor);
        if (all.size() < 2) return all;
        List<KType> pure = all.stream().filter(t -> t instanceof KType.ClassType c
                && c.args().stream().noneMatch(a -> a.type() instanceof KType.Flexible)).toList();
        return pure.isEmpty() ? all : pure;
    }

    private List<KType> collectAllSupertypesWithConstructor(KType sub, Object constructor) {
        if (!(constructor instanceof ClassSymbol) && sub instanceof KType.ClassType) return List.of();
        if (constructor instanceof ClassSymbol cls && isCommonFinalClass(cls)) {
            if (!constructor(sub).equals(constructor)) return List.of();
            KType captured = sub instanceof KType.ClassType c
                    ? ts.captureFromArguments(c, CapturedTypeConstructor.Status.FOR_SUBTYPING) : null;
            return List.of(captured != null ? captured : sub);
        }
        List<KType> result = new ArrayList<>();
        anySupertype(sub, t -> false, t -> {
            KType current = t;
            if (t instanceof KType.ClassType c) {
                KType captured = ts.captureFromArguments(c, CapturedTypeConstructor.Status.FOR_SUBTYPING);
                if (captured != null) current = captured;
            }
            if (constructor(current).equals(constructor)) {
                result.add(current);
                return null;
            }
            if (!(current instanceof KType.ClassType c) || c.args().isEmpty()) return Types::lowerBound;
            return substitutionPolicy(c);
        });
        return result;
    }

    private static boolean isCommonFinalClass(ClassSymbol cls) {
        return cls.modality() == Modality.FINAL
                && cls.kind() != ClassKind.ENUM_ENTRY
                && cls.kind() != ClassKind.ANNOTATION_CLASS;
    }

    /** Supertypes of a class type with its arguments substituted; a star or projection gives its type, or Any?. */
    private UnaryOperator<KType> substitutionPolicy(KType.ClassType type) {
        List<TypeParamSymbol> params = Overrides.allTypeParams(type.symbol());
        if (params.size() != type.args().size()) return Types::lowerBound;
        List<KType> args = type.args().stream().map(a -> a.isStar() ? ts.nullableAny() : a.type()).toList();
        Substitution subst = Substitution.ofTypes(params, args);
        return t -> subst.apply(Types.lowerBound(t));
    }

    /**
     * Breadth-first search of the supertypes of a type: whether any
     * satisfies the predicate. The policy says how to transform the
     * supertypes of a type before visiting them, or null to skip them.
     */
    boolean anySupertype(KType start, Predicate<KType> predicate, Function<KType, UnaryOperator<KType>> policy) {
        if (predicate.test(start)) return true;
        Deque<KType> deque = new ArrayDeque<>();
        Set<KType> visited = new HashSet<>();
        deque.add(start);
        while (!deque.isEmpty()) {
            KType current = deque.removeFirst();
            if (!visited.add(current)) continue;
            UnaryOperator<KType> transform = policy.apply(current);
            if (transform == null) continue;
            for (KType s : ts.supertypes(current)) {
                KType t = transform.apply(s);
                if (predicate.test(t)) return true;
                deque.add(t);
            }
        }
        return false;
    }

    // ==== Type constructors ====

    /**
     * What a type is a type of, ignoring arguments and nullability: a
     * class, type parameter, type variable, captured type constructor, or
     * for intersection and integer literal types, the type itself.
     */
    public static Object constructor(KType t) {
        return switch (t) {
            case KType.ClassType c -> c.symbol();
            case KType.TypeParamType p -> p.symbol();
            case KType.TypeVariableType v -> v.variable();
            case KType.Captured c -> c.constructor();
            case KType.DefinitelyNotNull d -> constructor(d.inner());
            case KType.Flexible f -> constructor(f.lower());
            case KType.Intersection i -> i;
            case KType.IntegerLiteral l -> l.withNullable(false);
        };
    }

    static int parameterCount(Object constructor) {
        return constructor instanceof ClassSymbol cls ? Overrides.allTypeParams(cls).size() : 0;
    }
}
