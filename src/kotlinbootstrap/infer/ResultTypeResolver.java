package kotlinbootstrap.infer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.types.CapturedTypeConstructor;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.SubtypeChecker;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;

/**
 * The type a variable is fixed to, from its constraints, as kotlinc's
 * ResultTypeResolver: the type of an equality constraint, else the common
 * supertype of the lower bounds or the intersection of the upper bounds,
 * whichever satisfies all the constraints.
 */
final class ResultTypeResolver {
    enum Direction { TO_SUBTYPE, TO_SUPERTYPE, UNKNOWN }

    private final TypeSystem ts;

    ResultTypeResolver(TypeSystem ts) {
        this.ts = ts;
    }

    KType findResultType(ConstraintSystem c, VariableWithConstraints v, Direction direction) {
        KType result = findResultTypeOrNull(c, v, direction);
        if (result != null) return result;
        // No proper constraints.
        return defaultType(v, direction);
    }

    private KType defaultType(VariableWithConstraints v, Direction direction) {
        KType selfType = defaultTypeForSelfType(v);
        if (selfType != null) return selfType;
        return direction == Direction.TO_SUBTYPE ? ts.nothing() : ts.nullableAny();
    }

    /**
     * For {@code T : Comparable<T>} without other constraints, a captured
     * star whose supertype is the bound, {@code Comparable<*>} in place of T.
     */
    private KType defaultTypeForSelfType(VariableWithConstraints v) {
        TypeVariable variable = v.variable();
        TypeParamSymbol typeParam = variable.typeParam();
        if (typeParam == null) return null;
        List<KType> typesForRecursiveTypeParameters = new ArrayList<>();
        for (Constraint constraint : v.constraints()) {
            if (constraint.position().fromKind() != ConstraintPosition.Kind.DECLARED_UPPER_BOUND) continue;
            KType t = extractTypeForGivenRecursiveTypeParameter(constraint.type(), typeParam);
            if (t != null) typesForRecursiveTypeParameters.add(t);
        }
        if (typesForRecursiveTypeParameters.isEmpty()) return null;

        // Other variables would be uninferred error types in kotlinc; there are none here.
        List<KType> superTypes = typesForRecursiveTypeParameters.stream()
                .map(t -> replaceArgumentsDeeply(t,
                        a -> a.isStar() || InferenceTypes.typeVariable(a.type()) == null ? a : TypeArg.STAR))
                .toList();
        CapturedTypeConstructor constructor =
                new CapturedTypeConstructor(TypeArg.STAR, null, CapturedTypeConstructor.Status.FROM_EXPRESSION);
        constructor.setSupertypes(List.of(ts.intersect(superTypes)));
        return new KType.Captured(constructor, null, false);
    }

    /** The type (or nested type) with the variable for the recursive parameter as an argument. */
    private static KType extractTypeForGivenRecursiveTypeParameter(KType type, TypeParamSymbol typeParam) {
        if (!(Types.lowerBound(type) instanceof KType.ClassType c)) return null;
        for (TypeArg arg : c.args()) {
            if (arg.isStar()) continue;
            if (arg.type() instanceof KType.TypeVariableType v && v.variable().typeParam() == typeParam
                    && SubtypeChecker.hasRecursiveBounds(typeParam, SubtypeChecker.constructor(type))) {
                return type;
            }
            KType nested = extractTypeForGivenRecursiveTypeParameter(arg.type(), typeParam);
            if (nested != null) return nested;
        }
        return null;
    }

    private static KType replaceArgumentsDeeply(KType type, java.util.function.UnaryOperator<TypeArg> replacement) {
        return switch (type) {
            case KType.Flexible f -> new KType.Flexible(replaceArgumentsDeeply(f.lower(), replacement),
                    replaceArgumentsDeeply(f.upper(), replacement));
            case KType.ClassType c -> {
                List<TypeArg> args = new ArrayList<>();
                for (TypeArg a : c.args()) {
                    TypeArg replaced = a;
                    if (!a.isStar() && InferenceTypes.argumentsCount(a.type()) > 0) {
                        replaced = new TypeArg(a.variance(), replaceArgumentsDeeply(a.type(), replacement));
                    }
                    args.add(replacement.apply(replaced));
                }
                yield new KType.ClassType(c.symbol(), args, c.nullable(), c.extensionFunction(), c.contextParams());
            }
            default -> type;
        };
    }

