package kotlinbootstrap.types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.TypeParamSymbol;

/**
 * Computes the common supertype (least upper bound) of types, as kotlinc's
 * NewCommonSuperTypeCalculator: the intersection of the most specific
 * classes all types share, with arguments computed recursively up to a
 * depth, beyond which they become stars.
 */
final class CommonSuperTypes {
    private final TypeSystem ts;
    private final SubtypeChecker checker;

    CommonSuperTypes(TypeSystem ts) {
        this.ts = ts;
        this.checker = new SubtypeChecker(ts);
    }

    KType commonSuperType(List<KType> types) {
        if (types.size() == 1) return types.getFirst();
        int maxDepth = types.stream().mapToInt(CommonSuperTypes::typeDepth).max().orElse(0);
        return commonSuperType(types, -maxDepth, true);
    }

    private KType commonSuperType(List<KType> types, int depth, boolean topLevel) {
        if (types.isEmpty()) throw new IllegalArgumentException("Empty list of types");
        if (types.size() == 1) return types.getFirst();

        boolean flexible = types.stream().anyMatch(t -> t instanceof KType.Flexible);
        KType lower = commonSuperTypeOfRigid(types.stream().map(Types::lowerBound).toList(), depth);
        if (!flexible) return lower;
        KType upper = commonSuperTypeOfRigid(types.stream().map(Types::upperBound).toList(), depth);

        if (!topLevel) {
            List<KType> nonVariables = types.stream()
                    .filter(t -> !isTypeVariable(Types.lowerBound(t)) && !isTypeVariable(Types.upperBound(t))).toList();
            for (KType candidate : nonVariables) {
                // Like kotlinc, picks the first rather than merging the flexibility of arguments.
                if (nonVariables.stream().allMatch(t -> checker.equalTypes(t, candidate))) return candidate;
            }
        }
        return Types.flexible(lower, upper);
    }

    private KType commonSuperTypeOfRigid(List<KType> types, int depth) {
        // The result is nullable unless all types are non-null.
        boolean allNotNull = types.stream().allMatch(t -> isTypeVariable(t) || Types.isSubtypeOfAny(t));
        List<KType> notNull = allNotNull ? types : types.stream().map(t -> t.withNullable(false)).toList();
        KType result = commonSuperTypeOfNotNull(notNull, depth);
        if (allNotNull) return result;
        KType refined = refineNullabilityForUndefinedNullability(types, result);
        return refined != null ? refined : result.withNullable(true);
    }

    /** For a type parameter result: non-null after all if every type reaches it through non-null types. */
    private KType refineNullabilityForUndefinedNullability(List<KType> types, KType result) {
        if (!(result instanceof KType.Captured || result instanceof KType.TypeVariableType
                || result instanceof KType.TypeParamType)) {
            return null;
        }
        Object constructor = SubtypeChecker.constructor(result);
        return types.stream().allMatch(t -> checker.hasPathByNotMarkedNullableNodes(t, constructor)) ? result : null;
    }

    private KType commonSuperTypeOfNotNull(List<KType> types, int depth) {
        if (types.size() == 1) return types.getFirst();
        List<KType> nonVariables = types.stream().filter(t -> !isTypeVariable(t)).toList();
        if (nonVariables.isEmpty()) {
            throw new IllegalStateException("No proper type to compute a common supertype of: " + types);
        }
        List<KType> unique = uniquify(nonVariables);
        if (unique.size() == 1) return unique.getFirst();
        List<KType> explicit = filterSupertypes(unique);
        if (explicit.size() == 1) return explicit.getFirst();
        KType literal = commonIntegerLiteralSuperType(explicit);
        if (literal != null) return literal;
        List<KType> supertypes = new ArrayList<>();
        for (Object c : allCommonSuperTypeConstructors(explicit)) supertypes.add(superTypeWithConstructor(explicit, c, depth));
        return ts.intersect(supertypes);
    }

    /**
     * A type variable, or a captured type of a projection of one. kotlinc
     * replaces type variables by stub types before computing common
     * supertypes; they stand for any type.
     */
    private static boolean isTypeVariable(KType t) {
        if (t instanceof KType.TypeVariableType) return true;
        if (t instanceof KType.DefinitelyNotNull d) t = d.inner();
        return t instanceof KType.Captured c && !c.constructor().projection().isStar()
                && c.constructor().projection().type() instanceof KType.TypeVariableType;
    }

