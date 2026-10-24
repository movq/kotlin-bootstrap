package kotlinbootstrap.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.TypeParamSymbol;

/**
 * Replaces types that cannot be written in source (captured, integer
 * literal, intersection, type variable and local types) by denotable
 * supertypes or subtypes, as kotlinc's AbstractTypeApproximator with
 * captured type approximation as reworked in 2.2. What is replaced
 * depends on the {@link Config}. Flexible types are kept.
 */
public final class TypeApproximator {
    public enum IntersectionStrategy { ALLOWED, TO_FIRST, TO_COMMON_SUPERTYPE }

    /**
     * What to approximate, as kotlinc's TypeApproximatorConfiguration.
     *
     * @param expectedTypeForIntegerLiteral the type that a top-level integer literal type becomes if possible
     * @param capturedTypes                 which captured types to approximate
     */
    public record Config(IntersectionStrategy intersectionStrategy, boolean integerLiterals,
                         boolean integerOperators, KType expectedTypeForIntegerLiteral,
                         boolean intersectionsInContravariantPositions, boolean localTypes,
                         boolean anonymousTypes, boolean typeVariables, Predicate<KType.Captured> capturedTypes) {
        /** Declared types of local variables and functions. */
        public static final Config LOCAL_DECLARATION = new Config(IntersectionStrategy.ALLOWED, true, false, null,
                true, false, false, false, c -> true);
        /** Declared types of private declarations: anonymous object types are kept. */
        public static final Config PUBLIC_DECLARATION_SAVE_ANONYMOUS = publicDeclaration(false, false);
        public static final Config PUBLIC_DECLARATION_APPROXIMATE_ANONYMOUS = publicDeclaration(false, true);
        public static final Config PUBLIC_DECLARATION_APPROXIMATE_LOCAL_AND_ANONYMOUS = publicDeclaration(true, true);
        public static final Config INCORPORATION = capturedAndLiterals(c -> isForIncorporation(c)
                || Types.contains(c, t -> t != c && t instanceof KType.Captured n && isForIncorporation(n)), false, false);
        public static final Config SUBTYPE_CAPTURED_TYPES =
                capturedAndLiterals(c -> c.constructor().status() == CapturedTypeConstructor.Status.FOR_SUBTYPING, false, false);
        public static final Config INTERNAL_TYPES =
                capturedAndLiterals(c -> c.constructor().status() == CapturedTypeConstructor.Status.FROM_EXPRESSION, true, true);
        public static final Config FINAL_APPROXIMATION_AFTER_RESOLUTION = INTERNAL_TYPES;
        /** kotlinc approximates captured types with raw supertypes here; raw types are not modelled. */
        public static final Config INTERMEDIATE_APPROXIMATION_AFTER_COMPLETION = capturedAndLiterals(c -> false, true, true);
        public static final Config TYPE_ARGUMENT_AFTER_COMPLETION = capturedAndLiterals(c -> false, true, true);
        public static final Config FRONTEND_TO_BACKEND = new Config(IntersectionStrategy.TO_COMMON_SUPERTYPE, true, true,
                null, true, false, false, true, c -> true);

        private static Config publicDeclaration(boolean localTypes, boolean anonymousTypes) {
            return new Config(IntersectionStrategy.TO_COMMON_SUPERTYPE, true, false, null, true, localTypes,
                    anonymousTypes, false, c -> true);
        }

        private static Config capturedAndLiterals(Predicate<KType.Captured> captured, boolean literals,
                                                  boolean contravariantIntersections) {
            return new Config(IntersectionStrategy.ALLOWED, literals, literals, null, contravariantIntersections,
                    false, false, false, captured);
        }

        private static boolean isForIncorporation(KType.Captured c) {
            return c.constructor().status() == CapturedTypeConstructor.Status.FOR_INCORPORATION;
        }

        /** Integer literal types (also of operators) become the expected type if possible, else their default. */
        public static Config topLevelIntegerLiteral(KType expected) {
            return new Config(IntersectionStrategy.TO_COMMON_SUPERTYPE, true, true, expected, false, false, false,
                    true, c -> true);
        }
    }

    private final TypeSystem ts;
    private final Config config;
    private final Map<KType.Captured, Optional<KType>> superResults = new HashMap<>();
    private final Map<KType.Captured, Optional<KType>> subResults = new HashMap<>();
    private final Set<KType> beingApproximatedToSuper = new HashSet<>();

    private TypeApproximator(TypeSystem ts, Config config) {
        this.ts = ts;
        this.config = config;
    }