    // ==== Result types ====

    KType findResultTypeOrNull(ConstraintSystem c, VariableWithConstraints v, Direction direction) {
        KType fromEquality = findResultIfThereIsEqualsConstraint(c, v);
        // ILT types from equality constraints are less precise than Int etc. from other constraints.
        if (fromEquality != null && !InferenceTypes.contains(fromEquality, InferenceTypes::isIntegerLiteral)) {
            return fromEquality;
        }

        KType subType = findSubType(c, v);
        KType superType = findSuperType(c, v);
        KType[] prepared = prepareSubAndSuperTypes(c, v, subType, superType);

        KType fromDirection = direction == Direction.TO_SUPERTYPE
                ? resultType(c, v, prepared[1], prepared[0])
                : resultType(c, v, prepared[0], prepared[1]);

        if (fromEquality == null) return fromDirection;
        if (fromDirection == null) return fromEquality;
        // E.g. Int from an upper constraint is more precise than an ILT.
        if (!InferenceTypes.isNothingConstructor(ts, fromDirection) && ts.isSubtype(fromDirection, fromEquality)) {
            return fromDirection;
        }
        return fromEquality;
    }

    /**
     * ILTs are always approximated, captured types unless that gives a
     * contradiction, as for {@code A<C> <: T <: A<C>} with a captured C.
     */
    private KType[] prepareSubAndSuperTypes(ConstraintSystem c, VariableWithConstraints v, KType subType, KType superType) {
        KType approximatedSubType = subType == null ? null : approximateToSuperTypeOrSelf(subType, superType);
        KType approximatedSuperType = superType == null ? null : approximateToSubTypeOrSelf(superType);

        KType preparedSubType;
        if (approximatedSubType == null) {
            preparedSubType = null;
        } else if (shouldBeUsedWithoutApproximation(c, subType, approximatedSubType, v)) {
            preparedSubType = subType;
        } else {
            preparedSubType = approximatedSubType;
        }

        KType preparedSuperType;
        if (approximatedSuperType == null) {
            preparedSuperType = null;
        } else if (shouldBeUsedWithoutApproximation(c, superType, approximatedSuperType, v)) {
            preparedSuperType = superType;
        } else if (InferenceTypes.hasRecursiveTypeParametersWithGivenSelfType(SubtypeChecker.constructor(superType))) {
            preparedSuperType = superType;
        } else {
            preparedSuperType = approximatedSuperType;
        }
        // The super type should be the most flexible, the sub type the least.
        return new KType[] {preparedSubType, makeFlexibleIfNecessary(preparedSuperType, v.constraints())};
    }

    private KType approximateToSuperTypeOrSelf(KType type, KType superTypeCandidate) {
        // An ILT becomes the upper type if possible, which is more precise than Int.
        TypeApproximator.Config config = InferenceTypes.isIntegerLiteral(type)
                ? TypeApproximator.Config.topLevelIntegerLiteral(superTypeCandidate)
                : TypeApproximator.Config.INTERNAL_TYPES;
        KType result = TypeApproximator.approximateToSuperType(ts, type, config);
        return result != null ? result : type;
    }

    private KType approximateToSubTypeOrSelf(KType type) {
        KType result = TypeApproximator.approximateToSubType(ts, type, TypeApproximator.Config.INTERNAL_TYPES);
        return result != null ? result : type;
    }

    /** Whether the approximated type would contradict the constraints (the same object: nothing was approximated). */
    private boolean shouldBeUsedWithoutApproximation(ConstraintSystem c, KType type, KType approximated,
                                                     VariableWithConstraints v) {
        if (type == approximated || c.hasContradiction()) return false;
        if (InferenceTypes.isIntegerLiteral(type)) return false;
        return !c.isEqualityConstraintCompatible(approximated, v.variable().type());
    }

