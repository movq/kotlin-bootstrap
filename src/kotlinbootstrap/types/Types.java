package kotlinbootstrap.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural operations on types that need no knowledge of classes. See
 * {@link TypeSystem} for subtyping and everything else that does.
 */
public final class Types {
    private Types() {
    }

    public static KType makeNullable(KType t) {
        return t.withNullable(true);
    }

    /**
     * {@code T & Any}: the type without null. Class types just lose their
     * question mark; type parameters, variables and captured types that can
     * be null become definitely non-null. A flexible type becomes its lower
     * bound without null. As kotlinc's makeConeTypeDefinitelyNotNullOrNotNull.
     */
    public static KType makeDefinitelyNotNull(KType t) {
        return switch (t) {
            case KType.TypeParamType p -> canBeNull(p) ? new KType.DefinitelyNotNull(p.withNullable(false)) : p;
            case KType.TypeVariableType v -> !isSubtypeOfAny(v.withNullable(false))
                    ? new KType.DefinitelyNotNull(v.withNullable(false)) : v;
            case KType.Captured c -> !isSubtypeOfAny(c.withNullable(false))
                    ? new KType.DefinitelyNotNull(c.withNullable(false)) : c;
            case KType.DefinitelyNotNull d -> d;
            case KType.Flexible f -> makeDefinitelyNotNull(f.lower()) instanceof KType.DefinitelyNotNull d
                    ? d : f.withNullable(false);
            case KType.Intersection i -> new KType.Intersection(
                    i.components().stream().map(Types::makeDefinitelyNotNull).toList());
            case KType.ClassType c -> c.withNullable(false);
            case KType.IntegerLiteral l -> l.withNullable(false);
        };
    }

    /** Whether null is a value of the type, as kotlinc's canBeNull. */
    public static boolean canBeNull(KType t) {
        return canBeNull(t, new HashSet<>());
    }

    private static boolean canBeNull(KType t, Set<KType> visited) {
        if (!visited.add(t)) return false;
        return switch (t) {
            case KType.Flexible f -> canBeNull(f.upper(), visited);
            case KType.DefinitelyNotNull d -> false;
            case KType.TypeParamType p -> p.nullable()
                    || p.symbol().bounds().stream().allMatch(b -> canBeNull(b, visited));
            case KType.Intersection i -> i.components().stream().allMatch(c -> canBeNull(c, visited));
            case KType.Captured c -> c.nullable() || c.constructor().supertypes() != null
                    && c.constructor().supertypes().stream().allMatch(s -> canBeNull(s, visited));
            case KType.TypeVariableType v -> v.nullable() || v.variable().typeParam() == null
                    || v.variable().typeParam().bounds().stream().allMatch(b -> canBeNull(b, visited));
            case KType.ClassType c -> c.nullable();
            case KType.IntegerLiteral l -> l.nullable();
        };
    }

    /**
     * Whether the type has a supertype that is a non-null class type or
     * definitely non-null: AbstractNullabilityChecker.isSubtypeOfAny. Class
     * types answer directly, so their supertypes are not needed.
     */
    public static boolean isSubtypeOfAny(KType t) {
        Deque<KType> queue = new ArrayDeque<>();
        Set<KType> visited = new HashSet<>();
        queue.add(lowerBound(t));
        while (!queue.isEmpty()) {
            KType current = queue.removeFirst();
            if (current instanceof KType.ClassType c && !c.nullable()) return true;
            if (current instanceof KType.DefinitelyNotNull || current instanceof KType.IntegerLiteral l && !l.nullable()) {
                return true;
            }
            if (current.nullable() || !visited.add(current)) continue;
            for (KType s : nonClassSupertypes(current)) queue.add(lowerBound(s));
        }
        return false;
    }

    /** The supertypes of a type that is not a class type: bounds, components and the like. */
    static List<KType> nonClassSupertypes(KType t) {
        return switch (t) {
            case KType.TypeParamType p -> p.symbol().bounds();
            case KType.DefinitelyNotNull d -> nonClassSupertypes(d.inner());
            case KType.Captured c -> c.constructor().supertypes() == null ? List.of() : c.constructor().supertypes();
            case KType.Intersection i -> i.components();
            case KType.TypeVariableType v -> List.of();
            case KType.Flexible f -> nonClassSupertypes(f.lower());
            case KType.ClassType c -> throw new IllegalArgumentException("Class type " + c);
            case KType.IntegerLiteral l -> throw new IllegalArgumentException("Integer literal type " + l);
        };
    }

    /** {@code (lower..upper)}, or just the type if the bounds are the same. */
    public static KType flexible(KType lower, KType upper) {
        if (lower instanceof KType.Flexible f) lower = f.lower();
        if (upper instanceof KType.Flexible f) upper = f.upper();
        return lower.equals(upper) ? lower : new KType.Flexible(lower, upper);
    }

    public static KType lowerBound(KType t) {
        return t instanceof KType.Flexible f ? f.lower() : t;
    }

    public static KType upperBound(KType t) {
        return t instanceof KType.Flexible f ? f.upper() : t;
    }

    /** The intersection of the types, flattened and without duplicates; the type itself if only one. */
    public static KType intersect(List<KType> types) {
        Set<KType> components = new LinkedHashSet<>();
        for (KType t : types) {
            if (t instanceof KType.Intersection i) {
                components.addAll(i.components());
            } else {
                components.add(t);
            }
        }
        if (components.size() == 1) return components.iterator().next();
        if (components.isEmpty()) throw new IllegalArgumentException("Empty intersection");
        return new KType.Intersection(new ArrayList<>(components));
    }

    /** Whether the type mentions any of the type variables. */
    public static boolean containsTypeVariable(KType t) {
        return contains(t, x -> x instanceof KType.TypeVariableType);
    }

    public static boolean contains(KType t, java.util.function.Predicate<KType> p) {
        if (p.test(t)) return true;
        return switch (t) {
            case KType.ClassType c -> c.args().stream().anyMatch(a -> !a.isStar() && contains(a.type(), p));
            case KType.DefinitelyNotNull d -> contains(d.inner(), p);
            case KType.Flexible f -> contains(f.lower(), p) || contains(f.upper(), p);
            case KType.Intersection i -> i.components().stream().anyMatch(c -> contains(c, p));
            case KType.Captured c -> !c.constructor().projection().isStar()
                    && contains(c.constructor().projection().type(), p);
            default -> false;
        };
    }
}
