package kotlinbootstrap.infer;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.types.CapturedTypeConstructor;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.SubtypeChecker;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;

/**
 * Type operations for inference with exactly kotlinc's semantics, which
 * differ in details from the general ones in {@link Types}: e.g. FIR's
 * {@code contains} does not look into the projections of captured types.
 */
final class InferenceTypes {
    private InferenceTypes() {
    }

    /**
     * Whether the type or a type nested in it satisfies the predicate, as
     * FIR's ConeKotlinType.contains: the upper bound of a flexible type only
     * if it is not trivially flexible, and not the projections of captured
     * types.
     */
    static boolean contains(KType t, Predicate<KType> p) {
        return contains(t, p, new HashSet<>());
    }

    private static boolean contains(KType t, Predicate<KType> p, Set<KType> visited) {
        if (visited.contains(t)) return false;
        if (p.test(t)) return true;
        visited.add(t);
        return switch (t) {
            case KType.Flexible f -> contains(f.lower(), p, visited)
                    || !isTriviallyFlexible(f) && contains(f.upper(), p, visited);
            case KType.DefinitelyNotNull d -> contains(d.inner(), p, visited);
            case KType.Intersection i -> i.components().stream().anyMatch(c -> contains(c, p, visited));
            case KType.ClassType c -> c.args().stream().anyMatch(a -> !a.isStar() && contains(a.type(), p, visited));
            default -> false;
        };
    }

    /** {@code (T..T?)}: the upper bound is the lower bound made nullable. */
    static boolean isTriviallyFlexible(KType.Flexible f) {
        return f.lower().withNullable(true).equals(f.upper());
    }

    /** {@code (t..t?)} for a non-flexible, not nullable type; otherwise the type itself. */
    static KType createTrivialFlexibleTypeOrSelf(KType t) {
        if (t instanceof KType.Flexible || isMarkedNullable(t)) return t;
        return new KType.Flexible(t, t.withNullable(true));
    }

    /** {@code (A..B)} becomes {@code (A & Any..B)}. */
    static KType makeLowerBoundDefinitelyNotNullOrNotNull(KType.Flexible f) {
        return new KType.Flexible(Types.makeDefinitelyNotNull(f.lower()), f.upper());
    }

    static boolean isMarkedNullable(KType t) {
        return Types.isMarkedNullable(t);
    }

    /** {@code (A..B?)} or {@code (A?..B)}. */
    static boolean hasFlexibleNullability(KType t) {
        return isMarkedNullable(Types.lowerBound(t)) != isMarkedNullable(Types.upperBound(t));
    }

    /**
     * Whether null is a value of the type, as FIR's canBeNull. Without the
     * bounds of type variables, a type variable can only be null if marked
     * nullable.
     */
    static boolean isNullableType(KType t, boolean considerTypeVariableBounds) {
        if (considerTypeVariableBounds) return Types.canBeNull(t);
        return canBeNullIgnoringVariableBounds(t, new HashSet<>());
    }

    private static boolean canBeNullIgnoringVariableBounds(KType t, Set<KType> visited) {
        if (!visited.add(t)) return false;
        return switch (t) {
            case KType.Flexible f -> canBeNullIgnoringVariableBounds(f.upper(), visited);
            case KType.DefinitelyNotNull d -> false;
            case KType.TypeVariableType v -> v.nullable();
            case KType.Intersection i -> i.components().stream().allMatch(c -> canBeNullIgnoringVariableBounds(c, visited));
            case KType.Captured c -> c.nullable() || c.constructor().supertypes() != null
                    && c.constructor().supertypes().stream().allMatch(s -> canBeNullIgnoringVariableBounds(s, visited));
            case KType.TypeParamType p -> p.nullable() || p.symbol().bounds().stream()
                    .allMatch(b -> canBeNullIgnoringVariableBounds(b, visited));
            default -> t.nullable();
        };
    }

    // ==== Kinds of types ====

    static boolean isNothingConstructor(TypeSystem ts, KType t) {
        return SubtypeChecker.constructor(t) == ts.nothingClass();
    }

    static boolean isNothing(TypeSystem ts, KType t) {
        return isNothingConstructor(ts, t) && !Types.canBeNull(t);
    }

    static boolean isNullableNothing(TypeSystem ts, KType t) {
        return isNothingConstructor(ts, t) && Types.canBeNull(t);
    }

    /** {@code Nothing!}: {@code (Nothing..Nothing?)}. */
    static boolean isFlexibleNothing(TypeSystem ts, KType t) {
        return t instanceof KType.Flexible f && ts.isNothing(f.lower()) && isNullableNothing(ts, f.upper());
    }

    static boolean isNullableAny(TypeSystem ts, KType t) {
        return SubtypeChecker.constructor(t) == ts.anyClass() && Types.canBeNull(t);
    }

    static boolean isIntegerLiteral(KType t) {
        return SubtypeChecker.constructor(t) instanceof KType.IntegerLiteral;
    }

    /** A captured type, possibly definitely non-null; not flexible. */
    static KType.Captured capturedUnwrappingDnn(KType t) {
        if (t instanceof KType.DefinitelyNotNull d) t = d.inner();
        return t instanceof KType.Captured c ? c : null;
    }

    /** The type variable the type is a type of (also flexibly or definitely non-null), or null. */
    static TypeVariable typeVariable(KType t) {
        return SubtypeChecker.constructor(t) instanceof TypeVariable v ? v : null;
    }

