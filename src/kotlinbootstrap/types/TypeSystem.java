package kotlinbootstrap.types;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.SymbolTable;
import kotlinbootstrap.symbols.TypeParamSymbol;

/**
 * Operations on types that need to know about classes: subtyping (see
 * {@link SubtypeChecker}), capturing, intersection and common supertypes,
 * following kotlinc's K2 implementations.
 */
public final class TypeSystem {
    private final SymbolTable table;
    private final ClassSymbol anyClass;
    private final ClassSymbol nothingClass;

    public TypeSystem(SymbolTable table) {
        this.table = table;
        this.anyClass = table.requireClass("kotlin.Any");
        this.nothingClass = table.requireClass("kotlin.Nothing");
    }

    public SymbolTable table() {
        return table;
    }

    // ==== Well-known types ====

    public ClassSymbol anyClass() {
        return anyClass;
    }

    public ClassSymbol nothingClass() {
        return nothingClass;
    }

    public KType.ClassType classType(String fqName, TypeArg... args) {
        return new KType.ClassType(table.requireClass(fqName), List.of(args), false);
    }

    public KType.ClassType any() {
        return new KType.ClassType(anyClass, List.of(), false);
    }

    public KType.ClassType nullableAny() {
        return new KType.ClassType(anyClass, List.of(), true);
    }

    public KType.ClassType nothing() {
        return new KType.ClassType(nothingClass, List.of(), false);
    }

    public KType.ClassType nullableNothing() {
        return new KType.ClassType(nothingClass, List.of(), true);
    }

    /** Non-null Nothing; not a flexible or nullable one. */
    public boolean isNothing(KType t) {
        return t instanceof KType.ClassType c && c.symbol() == nothingClass && !c.nullable();
    }

    // ==== Subtyping ====

    public boolean isSubtype(KType sub, KType sup) {
        return new SubtypeChecker(this).isSubtype(sub, sup);
    }

    public boolean equalTypes(KType a, KType b) {
        return new SubtypeChecker(this).equalTypes(a, b);
    }

    /**
     * The declared supertypes of the type's constructor: of a class, not
     * substituted; the bounds of a type parameter; the components of an
     * intersection.
     */
    public List<KType> supertypes(KType t) {
        return switch (t) {
            case KType.ClassType c -> c.symbol().supertypes();
            case KType.IntegerLiteral l -> integerLiteralSupertypes(l);
            default -> Types.nonClassSupertypes(t);
        };
    }

    /** {@code Number, Comparable<ILT>}; for unsigned literals only the latter. */
    private List<KType> integerLiteralSupertypes(KType.IntegerLiteral l) {
        KType comparable = classType("kotlin.Comparable", new TypeArg(Variance.IN, l.withNullable(false)));
        return l.unsigned() ? List.of(comparable) : List.of(classType("kotlin.Number"), comparable);
    }

    /**
     * The type as an instance of a superclass: e.g. {@code ArrayList<String>}
     * as Collection is {@code Collection<String>}. Arguments are captured
     * for subtyping where projected. Null if the class is not a supertype.
     */
    public KType.ClassType asSupertype(KType type, ClassSymbol cls) {
        SubtypeChecker checker = new SubtypeChecker(this);
        List<KType> found = checker.findCorrespondingSupertypes(Types.lowerBound(type), cls);
        return found.isEmpty() ? null : (KType.ClassType) found.getFirst();
    }

    // ==== Capturing ====

    /**
     * Replaces projected arguments of a class type by captured types: a
     * value of {@code MutableList<out Number>} is a {@code MutableList<C>}
     * for some unknown {@code C <: Number}. Null if no argument is projected.
     */
    public KType.ClassType captureFromArguments(KType.ClassType type, CapturedTypeConstructor.Status status) {
        List<TypeArg> captured = captureArguments(type, status);
        return captured == null ? null
                : new KType.ClassType(type.symbol(), captured, type.nullable(), type.extensionFunction(),
                        type.contextParams());
    }