    /** A supertype of the type without the types the configuration disallows, or null if the type has none. */
    public static KType approximateToSuperType(TypeSystem ts, KType type, Config config) {
        return new TypeApproximator(ts, config).toSuper(type, -approximationDepth(type));
    }

    /** A subtype of the type without the types the configuration disallows, or null if the type has none. */
    public static KType approximateToSubType(TypeSystem ts, KType type, Config config) {
        return new TypeApproximator(ts, config).toSub(type, -approximationDepth(type));
    }

    /** The type itself if it needs no approximation. */
    public static KType approximateToSuperTypeOrSelf(TypeSystem ts, KType type, Config config) {
        KType result = approximateToSuperType(ts, type, config);
        return result != null ? result : type;
    }

    private static int approximationDepth(KType t) {
        if (t instanceof KType.Captured c) {
            return c.constructor().projection().isStar() ? 1 : CommonSuperTypes.typeDepth(c.constructor().projection().type());
        }
        return CommonSuperTypes.typeDepth(t);
    }

    private KType toSuper(KType type, int depth) {
        return approximate(type, true, depth);
    }

    private KType toSub(KType type, int depth) {
        return approximate(type, false, depth);
    }

    private KType approximate(KType type, boolean toSuper, int depth) {
        if (!(type instanceof KType.Flexible f)) return approximateRigid(type, toSuper, depth);
        // L1..U1 <: L2..U2 needs L1 <: L2 and U1 <: U2: the bounds are approximated separately.
        KType lower = f.lower();
        KType upper = f.upper();
        KType lowerResult = approximateRigid(lower, toSuper, depth);
        KType upperResult;
        if (shouldApproximateUpperBoundSeparately(lower, upper)) {
            upperResult = approximateRigid(upper, toSuper, depth);
        } else {
            upperResult = lowerResult == null ? null : lowerResult.withNullable(upper.nullable());
        }
        if (lowerResult == null && upperResult == null) return null;
        return Types.flexible(lowerResult != null ? Types.lowerBound(lowerResult) : lower,
                upperResult != null ? Types.upperBound(upperResult) : upper);
    }

    /** Flexible arrays {@code Array<X>..Array<out X>?} become {@code Array<X>..Array<Captured(out X)>?} when captured. */
    private boolean shouldApproximateUpperBoundSeparately(KType lower, KType upper) {
        Object upperConstructor = SubtypeChecker.constructor(upper);
        if (!SubtypeChecker.constructor(lower).equals(upperConstructor)) return true;
        return upperConstructor instanceof ClassSymbol cls && cls.id().fqName().equals("kotlin.Array")
                && upper instanceof KType.ClassType u && !u.args().isEmpty() && !u.args().getFirst().isStar()
                && u.args().getFirst().type() instanceof KType.Captured c && config.capturedTypes().test(c);
    }

    private KType approximateRigid(KType type, boolean toSuper, int depth) {
        return switch (type) {
            case KType.ClassType c when !c.args().isEmpty() -> approximateParametrized(c, toSuper, depth + 1);
            case KType.ClassType c -> approximateLocalTypes(c, toSuper, depth);
            case KType.DefinitelyNotNull d -> approximateDefinitelyNotNull(d, toSuper, depth);
            case KType.Captured c -> approximateCaptured(c, toSuper, depth);
            case KType.Intersection i -> approximateIntersection(i, toSuper, depth);
            case KType.TypeVariableType v -> config.typeVariables() ? defaultResult(v, toSuper) : null;
            case KType.IntegerLiteral l -> {
                if (!config.integerLiterals()) yield null;
                if (config.expectedTypeForIntegerLiteral() != null && depth > 0) {
                    throw new IllegalStateException("Expected type for a nested integer literal type " + l);
                }
                yield integerLiteralType(l, config.expectedTypeForIntegerLiteral()).withNullable(l.nullable());
            }
            case KType.TypeParamType p -> null;
            case KType.Flexible f -> throw new IllegalArgumentException("Flexible type " + f);
        };
    }

    /** The expected type if it is one of the possible types, else the first possible type. */
    public static KType.ClassType integerLiteralType(KType.IntegerLiteral literal, KType expected) {
        if (expected != null && Types.lowerBound(expected) instanceof KType.ClassType e
                && literal.possibleTypes().contains(e.withNullable(false))) {
            return e.withNullable(false);
        }
        return literal.possibleTypes().getFirst();
    }

    private KType approximateDefinitelyNotNull(KType.DefinitelyNotNull type, boolean toSuper, int depth) {
        KType original = type.inner();
        KType approximated = toSuper ? toSuper(original, depth) : toSub(original, depth);
        // T & Any is T if T is not null anyway.
        KType notNull = original.withNullable(false);
        if (original instanceof KType.TypeParamType && !Types.canBeNull(notNull)) return notNull;
        return approximated == null ? null : Types.makeDefinitelyNotNull(approximated);
    }