    /** The number of type arguments: of the lower bound of a flexible type; none but for class types. */
    static int argumentsCount(KType t) {
        return Types.lowerBound(t) instanceof KType.ClassType c ? c.args().size() : 0;
    }

    // ==== Type variables in types ====

    /**
     * The types of the projections of the captured types in the type,
     * transitively through their arguments, as kotlinc's
     * extractProjectionsForAllCapturedTypes.
     */
    static Set<KType> extractProjectionsForAllCapturedTypes(KType t) {
        Set<KType> result = new LinkedHashSet<>();
        extractProjections(t, result);
        return result;
    }

    private static void extractProjections(KType t, Set<KType> result) {
        if (t instanceof KType.Flexible f) {
            extractProjections(f.lower(), result);
            if (!isTriviallyFlexible(f)) extractProjections(f.upper(), result);
            return;
        }
        KType.Captured captured = capturedUnwrappingDnn(t);
        KType projected = t;
        if (captured != null) {
            projected = captured.constructor().projection().type();
            if (projected == null || !result.add(projected)) return;
        }
        if (!(projected instanceof KType.ClassType c)) return;
        for (TypeArg a : c.args()) {
            if (!a.isStar()) extractProjections(a.type(), result);
        }
    }

    /** All type variables in the type, also in projections of captured types. */
    static Set<TypeVariable> extractAllContainingTypeVariables(KType t) {
        Set<TypeVariable> result = new LinkedHashSet<>();
        Predicate<KType> collect = x -> {
            TypeVariable v = typeVariable(x);
            if (v != null) result.add(v);
            return false;
        };
        contains(t, collect);
        for (KType projection : extractProjectionsForAllCapturedTypes(t)) contains(projection, collect);
        return result;
    }

    /** Whether the type mentions the variable, also in projections of captured types. */
    static boolean containsTypeVariable(KType t, TypeVariable variable) {
        if (contains(t, x -> typeVariable(x) == variable)) return true;
        for (KType projection : extractProjectionsForAllCapturedTypes(t)) {
            if (contains(projection, x -> typeVariable(x) == variable)) return true;
        }
        return false;
    }

    /**
     * Whether a variable may be fixed to the type, as kotlinc's
     * isProperTypeForFixation: never to a type variable at the top level,
     * and the type and the projections of its captured types must be proper.
     */
    static boolean isProperTypeForFixation(KType t, Set<TypeVariable> notFixed, Predicate<KType> isProper) {
        if (notFixed.contains(typeVariable(t))) return false;
        if (!isProper.test(t)) return false;
        for (KType projection : extractProjectionsForAllCapturedTypes(t)) {
            if (!isProper.test(projection)) return false;
        }
        return true;
    }

    /**
     * Whether some type argument nested in the type satisfies the predicate,
     * the type itself counting as an invariant argument; as kotlinc's
     * anyNestedArgument.
     */
    static boolean anyNestedArgument(KType type, Predicate<TypeArg> predicate) {
        ArrayDeque<TypeArg> stack = new ArrayDeque<>();
        if (type instanceof KType.Flexible f) {
            stack.push(TypeArg.of(f.lower()));
            if (!isTriviallyFlexible(f)) stack.push(TypeArg.of(f.upper()));
        } else {
            stack.push(TypeArg.of(type));
        }
        stack.push(TypeArg.of(type));
        while (!stack.isEmpty()) {
            TypeArg arg = stack.pop();
            if (arg.isStar()) continue;
            if (predicate.test(arg)) return true;
            if (arg.type() instanceof KType.Flexible f) {
                addArguments(stack, f.lower());
                if (!isTriviallyFlexible(f)) addArguments(stack, f.upper());
            } else {
                addArguments(stack, arg.type());
            }
        }
        return false;
    }

    private static void addArguments(ArrayDeque<TypeArg> stack, KType t) {
        if (t instanceof KType.ClassType c) stack.addAll(c.args());
    }

    // ==== Self types ====

    /**
     * Whether a type parameter of the constructor has a bound mentioning
     * itself with the constructor, e.g. {@code E : Enum<E>} of Enum; for
     * captured and intersection types, whether one of their supertypes does.
     */
    static boolean hasRecursiveTypeParametersWithGivenSelfType(Object constructor) {
        return hasRecursiveTypeParametersWithGivenSelfType(constructor,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean hasRecursiveTypeParametersWithGivenSelfType(Object constructor, Set<Object> visited) {
        if (!visited.add(constructor)) return false;
        if (constructor instanceof ClassSymbol cls) {
            for (TypeParamSymbol tp : Overrides.allTypeParams(cls)) {
                if (SubtypeChecker.hasRecursiveBounds(tp, cls)) return true;
            }
            return false;
        }
        Iterable<KType> supertypes = switch (constructor) {
            case CapturedTypeConstructor c -> c.supertypes() == null ? Set.of() : c.supertypes();
            case KType.Intersection i -> i.components();
            default -> Set.of();
        };
        for (KType s : supertypes) {
            if (hasRecursiveTypeParametersWithGivenSelfType(SubtypeChecker.constructor(s), visited)) return true;
        }
        return false;
    }

    /** A type parameter with a bound mentioning itself, e.g. {@code T : Comparable<T>}. */
    static boolean isRecursiveTypeParameter(Object constructor) {
        return constructor instanceof TypeParamSymbol tp && SubtypeChecker.hasRecursiveBounds(tp, null);
    }
}