    /** One of each set of equal types: (A, B, A) gives (A, B). */
    private List<KType> uniquify(List<KType> types) {
        List<KType> unique = new ArrayList<>();
        for (KType t : types) {
            boolean isNew = unique.stream().allMatch(u -> {
                boolean equal = checker.equalTypes(u, t) && !(u instanceof KType.IntegerLiteral);
                return !equal || hasDifferentFlexibilityAtDepth(List.of(u, t));
            });
            if (isNew) unique.add(t);
        }
        return unique;
    }

    /** Drops the types that are subtypes of others: CST(A, B, A0, B0) for A : A0, B : B0 is CST(A0, B0). */
    private List<KType> filterSupertypes(List<KType> types) {
        List<KType> result = new ArrayList<>(types);
        for (var it = result.iterator(); it.hasNext(); ) {
            KType potentialSubtype = it.next();
            boolean isSubtype = false;
            for (KType sup : result) {
                if (sup != potentialSubtype && checker.isSubtype(potentialSubtype, sup)
                        && !hasDifferentFlexibilityAtDepth(List.of(potentialSubtype, sup))) {
                    isSubtype = true;
                    break;
                }
            }
            if (isSubtype) it.remove();
        }
        return result;
    }

    private static boolean hasDifferentFlexibilityAtDepth(List<KType> types) {
        if (types.isEmpty()) return false;
        KType first = types.getFirst();
        if (!types.stream().allMatch(t -> t == first)) {
            long flexible = types.stream().filter(t -> t instanceof KType.Flexible).count();
            if (flexible != 0 && flexible != types.size()) return true;
        }
        int argCount = args(first).size();
        for (int i = 0; i < argCount; i++) {
            List<KType> argTypes = new ArrayList<>();
            for (KType t : types) {
                List<TypeArg> args = args(t);
                if (args.size() > i && !args.get(i).isStar()) argTypes.add(args.get(i).type());
            }
            if (hasDifferentFlexibilityAtDepth(argTypes)) return true;
        }
        return false;
    }

    private static List<TypeArg> args(KType t) {
        return Types.lowerBound(t) instanceof KType.ClassType c ? c.args() : List.of();
    }