    /**
     * Nullability flexibility of type variables in other constraints is
     * kept: with {@code T <: (K..K?)} and {@code T <: Foo?}, the result is
     * {@code Foo!}.
     */
    private static KType makeFlexibleIfNecessary(KType type, List<Constraint> constraints) {
        if (type == null || type instanceof KType.Flexible) return type;
        if (constraints.stream().anyMatch(c -> InferenceTypes.typeVariable(c.type()) != null
                && InferenceTypes.hasFlexibleNullability(c.type()))) {
            return InferenceTypes.createTrivialFlexibleTypeOrSelf(Types.makeDefinitelyNotNull(type));
        }
        return type;
    }

    private KType resultType(ConstraintSystem c, VariableWithConstraints v, KType first, KType second) {
        if (first == null || second == null) return first != null ? first : second;
        // kotlinc keeps the second as an upper bound for approximation, which is not modelled.
        if (first instanceof KType.Intersection && !ts.isSubtype(toPublicType(first), toPublicType(second))) {
            return first;
        }
        if (isSuitableType(c, first, v)) return first;
        return isSuitableType(c, second, v) ? second : first;
    }

    private KType toPublicType(KType type) {
        KType result = TypeApproximator.approximateToSuperType(ts, type,
                TypeApproximator.Config.PUBLIC_DECLARATION_SAVE_ANONYMOUS);
        return result != null ? result : type;
    }

    private boolean isSuitableType(ConstraintSystem c, KType resultType, VariableWithConstraints v) {
        List<Constraint> filtered = v.constraints().stream().filter(x -> isProperConstraint(c, x)).toList();
        for (Constraint constraint : filtered) {
            if (!checkConstraint(constraint.type(), constraint.kind(), resultType)) return false;
        }
        if (ConstraintIncorporator.isSuitableResultedType(ts, resultType)) return true;
        // Nothing and Nothing? are not allowed for reified parameters.
        TypeParamSymbol typeParam = v.variable().typeParam();
        if (typeParam != null && typeParam.reified()) return false;
        if (!InferenceTypes.isNullableType(resultType, true)) {
            // Nothing <: T can't give T = Nothing if it is a nullability constraint; an upper one is preferred.
            return !isThereSingleLowerNullabilityConstraint(filtered);
        }
        return filtered.stream().allMatch(x -> x.kind() != ConstraintKind.UPPER || isFromTypeParameterUpperBound(x));
    }

    private boolean checkConstraint(KType constraintType, ConstraintKind kind, KType resultType) {
        return switch (kind) {
            case EQUALITY -> ts.equalTypes(constraintType, resultType);
            case LOWER -> ts.isSubtype(constraintType, resultType);
            case UPPER -> ts.isSubtype(resultType, constraintType);
        };
    }

    private static boolean isFromTypeParameterUpperBound(Constraint c) {
        return c.position().fromDeclaredUpperBound() || c.position().fromKind() == ConstraintPosition.Kind.DECLARED_UPPER_BOUND;
    }

    private static boolean isThereSingleLowerNullabilityConstraint(List<Constraint> constraints) {
        List<Constraint> lower = constraints.stream().filter(c -> c.kind() == ConstraintKind.LOWER).toList();
        return lower.size() == 1 && lower.getFirst().isNullabilityConstraint();
    }

    // ==== Sub and super types ====

    private KType findSubType(ConstraintSystem c, VariableWithConstraints v) {
        List<KType> lowerTypes = prepareLowerConstraints(c, v.constraints());
        if (lowerTypes.isEmpty()) return null;
        // The common supertype of type variables alone is not defined.
        if (lowerTypes.size() > 1 && lowerTypes.stream().allMatch(t -> isNotFixedVariableOrCaptured(c, t))) {
            return null;
        }
        List<KType> types = sinkIntegerLiteralTypes(lowerTypes);
        KType commonSuperType = ts.commonSuperType(types);

        // Not fixed variables play the part of kotlinc's stub types here.
        if (InferenceTypes.contains(commonSuperType, t -> isNotFixedVariable(c, t))) {
            List<KType> withoutStubs = types.stream()
                    .filter(t -> !InferenceTypes.contains(t, x -> isNotFixedVariable(c, x))).toList();
            if (!withoutStubs.isEmpty()) commonSuperType = ts.commonSuperType(withoutStubs);
        }
        return commonSuperType;
    }