    private List<TypeArg> captureArguments(KType.ClassType type, CapturedTypeConstructor.Status status) {
        List<TypeArg> args = type.args();
        if (args.isEmpty()) return null;
        List<TypeParamSymbol> params = Overrides.allTypeParams(type.symbol());
        if (args.size() != params.size()) return null;
        if (args.stream().allMatch(a -> !a.isStar() && a.variance() == Variance.INVARIANT)) return null;

        List<TypeArg> newArgs = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            TypeArg a = args.get(i);
            if (!a.isStar() && a.variance() == Variance.INVARIANT) {
                newArgs.add(a);
                continue;
            }
            KType lower = !a.isStar() && a.variance() == Variance.IN ? a.type() : null;
            newArgs.add(TypeArg.of(new KType.Captured(new CapturedTypeConstructor(a, params.get(i), status), lower, false)));
        }
        Substitution subst = Substitution.of(params, newArgs);
        for (int i = 0; i < args.size(); i++) {
            TypeArg a = args.get(i);
            if (!a.isStar() && a.variance() == Variance.INVARIANT) continue;
            Set<KType> upper = new LinkedHashSet<>();
            for (KType bound : params.get(i).bounds()) upper.add(subst.apply(bound));
            if (!a.isStar() && a.variance() == Variance.OUT) upper.add(a.type());
            List<KType> supertypes;
            if (status == CapturedTypeConstructor.Status.FROM_EXPRESSION) {
                // Intersecting drops redundant bounds, which can change nullability: Foo<String!> & Foo<String>
                // is Foo<String>. Only from expressions, since intersecting checks subtyping, which captures.
                KType intersected = intersect(new ArrayList<>(upper));
                supertypes = intersected instanceof KType.Intersection i2 ? i2.components() : List.of(intersected);
            } else {
                supertypes = new ArrayList<>(upper);
            }
            ((KType.Captured) newArgs.get(i).type()).constructor().setSupertypes(supertypes);
        }
        return newArgs;
    }

    /**
     * Captures the projected arguments in the type of an expression, so that
     * e.g. a {@code MutableList<*>} can be passed for a {@code MutableList<T>}.
     * In flexible and intersection types, components with the same
     * arguments share the captured types. Null if nothing was captured.
     */
    public KType captureFromExpression(KType type) {
        return switch (type) {
            case KType.DefinitelyNotNull d -> {
                KType inner = captureFromExpression(d.inner());
                yield inner == null ? null : Types.makeDefinitelyNotNull(inner);
            }
            case KType.Flexible f -> {
                if (f.lower() instanceof KType.Captured) {
                    KType lower = captureFromExpression(f.lower());
                    KType upper = captureFromExpression(f.upper());
                    yield lower == null && upper == null ? null
                            : Types.flexible(lower != null ? lower : f.lower(), upper != null ? upper : f.upper());
                }
                if (!(f.lower() instanceof KType.ClassType || f.lower() instanceof KType.Intersection)) yield null;
                List<KType> toCapture = new ArrayList<>(components(f.lower()));
                toCapture.addAll(components(f.upper()));
                Map<KType, List<TypeArg>> captured = captureShared(toCapture);
                if (captured == null) yield null;
                // As kotlinc, nothing is captured unless both bounds have something to capture.
                KType lower = replaceCaptured(f.lower(), captured);
                KType upper = replaceCaptured(f.upper(), captured);
                yield lower == null || upper == null ? null : Types.flexible(lower, upper);
            }
            case KType.Intersection i -> {
                Map<KType, List<TypeArg>> captured = captureShared(i.components());
                yield captured == null ? null : replaceCaptured(i, captured);
            }
            case KType.ClassType c -> captureFromArguments(c, CapturedTypeConstructor.Status.FROM_EXPRESSION);
            default -> null;
        };
    }

    private static List<KType> components(KType t) {
        return t instanceof KType.Intersection i ? i.components() : List.of(t);
    }

    /**
     * Captured arguments for each component type, reusing them for
     * components with the same class (or the read-only and mutable
     * versions of a collection) and arguments. Null if nothing is captured.
     */
    private Map<KType, List<TypeArg>> captureShared(List<KType> types) {
        Map<KType, List<TypeArg>> result = new HashMap<>();
        Map<List<Object>, List<TypeArg>> byKey = new HashMap<>();
        for (KType t : types) {
            if (!(t instanceof KType.ClassType c)) continue;
            List<Object> key = List.of(baseClassName(c.symbol()), c.args());
            List<TypeArg> args = byKey.get(key);
            if (args == null && !byKey.containsKey(key)) {
                args = captureArguments(c, CapturedTypeConstructor.Status.FROM_EXPRESSION);
                byKey.put(key, args);
            }
            if (args != null) result.put(c.withNullable(false), args);
        }
        return result.isEmpty() ? null : result;
    }

    /** kotlin.collections.MutableList and List are the same base class. */
    private static String baseClassName(ClassSymbol cls) {
        String pkg = cls.id().packageName();
        String name = cls.name();
        if (pkg.equals("kotlin.collections") && name.startsWith("Mutable")) name = name.substring("Mutable".length());
        return pkg + "." + name;
    }

    /** The type with the captured arguments substituted in its components; null if none changed. */
    private KType replaceCaptured(KType t, Map<KType, List<TypeArg>> captured) {
        if (t instanceof KType.Intersection i) {
            boolean changed = false;
            List<KType> components = new ArrayList<>();
            for (KType c : i.components()) {
                KType r = replaceCaptured(c, captured);
                changed |= r != null;
                components.add(r != null ? r : c);
            }
            if (!changed) return null;
            KType result = intersect(components);
            return Types.canBeNull(i) ? result.withNullable(true) : result;
        }
        if (!(t instanceof KType.ClassType c)) return null;
        List<TypeArg> args = captured.get(c.withNullable(false));
        return args == null ? null
                : new KType.ClassType(c.symbol(), args, c.nullable(), c.extensionFunction(), c.contextParams());
    }

    // ==== Intersection ====

    /**
     * {@code A & B}, simplified as kotlinc's ConeTypeIntersector: flexible
     * types are intersected bound by bound; all components become non-null
     * if any is; strict supertypes of other components and duplicates are
     * dropped.
     */
    public KType intersect(Collection<KType> types) {
        if (types.isEmpty()) throw new IllegalArgumentException("Empty intersection");
        if (types.size() == 1) return types.iterator().next();
        List<KType> input = new ArrayList<>();
        for (KType t : types) input.addAll(components(t));

        if (input.stream().anyMatch(t -> t instanceof KType.Flexible)) {
            // (A..B) & C = (A & C)..(B & C)
            KType lower = intersect(input.stream().map(Types::lowerBound).toList());
            KType upper = intersect(input.stream().map(Types::upperBound).toList());
            // C = Nothing? gives Nothing!, but if it is non-null the code is unreachable.
            return isNothing(lower) ? upper : Types.flexible(lower, upper);
        }
        boolean notNull = input.stream().anyMatch(t -> !Types.canBeNull(t));
        Set<KType> unique = new LinkedHashSet<>();
        for (KType t : input) unique.add(notNull ? Types.makeDefinitelyNotNull(t) : t);
        if (unique.size() == 1) return unique.iterator().next();

        // Drop A from A & B for B : A; it adds nothing.
        List<KType> result = new ArrayList<>(unique);
        SubtypeChecker checker = new SubtypeChecker(this);
        removeIfRelated(result, (candidate, other) -> checker.isSubtype(other, candidate)
                && !checker.isSubtype(candidate, other));
        KType literal = commonIntegerLiteralIntersection(result);
        if (literal != null) return literal;
        removeIfRelated(result, checker::equalTypes);
        return result.size() == 1 ? result.getFirst() : new KType.Intersection(result);
    }

    private static void removeIfRelated(List<KType> types, BiPredicate<KType, KType> related) {
        // Captured types with recursive supertypes are kept, as kotlinc does; comparing them may not terminate.
        for (var it = types.iterator(); it.hasNext(); ) {
            KType candidate = it.next();
            if (hasRecursiveCapturedSupertype(candidate)) continue;
            boolean remove = false;
            for (KType other : types) {
                if (other != candidate && !hasRecursiveCapturedSupertype(other) && related.test(candidate, other)) {
                    remove = true;
                    break;
                }
            }
            if (remove) it.remove();
        }
    }

    private static boolean hasRecursiveCapturedSupertype(KType t) {
        if (!(Types.lowerBound(t) instanceof KType.Captured c) || c.constructor().supertypes() == null) return false;
        for (KType s : c.constructor().supertypes()) {
            if (!(s instanceof KType.ClassType sc)) continue;
            for (TypeArg a : sc.args()) {
                if (!a.isStar() && Types.lowerBound(a.type()) instanceof KType.Captured ac && ac.constructor() == c.constructor()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** An integer literal type with the possible types that all others share, or one of them; else null. */
    private static KType commonIntegerLiteralIntersection(List<KType> types) {
        KType result = types.getFirst();
        for (KType t : types.subList(1, types.size())) {
            if (result instanceof KType.IntegerLiteral l && t instanceof KType.IntegerLiteral r) {
                if (l.possibleTypes().containsAll(r.possibleTypes())) {
                    result = r;
                } else if (!r.possibleTypes().containsAll(l.possibleTypes())) {
                    return null;
                }
            } else if (result instanceof KType.IntegerLiteral l) {
                if (!l.possibleTypes().contains(t)) return null;
                result = t;
            } else if (t instanceof KType.IntegerLiteral r) {
                if (!r.possibleTypes().contains(result)) return null;
            } else {
                return null;
            }
        }
        return result;
    }

    // ==== Common supertypes ====

    /** The least upper bound of the types, as kotlinc computes it. */
    public KType commonSuperType(List<KType> types) {
        return new CommonSuperTypes(this).commonSuperType(types);
    }
}