    private KType approximateIntersection(KType.Intersection type, boolean toSuper, int depth) {
        boolean approximated = false;
        List<KType> newTypes = new ArrayList<>();
        for (KType c : type.components()) {
            KType r = toSuper ? toSuper(c, depth) : toSub(c, depth);
            approximated |= r != null;
            newTypes.add(r != null ? r : c);
        }
        // With ALLOWED, A <: A' and B <: B' give A & B <: A' & B'. Otherwise the only subtype is Nothing.
        return switch (config.intersectionStrategy()) {
            case ALLOWED -> approximated ? ts.intersect(newTypes) : null;
            case TO_FIRST -> toSuper ? newTypes.getFirst() : defaultResult(type, false);
            case TO_COMMON_SUPERTYPE -> {
                if (!toSuper) yield defaultResult(type, false);
                KType cst = ts.commonSuperType(newTypes);
                KType r = toSuper(cst, depth);
                yield r != null ? r : cst;
            }
        };
    }

    private KType approximateCaptured(KType.Captured type, boolean toSuper, int depth) {
        Map<KType.Captured, Optional<KType>> results = toSuper ? superResults : subResults;
        Optional<KType> cached = results.get(type);
        if (cached != null) return cached.orElse(null);
        if (toSuper && !beingApproximatedToSuper.add(type)) {
            throw new IllegalStateException("Captured types loop should be handled at approximateParametrized: " + type);
        }
        KType result = doApproximateCaptured(type, toSuper, depth);
        if (toSuper) beingApproximatedToSuper.remove(type);
        // Only those from expressions live long enough to be worth caching.
        if (type.constructor().status() == CapturedTypeConstructor.Status.FROM_EXPRESSION) {
            results.put(type, Optional.ofNullable(result));
        }
        return result;
    }

    private KType doApproximateCaptured(KType.Captured type, boolean toSuper, int depth) {
        if (!config.capturedTypes().test(type)) return null;
        List<KType> supertypes = type.constructor().supertypes() == null ? List.of() : type.constructor().supertypes();
        // For Captured(in Int), the supertype is Any?. With several supertypes, kotlinc takes the projected
        // type rather than their intersection: KClass<Captured(out A!)> becomes KClass<out A!>, not KClass<out A>.
        KType baseSuper = switch (supertypes.size()) {
            case 0 -> ts.nullableAny();
            case 1 -> supertypes.getFirst();
            default -> type.constructor().projection().isStar() ? ts.intersect(supertypes)
                    : type.constructor().projection().type();
        };
        KType baseSub = type.lowerType() != null ? type.lowerType() : ts.nothing();
        KType result;
        if (toSuper) {
            KType r = toSuper(baseSuper, depth);
            result = r != null ? r : baseSuper;
        } else {
            KType r = toSub(baseSub, depth);
            result = r != null ? r : baseSub;
        }
        // C = in Int, Int <: C gives Int? <: C?; C = out Number, C <: Number gives C? <: Number?.
        return type.nullable() ? result.withNullable(true) : result;
    }