    /**
     * The common supertype by the special rules for integer literal types:
     * the literal type with the possible types they share, or the class
     * type that is a possible type of all of them. Null if the rules do not apply.
     */
    private static KType commonIntegerLiteralSuperType(List<KType> types) {
        KType result = types.getFirst();
        for (KType t : types.subList(1, types.size())) {
            if (result instanceof KType.IntegerLiteral l && t instanceof KType.IntegerLiteral r) {
                List<KType.ClassType> possible = new ArrayList<>(l.possibleTypes());
                possible.retainAll(r.possibleTypes());
                result = new KType.IntegerLiteral(l.value(), possible, l.unsigned(), l.nullable());
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

    /**
     * The constructors of supertypes that all types share, without those
     * that are supertypes of others among them.
     */
    private List<Object> allCommonSuperTypeConstructors(List<KType> types) {
        Set<Object> result = collectAllSupertypeConstructors(types.getFirst());
        for (KType t : types.subList(1, types.size())) result.retainAll(collectAllSupertypeConstructors(t));
        List<Object> filtered = new ArrayList<>();
        for (Object target : result) {
            boolean lessPrecise = false;
            for (Object other : result) {
                if (!other.equals(target) && constructorSupertypes(other).stream()
                        .anyMatch(s -> SubtypeChecker.constructor(s).equals(target))) {
                    lessPrecise = true;
                    break;
                }
            }
            if (!lessPrecise) filtered.add(target);
        }
        return filtered;
    }

    private Set<Object> collectAllSupertypeConstructors(KType type) {
        Set<Object> result = new LinkedHashSet<>();
        checker.anySupertype(type, t -> {
            result.add(SubtypeChecker.constructor(t));
            return false;
        }, t -> Types::lowerBound);
        return result;
    }

    private List<KType> constructorSupertypes(Object constructor) {
        return switch (constructor) {
            case ClassSymbol cls -> cls.supertypes();
            case TypeParamSymbol tp -> tp.bounds();
            case CapturedTypeConstructor c -> c.supertypes() == null ? List.of() : c.supertypes();
            case KType t -> ts.supertypes(t);
            default -> List.of();
        };
    }

    /** The common supertype with a given constructor, with arguments from the types' supertypes with it. */
    private KType superTypeWithConstructor(List<KType> types, Object constructor, int depth) {
        if (!(constructor instanceof ClassSymbol cls)) return typeOfConstructor(constructor);
        List<TypeParamSymbol> params = Overrides.allTypeParams(cls);
        boolean extension = types.stream().allMatch(t -> t instanceof KType.ClassType c && c.extensionFunction());
        if (params.isEmpty()) return new KType.ClassType(cls, List.of(), false, extension, 0);

        // A type can have several supertypes with the constructor, e.g. A <: List<Int> and A <: List<Double>;
        // kotlinc then takes the common supertype of all of them, which is not quite precise.
        List<KType.ClassType> corresponding = new ArrayList<>();
        for (KType t : types) {
            for (KType s : checker.findCorrespondingSupertypes(t, cls)) corresponding.add((KType.ClassType) s);
        }
        List<TypeArg> args = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            TypeParamSymbol param = params.get(i);
            boolean star = false;
            List<TypeArg> projections = new ArrayList<>();
            for (KType.ClassType s : corresponding) {
                if (s.args().size() <= i) continue;
                // Types captured for subtyping are only intermediate results of finding the supertypes.
                TypeArg arg = uncaptureFromSubtyping(s.args().get(i));
                if (arg.isStar()) {
                    star = true;
                } else if (!(Types.lowerBound(arg.type()) instanceof KType.TypeVariableType)) {
                    projections.add(arg);
                }
            }
            TypeArg arg;
            if (star || projections.isEmpty() || checkRecursion(types, projections, param)) {
                arg = TypeArg.STAR;
            } else {
                arg = calculateArgument(param, projections, depth);
                // Fold recursive types like Inv<Inv<*>> into Inv<*>.
                if (arg.variance() == Variance.OUT && arg.type() instanceof KType.ClassType at && at.symbol() == cls
                        && at.args().get(i).isStar()) {
                    arg = TypeArg.STAR;
                }
            }
            args.add(arg);
        }
        return new KType.ClassType(cls, args, false, extension, 0);
    }

    private static KType typeOfConstructor(Object constructor) {
        return switch (constructor) {
            case TypeParamSymbol tp -> new KType.TypeParamType(tp, false);
            case TypeVariable v -> v.type();
            case CapturedTypeConstructor c -> new KType.Captured(c,
                    !c.projection().isStar() && c.projection().variance() == Variance.IN ? c.projection().type() : null,
                    false);
            case KType t -> t;
            default -> throw new IllegalArgumentException("Unknown type constructor " + constructor);
        };
    }

    private static TypeArg uncaptureFromSubtyping(TypeArg arg) {
        if (arg.isStar()) return arg;
        KType t = arg.type() instanceof KType.DefinitelyNotNull d ? d.inner() : arg.type();
        if (t instanceof KType.Captured c && c.constructor().status() == CapturedTypeConstructor.Status.FOR_SUBTYPING) {
            return c.constructor().projection();
        }
        return arg;
    }

    /**
     * Whether computing the argument would recurse forever: the arguments
     * are the types themselves, e.g. for types {@code Some<C>} where C is a
     * captured type whose supertype is {@code Some<C>}, for
     * {@code interface Some<T : Some<T>>}. Captured star projections stand
     * for their supertypes here.
     */
    private boolean checkRecursion(List<KType> originalTypes, List<TypeArg> args, TypeParamSymbol param) {
        if (param.variance() == Variance.IN) return false;
        Set<KType> originals = new HashSet<>();
        for (KType t : originalTypes) originals.add(t instanceof KType.DefinitelyNotNull d ? d.inner() : t);
        Set<KType> argTypes = new HashSet<>();
        for (TypeArg a : args) {
            KType t = Types.lowerBound(a.type());
            argTypes.add(t instanceof KType.DefinitelyNotNull d ? d.inner() : t);
        }
        if (originals.size() != argTypes.size()) return false;

        Set<Object> originalConstructors = new HashSet<>();
        for (KType t : originals) {
            List<KType> starSupertypes = supertypesIfCapturedStar(t);
            if (starSupertypes != null) {
                for (KType s : starSupertypes) originalConstructors.add(SubtypeChecker.constructor(Types.lowerBound(s)));
            } else {
                originalConstructors.add(SubtypeChecker.constructor(t));
            }
        }
        for (KType argType : argTypes) {
            if (originals.contains(argType)) continue;
            List<KType> starSupertypes = supertypesIfCapturedStar(argType);
            if (starSupertypes == null || starSupertypes.isEmpty()) return false;
            for (KType s : starSupertypes) {
                if (!originalConstructors.contains(SubtypeChecker.constructor(Types.lowerBound(s)))) return false;
            }
        }
        return true;
    }

    private static List<KType> supertypesIfCapturedStar(KType t) {
        if (!(t instanceof KType.Captured c) || !c.constructor().projection().isStar()) return null;
        return c.constructor().supertypes() == null ? List.of() : c.constructor().supertypes();
    }

    /**
     * {@code CS(Out<X>, Out<Y>) = Out<CS(X, Y)>}; {@code CS(In<X>, In<Y>) = In<X & Y>};
     * for an invariant parameter, {@code Inv<X>} if all are equal,
     * {@code Inv<out CS(X, Y)>} otherwise, or a star for mixed in and out projections.
     */
    private TypeArg calculateArgument(TypeParamSymbol param, List<TypeArg> args, int depth) {
        if (depth > 0) return TypeArg.STAR;
        TypeArg first = args.getFirst();
        if (param.variance() == Variance.INVARIANT && args.stream().allMatch(a -> a.variance() == Variance.INVARIANT)
                && args.stream().allMatch(a -> a.type().equals(first.type()))) {
            return first;
        }

        boolean asOut;
        if (param.variance() != Variance.INVARIANT) {
            asOut = param.variance() == Variance.OUT;
        } else {
            boolean thereIsOut = args.stream().anyMatch(a -> a.variance() == Variance.OUT);
            boolean thereIsIn = args.stream().anyMatch(a -> a.variance() == Variance.IN);
            if (thereIsOut && thereIsIn) return TypeArg.STAR;
            asOut = thereIsOut || !thereIsIn;
        }

        List<KType> types = args.stream().map(TypeArg::type).toList();
        if (!asOut) {
            KType type = ts.intersect(types);
            return param.variance() != Variance.INVARIANT ? TypeArg.of(type) : new TypeArg(Variance.IN, type);
        }
        if (param.variance() != Variance.INVARIANT) return TypeArg.of(commonSuperType(types, depth + 1, false));

        for (TypeArg candidate : args) {
            if (args.stream().allMatch(a -> checker.equalTypes(a.type(), candidate.type()))) {
                boolean thereIsNotInvariant = args.stream().anyMatch(a -> a.variance() != Variance.INVARIANT);
                return new TypeArg(thereIsNotInvariant ? Variance.OUT : Variance.INVARIANT, candidate.type());
            }
        }
        KType cst = commonSuperType(types, depth + 1, false);
        boolean allEqual = types.stream().allMatch(t -> checker.equalTypes(t, cst));
        return new TypeArg(allEqual ? Variance.INVARIANT : Variance.OUT, cst);
    }

    /** 1 for a type without arguments, plus 1 for each level of nested arguments. */
    static int typeDepth(KType t) {
        return switch (t) {
            case KType.Flexible f -> Math.max(typeDepth(f.lower()), typeDepth(f.upper()));
            case KType.DefinitelyNotNull d -> typeDepth(d.inner());
            case KType.ClassType c -> {
                int max = 0;
                for (TypeArg a : c.args()) max = Math.max(max, a.isStar() ? 1 : typeDepth(a.type()));
                yield max + 1;
            }
            default -> 1;
        };
    }
}