    private static boolean isNotFixedVariable(ConstraintSystem c, KType t) {
        return t instanceof KType.TypeVariableType v && c.notFixedTypeVariables().containsKey(v.variable());
    }

    private static boolean isNotFixedVariableOrCaptured(ConstraintSystem c, KType t) {
        KType lower = Types.lowerBound(t);
        if (lower instanceof KType.DefinitelyNotNull d) lower = d.inner();
        return isNotFixedVariable(c, lower) || lower instanceof KType.Captured;
    }

    /** The types of the lower constraints, if one of them is proper. */
    private List<KType> prepareLowerConstraints(ConstraintSystem c, List<Constraint> constraints) {
        boolean atLeastOneProper = false;
        List<KType> lowerTypes = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (constraint.kind() != ConstraintKind.LOWER) continue;
            lowerTypes.add(constraint.type());
            if (isProperTypeForFixation(c, constraint.type())) atLeastOneProper = true;
        }
        return atLeastOneProper ? lowerTypes : List.of();
    }

    private static List<KType> sinkIntegerLiteralTypes(List<KType> types) {
        return types.stream()
                .sorted(Comparator.comparingInt(t -> InferenceTypes.contains(t, InferenceTypes::isIntegerLiteral) ? 1 : 0))
                .toList();
    }

    private KType findSuperType(ConstraintSystem c, VariableWithConstraints v) {
        boolean hasNotNull = false;
        List<KType> upperTypes = new ArrayList<>();
        for (Constraint constraint : v.constraints()) {
            if (constraint.kind() != ConstraintKind.UPPER) continue;
            if (isProperConstraint(c, constraint)) {
                upperTypes.add(constraint.type());
            } else if (!InferenceTypes.isNullableType(constraint.type(), true)) {
                // Non-proper constraints like recursive upper bounds still give nullability.
                hasNotNull = true;
            }
        }
        if (upperTypes.isEmpty()) return null;
        KType result = ts.intersect(upperTypes);
        if (hasNotNull && InferenceTypes.isNullableType(result, true)) return Types.makeDefinitelyNotNull(result);
        return result;
    }

    private boolean isProperConstraint(ConstraintSystem c, Constraint constraint) {
        return isProperTypeForFixation(c, constraint.type());
    }

    private static boolean isProperTypeForFixation(ConstraintSystem c, KType type) {
        return InferenceTypes.isProperTypeForFixation(type, c.notFixedTypeVariables().keySet(), c::isProperType);
    }

    // ==== Equality constraints ====

    private KType findResultIfThereIsEqualsConstraint(ConstraintSystem c, VariableWithConstraints v) {
        List<Constraint> properEqualities = v.constraints().stream()
                .filter(x -> x.kind() == ConstraintKind.EQUALITY && isProperConstraint(c, x)).toList();
        return representativeFromEqualityConstraints(v, properEqualities);
    }

    /** ILTs are less specific than Int etc. and are discriminated. */
    private KType representativeFromEqualityConstraints(VariableWithConstraints v, List<Constraint> properEqualities) {
        if (properEqualities.isEmpty()) return null;
        List<KType> types = properEqualities.stream().map(Constraint::type).toList();
        List<KType> nonLiteralTypes = types.stream().filter(t -> !InferenceTypes.isIntegerLiteral(t)).toList();
        KType best = singleBestRepresentative(nonLiteralTypes);
        if (best != null) return best;
        best = singleBestRepresentative(types);
        if (best != null) return best;
        // A contradiction: the explicit type argument keeps later checks accurate.
        for (Constraint constraint : properEqualities) {
            if (constraint.position().fromKind() == ConstraintPosition.Kind.EXPLICIT_TYPE_ARGUMENT
                    && constraint.position().initial().a().equals(v.variable().type())) {
                return constraint.type();
            }
        }
        return types.getFirst();
    }

    private KType singleBestRepresentative(List<KType> types) {
        if (types.size() == 1) return types.getFirst();
        for (KType candidate : types) {
            if (types.stream().allMatch(other -> candidate.equals(other) || ts.equalTypes(candidate, other))) {
                return candidate;
            }
        }
        return null;
    }
}