    private KType approximateParametrized(KType.ClassType type, boolean toSuper, int depth) {
        List<TypeParamSymbol> params = Overrides.allTypeParams(type.symbol());
        if (params.size() != type.args().size()) return defaultResult(type, toSuper);
        TypeArg[] newArgs = new TypeArg[params.size()];

        for (int i = 0; i < params.size(); i++) {
            TypeParamSymbol param = params.get(i);
            TypeArg arg = type.args().get(i);
            if (arg.isStar()) continue;
            Variance variance = SubtypeChecker.effectiveVariance(param.variance(), arg.variance());
            if (variance == null) return defaultResult(type, toSuper);
            KType argType = arg.type();
            KType simple = Types.lowerBound(argType);
            if (simple instanceof KType.DefinitelyNotNull d) simple = d.inner();
            KType.Captured captured = simple instanceof KType.Captured c ? c : null;

            if (shouldApproximateStarCapturedAsProjection(captured, param, variance, toSuper)) {
                newArgs[i] = captured.constructor().projection();
                continue;
            }
            if (variance != Variance.INVARIANT) {
                if (shouldApproximateContravariantIntersectionToStar(argType, variance)) {
                    newArgs[i] = TypeArg.STAR;
                    continue;
                }
                // Out<Foo> <: Out<superType(Foo)>; In<Foo> <: In<subType(Foo)>
                KType approximated;
                if ((variance == Variance.OUT) == toSuper) {
                    if (isRecursion(simple)) {
                        newArgs[i] = recursionArgument(simple, captured);
                        continue;
                    }
                    approximated = toSuper(argType, depth);
                } else {
                    approximated = toSub(argType, depth);
                }
                if (approximated == null) continue;
                if (useStarForIntersectionViolatingBounds(variance, param, argType, approximated)) {
                    newArgs[i] = TypeArg.STAR;
                } else {
                    newArgs[i] = param.variance() == Variance.INVARIANT ? new TypeArg(variance, approximated)
                            : TypeArg.of(approximated);
                }
                continue;
            }

            KType subType = toSub(argType, depth);
            if (!toSuper) {
                // Inv<Foo> has no subtype but itself.
                if (subType == null) continue;
                // Inv<Foo!> is a supertype of Inv<Foo?>; Captured(out Nothing) is Nothing.
                if (!ts.equalTypes(argType, subType)) return defaultResult(type, false);
                newArgs[i] = TypeArg.of(subType);
                continue;
            }
            // Inv<C> for C = Captured(in Int) becomes Inv<in Int>, since Int <: C. The lower bound
            // matters when there is a non-trivial upper bound too, as for Inv<T : CharSequence>.
            if (SubtypeChecker.constructor(argType) instanceof CapturedTypeConstructor) {
                if (subType == null) continue;
                if (shouldUseSubTypeForCapturedArgument(subType, argType, depth)) {
                    newArgs[i] = new TypeArg(Variance.IN, subType);
                    continue;
                }
            }
            if (isRecursion(simple)) {
                newArgs[i] = recursionArgument(simple, captured);
                continue;
            }
            KType superType = toSuper(argType, depth);
            if (superType == null) continue;
            boolean trivialSuper = isNullableAny(Types.upperBound(superType));
            if (trivialSuper && subType == null) continue;
            if (trivialSuper && !isTrivialSub(subType)) {
                // Inv<In<C>> for C = Captured(in Int) could be Inv<out In<Int>> or Inv<in In<Any?>>.
                newArgs[i] = new TypeArg(Variance.IN, subType);
            } else if (ts.equalTypes(argType, superType)) {
                newArgs[i] = TypeArg.of(superType);
            } else {
                newArgs[i] = new TypeArg(Variance.OUT, superType);
            }
        }

        boolean changed = false;
        List<TypeArg> args = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            changed |= newArgs[i] != null;
            args.add(newArgs[i] != null ? newArgs[i] : type.args().get(i));
        }
        if (!changed) return approximateLocalTypes(type, toSuper, depth);
        KType.ClassType result = new KType.ClassType(type.symbol(), args, type.nullable(), type.extensionFunction(),
                type.contextParams());
        KType local = approximateLocalTypes(result, toSuper, depth);
        return local != null ? local : result;
    }

    /** Whether the argument is already being approximated to a supertype further up: a recursive type. */
    private boolean isRecursion(KType simple) {
        return beingApproximatedToSuper.contains(simple);
    }

    /** A star for a recursive captured or local type; null keeps the argument as it is. */
    private TypeArg recursionArgument(KType simple, KType.Captured captured) {
        boolean approximate = captured != null && config.capturedTypes().test(captured)
                || simple instanceof KType.ClassType c && requiresLocalOrAnonymousApproximation(c.symbol());
        return approximate ? TypeArg.STAR : null;
    }

    /**
     * Whether a {@code Captured(*)} argument becomes a star again: {@code List<*>}
     * reads better than {@code List<out Any?>}. Not for a captured type of
     * another class's parameter: from {@code A<T : CharSequence>}, a
     * {@code List<Captured(*)>} becomes {@code List<out CharSequence>}.
     */
    private boolean shouldApproximateStarCapturedAsProjection(KType.Captured captured, TypeParamSymbol param,
                                                              Variance variance, boolean toSuper) {
        if (captured == null || !captured.constructor().projection().isStar()) return false;
        // SomeClass<Captured(*)> has no subtype SomeClass<*>.
        if (!toSuper) return false;
        if (!config.capturedTypes().test(captured)) return false;
        // In<*> reads better than In<Nothing>.
        if ((captured.lowerType() == null || isTrivialSub(captured.lowerType())) && param.variance() == Variance.IN) {
            return true;
        }
        return captured.constructor().typeParam() == param;
    }

    /** {@code In<Int & String>} has no values but null; it becomes {@code In<*>}. */
    private boolean shouldApproximateContravariantIntersectionToStar(KType argType, Variance variance) {
        return config.intersectionsInContravariantPositions() && variance == Variance.IN
                && argType instanceof KType.Intersection i
                && i.components().stream().anyMatch(c -> !c.nullable() && isNumberType(c));
    }

    private static boolean isNumberType(KType t) {
        return t instanceof KType.ClassType c && c.symbol().id().packageName().equals("kotlin")
                && NUMBER_TYPES.contains(c.symbol().name());
    }

    private static final Set<String> NUMBER_TYPES = Set.of("Byte", "Short", "Int", "Long", "Float", "Double",
            "UByte", "UShort", "UInt", "ULong");

    /** An intersection argument approximated beyond the parameter's bounds becomes a star. */
    private boolean useStarForIntersectionViolatingBounds(Variance variance, TypeParamSymbol param, KType argType,
                                                         KType approximated) {
        if (config.intersectionStrategy() == IntersectionStrategy.ALLOWED) return false;
        if (variance != Variance.OUT || !(argType instanceof KType.Intersection)) return false;
        for (KType bound : param.bounds()) {
            if (!ts.isSubtype(approximated, bound)) return true;
        }
        return false;
    }

    private boolean shouldUseSubTypeForCapturedArgument(KType subType, KType capturedArgType, int depth) {
        if (isTrivialSub(subType)) return false;
        // Not Nothing?: the lower bound is non-trivial.
        if (!(Types.lowerBound(subType) instanceof KType.ClassType c && c.symbol() == ts.nothingClass() && c.nullable())) {
            return true;
        }
        // Nothing? from the captured type's lower bound rather than its own nullability.
        if (!capturedArgType.nullable()) return true;
        KType notNullSub = toSub(capturedArgType.withNullable(false), depth);
        if (notNullSub == null) throw new IllegalStateException("No subtype approximation for " + capturedArgType);
        return !isTrivialSub(notNullSub);
    }

    // ==== Local and anonymous types ====

    private boolean requiresLocalOrAnonymousApproximation(ClassSymbol cls) {
        return config.localTypes() && cls.isLocal() || config.anonymousTypes() && cls.isAnonymous();
    }

    /**
     * A local type becomes its nearest non-local supertype other than Any,
     * found breadth-first, or Any; an anonymous object type its first
     * supertype.
     */
    private KType approximateLocalTypes(KType.ClassType type, boolean toSuper, int depth) {
        if (!toSuper || !config.localTypes() && !config.anonymousTypes()) return null;
        if (!requiresLocalOrAnonymousApproximation(type.symbol())) return null;
        SubtypeChecker checker = new SubtypeChecker(ts);
        KType result = null;
        if (config.localTypes()) {
            Set<KType> visited = new HashSet<>();
            Deque<KType> queue = new ArrayDeque<>();
            queue.add(type);
            while (!queue.isEmpty()) {
                KType current = queue.removeFirst();
                if (!visited.add(current)) continue;
                if (!(current instanceof KType.ClassType cc) || !requiresLocalOrAnonymousApproximation(cc.symbol())) {
                    result = current.withNullable(type.nullable());
                    break;
                }
                for (KType s : cc.symbol().supertypes()) {
                    for (KType found : checker.findCorrespondingSupertypes(type, SubtypeChecker.constructor(s))) {
                        if (SubtypeChecker.constructor(found) != ts.anyClass()) queue.add(found);
                    }
                }
            }
            if (result == null) result = ts.any().withNullable(type.nullable());
        } else {
            Object superConstructor = SubtypeChecker.constructor(type.symbol().supertypes().getFirst());
            List<KType> found = checker.findCorrespondingSupertypes(type, superConstructor);
            if (found.isEmpty()) return null;
            result = found.getFirst().withNullable(type.nullable());
        }
        // Finding supertypes captures projections, which must not leak: for an anonymous
        // object type _anonymous_<in Number>, Invariant<Captured(in Number)>.
        beingApproximatedToSuper.add(type);
        KType approximated = approximate(result, true, depth);
        beingApproximatedToSuper.remove(type);
        return approximated != null ? approximated : result;
    }

    // ==== Trivial bounds ====

    private KType defaultResult(KType type, boolean toSuper) {
        if (toSuper) return ts.nullableAny();
        return type instanceof KType.ClassType && type.nullable() ? ts.nullableNothing() : ts.nothing();
    }

    private boolean isNullableAny(KType t) {
        return t instanceof KType.ClassType c && c.symbol() == ts.anyClass() && c.nullable();
    }

    /** Nothing or Nothing!. */
    private boolean isTrivialSub(KType t) {
        return ts.isNothing(Types.lowerBound(t));
    }
}
