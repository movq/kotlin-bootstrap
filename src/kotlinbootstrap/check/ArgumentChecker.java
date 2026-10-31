package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import kotlinbootstrap.infer.Constraint;
import kotlinbootstrap.infer.ConstraintKind;
import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.infer.VariableWithConstraints;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.SubtypeChecker;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;

/**
 * Adds the constraints an argument (or receiver) gives its call, as FIR's
 * ArgumentCheckingProcessor: its type must be a subtype of the parameter
 * type. The type of an uncompleted call among the arguments still has its
 * type variables, whose constraints are already in the system.
 */
final class ArgumentChecker {
    private final TypeSystem ts;

    ArgumentChecker(TypeSystem ts) {
        this.ts = ts;
    }

    // ==== Atoms ====

    /**
     * The uncompleted call an argument's value comes from: the argument
     * itself, the last expression of a block, the selector of a safe call.
     */
    static Candidate subCandidate(TExpr e) {
        return switch (e) {
            case TExpr.Unresolved u when u.state() instanceof Candidate c -> c;
            case TExpr.Block b when TExprs.lastExpression(b) != null -> subCandidate(TExprs.lastExpression(b));
            case TExpr.SafeCall s -> subCandidate(s.selector());
            default -> null;
        };
    }

    /**
     * Adds the system of the argument's uncompleted call, if any; returns
     * whether there was one. A call in a PCLA lambda that uses the shared
     * system has added to it already.
     */
    static boolean addSubsystemFromArgument(ConstraintSystem system, TExpr argument) {
        Candidate sub = subCandidate(argument);
        if (sub != null && !sub.system.usesOuterSystem()) system.addOtherSystem(sub.system);
        return sub != null;
    }

    // ==== Arguments ====

    /**
     * Checks an argument against the parameter type (with the candidate's
     * type variables), recording an error in the candidate if it does not fit.
     *
     * @param expected null if there is nothing to check
     */
    void resolveArgument(Candidate candidate, TExpr argument, KType expected, boolean isReceiver, boolean isDispatch) {
        resolveArgument(candidate, candidate, argument, expected, isReceiver, isDispatch);
    }

    /**
     * @param candidate  the call whose system and diagnostics get the constraints
     * @param containing the call the argument is of, which its lambdas are registered in: for a value
     *                   returned from a lambda during completion, the lambda's call
     */
    void resolveArgument(Candidate candidate, Candidate containing, TExpr argument, KType expected,
                         boolean isReceiver, boolean isDispatch) {
        switch (argument) {
            case TExpr.Unresolved u when u.state() instanceof LambdaExpression l ->
                    preprocessLambda(candidate, containing, l, expected);
            case TExpr.Unresolved u when u.state() instanceof CallableReferenceExpression r ->
                    preprocessCallableReference(containing, r, expected);
            case TExpr.SafeCall s -> {
                if (TExprs.isStatement(s.selector())) {
                    checkApplicability(candidate, s, unit(), expected, isReceiver, isDispatch);
                } else {
                    resolvePlainArgument(candidate, s.selector(), expected, true, isReceiver, isDispatch);
                }
            }
            case TExpr.Block b -> {
                TExpr last = TExprs.lastExpression(b);
                if (last == null) {
                    checkApplicability(candidate, b, b.type(), expected, false, false);
                } else {
                    resolveArgument(candidate, containing, last, expected, isReceiver, isDispatch);
                }
            }
            default -> resolvePlainArgument(candidate, argument, expected, false, isReceiver, isDispatch);
        }
    }

    private void resolvePlainArgument(Candidate candidate, TExpr argument, KType expected, boolean nullable,
                                      boolean isReceiver, boolean isDispatch) {
        if (expected == null) return;
        KType captured = ts.captureFromExpression(argument.type());
        KType type = captured != null ? captured : argument.type();
        if (nullable) type = type.withNullable(true);
        // TODO: suspend and Unit conversions of function values
        checkApplicability(candidate, argument, type, expected, isReceiver, isDispatch);
    }

    private void checkApplicability(Candidate candidate, TExpr argument, KType argumentTypeBeforeCapturing,
                                    KType expected, boolean isReceiver, boolean isDispatch) {
        if (expected == null) return;
        KType argumentType = captureFromTypeParameterUpperBoundIfNeeded(argumentTypeBeforeCapturing, expected);
        ConstraintSystem system = candidate.system;
        ConstraintPosition position = ConstraintPosition.of(
                isReceiver ? ConstraintPosition.Kind.RECEIVER : ConstraintPosition.Kind.ARGUMENT);
        if (isReceiver && isDispatch) {
            if (!Types.isMarkedOrFlexiblyNullable(expected) && Types.isMarkedNullable(argumentType)) {
                candidate.addDiagnostic(Applicability.INAPPLICABLE_WRONG_RECEIVER,
                        "Nullable receiver of type " + argumentType);
            }
            return;
        }
        if (addSubtypeConstraintIfCompatible(system, argumentType, expected, position)) return;
        // TODO: unstable smart casts
        if (!isReceiver) {
            boolean isNull = argument instanceof TExpr.Const c && c.value() == null && ts.isNothing(c.type().withNullable(false));
            boolean dueToNullability = !isNull && ts.isSubtype(argumentType, Types.makeNullable(expected));
            candidate.addDiagnostic(dueToNullability ? Applicability.UNSAFE_CALL : Applicability.INAPPLICABLE,
                    "Argument type mismatch: " + argumentType + " is not " + expected);
            return;
        }
        if (addSubtypeConstraintIfCompatible(system, argumentType, expected.withNullable(true), position)) {
            candidate.addDiagnostic(Applicability.UNSAFE_CALL, "Unsafe call on nullable receiver of type " + argumentType);
        } else {
            system.addSubtypeConstraint(argumentType, expected, position);
            candidate.addDiagnostic(Applicability.INAPPLICABLE_WRONG_RECEIVER,
                    "Wrong receiver: " + argumentType + " is not " + expected);
        }
    }

    /** The type of an implicit value as an argument for a parameter, as FIR's prepareImplicitArgument. */
    KType implicitArgumentType(KType valueType, KType expected) {
        KType type = captureFromTypeParameterUpperBoundIfNeeded(valueType, expected);
        KType captured = ts.captureFromExpression(type);
        if (captured != null) type = captured;
        return type instanceof KType.IntegerLiteral l ? l.possibleTypes().getFirst() : type;
    }

    /** Adds {@code lower <: upper} unless that makes a contradiction; returns whether it did. */
    static boolean addSubtypeConstraintIfCompatible(ConstraintSystem system, KType lower, KType upper,
                                                    ConstraintPosition position) {
        return system.runTransaction(() -> {
            if (!system.hasContradiction()) system.addSubtypeConstraint(lower, upper, position);
            return !system.hasContradiction();
        });
    }

    static boolean addEqualityConstraintIfCompatible(ConstraintSystem system, KType a, KType b,
                                                     ConstraintPosition position) {
        return system.runTransaction(() -> {
            if (!system.hasContradiction()) system.addEqualityConstraint(a, b, position);
            return !system.hasContradiction();
        });
    }

    /**
     * For an argument of a type parameter type whose one bound is of the
     * expected class, that bound captured: so that {@code T : List<*>}
     * can be passed for {@code List<E>}.
     */
    private KType captureFromTypeParameterUpperBoundIfNeeded(KType argumentType, KType expected) {
        if (!(Types.upperBound(expected) instanceof KType.ClassType expectedClass)) return argumentType;
        if (!(Types.lowerBound(argumentType) instanceof KType.TypeParamType)
                && !(Types.lowerBound(argumentType) instanceof KType.DefinitelyNotNull d
                && d.inner() instanceof KType.TypeParamType)) {
            return argumentType;
        }
        KType.ClassType chosen = null;
        for (KType.ClassType bound : collectUpperBounds(argumentType)) {
            if (ts.asSupertype(bound, expectedClass.symbol()) == null) continue;
            if (chosen != null) return argumentType;
            chosen = bound;
        }
        if (chosen == null) return argumentType;
        KType captured = ts.captureFromExpression(chosen);
        if (captured == null) return argumentType;
        return argumentType instanceof KType.DefinitelyNotNull ? Types.makeDefinitelyNotNull(captured) : captured;
    }

    /** The class types among the bounds of a type, transitively, as kotlinc's collectUpperBounds. */
    private static Set<KType.ClassType> collectUpperBounds(KType type) {
        Set<KType.ClassType> result = new LinkedHashSet<>();
        collectUpperBounds(type, result, new HashSet<>());
        return result;
    }

    private static void collectUpperBounds(KType type, Set<KType.ClassType> result, Set<KType> seen) {
        if (!seen.add(type)) return;
        switch (type) {
            case KType.ClassType c -> result.add(c);
            case KType.TypeParamType p -> {
                for (KType b : p.symbol().bounds()) {
                    collectUpperBounds(b.withNullable(Types.canBeNull(b) || p.nullable()), result, seen);
                }
            }
            case KType.TypeVariableType v -> {
                if (v.variable().typeParam() != null) {
                    for (KType b : v.variable().typeParam().bounds()) collectUpperBounds(b, result, seen);
                }
            }
            case KType.DefinitelyNotNull d -> collectUpperBounds(d.inner(), result, seen);
            case KType.Intersection i -> i.components().forEach(c -> collectUpperBounds(c, result, seen));
            case KType.Flexible f -> collectUpperBounds(f.upper(), result, seen);
            case KType.Captured c -> {
                if (c.constructor().supertypes() != null) {
                    c.constructor().supertypes().forEach(s -> collectUpperBounds(s, result, seen));
                }
            }
            case KType.IntegerLiteral l -> result.add(l.possibleTypes().getFirst().withNullable(l.nullable()));
        }
    }

    // ==== Lambdas ====

    /**
     * Registers the atom for a lambda argument, as FIR's
     * preprocessLambdaArgument: one expected to have a variable's type,
     * if the variable may become a function type, or one with its types.
     */
    private void preprocessLambda(Candidate candidate, Candidate containing, LambdaExpression lambda, KType expected) {
        PostponedArgument atom = createLambdaWithTypeVariableAtomIfNeeded(candidate.system, containing, lambda, expected);
        if (atom == null) atom = createResolvedLambdaAtom(candidate, candidate.system, containing, lambda, expected, false);
        containing.postponedArguments.add(atom);
        atom.activate();
    }

    /** As FIR's preprocessCallableReference: the reference is resolved once the candidate's arguments are checked. */
    private void preprocessCallableReference(Candidate containing, CallableReferenceExpression reference,
                                             KType expected) {
        CallableReferenceAtom atom = new CallableReferenceAtom(ts, reference, expected, containing);
        containing.postponedArguments.add(atom);
        atom.activate();
    }

    private static LambdaWithTypeVariableAtom createLambdaWithTypeVariableAtomIfNeeded(
            ConstraintSystem system, Candidate containing, LambdaExpression lambda, KType expected) {
        if (expected == null || !system.isTypeVariable(expected)) return null;
        VariableWithConstraints variable = system.notFixedTypeVariables().get((TypeVariable) SubtypeChecker.constructor(expected));
        if (variable == null) return null;
        // An explicit type argument that is not generic is not a function type.
        for (Constraint c : variable.constraints()) {
            if (c.kind() != ConstraintKind.EQUALITY
                    || c.position().fromKind() != ConstraintPosition.Kind.EXPLICIT_TYPE_ARGUMENT) {
                continue;
            }
            if (!(Types.lowerBound(c.type()) instanceof KType.ClassType ct) || ct.args().isEmpty()) return null;
            break;
        }
        return new LambdaWithTypeVariableAtom(lambda, expected, containing);
    }

    /**
     * Replaces a lambda expected to have a variable's type by one with its
     * types, from the function type built for it if any, as FIR's
     * transformToResolvedLambda.
     */
    LambdaAtom transformToResolvedLambda(ConstraintSystem system, LambdaWithTypeVariableAtom argument,
                                         KType expectedType) {
        KType fixed = system.buildCurrentSubstitutor().apply(expectedType != null ? expectedType : argument.expectedType());
        LambdaAtom atom = createResolvedLambdaAtom(null, system, argument.containingCandidate, argument.expression,
                fixed, true);
        argument.setSubAtom(atom);
        return atom;
    }

    /**
     * The atom for a lambda with its types, which must make a subtype of
     * the expected type.
     *
     * @param candidate for diagnostics; null during completion, where a mismatch is a contradiction
     */
    private LambdaAtom createResolvedLambdaAtom(Candidate candidate, ConstraintSystem system, Candidate containing,
                                                LambdaExpression lambda, KType expected, boolean duringCompletion) {
        LambdaAtom atom = extractLambdaInfoFromFunctionType(expected, lambda, duringCompletion, containing);
        if (atom == null) atom = extractLambdaInfo(system, expected, lambda, containing);
        if (expected != null) {
            FunctionTypes.Kind kind = atom.kind != null ? atom.kind.nonReflect() : FunctionTypes.Kind.FUNCTION;
            KType lambdaType = FunctionTypes.create(ts.table(), kind, atom.contextParameterTypes, atom.receiverType,
                    atom.parameterTypes, atom.returnType);
            ConstraintPosition position = ConstraintPosition.of(ConstraintPosition.Kind.ARGUMENT);
            if (duringCompletion) {
                system.addSubtypeConstraint(lambdaType, expected, position);
            } else if (!addSubtypeConstraintIfCompatible(system, lambdaType, expected, position)) {
                candidate.addDiagnostic(Applicability.INAPPLICABLE,
                        "Argument type mismatch: " + lambdaType + " is not " + expected);
            }
        }
        return atom;
    }

    /** The lambda's types from the function type expected of it, as kotlinc's extractLambdaInfoFromFunctionType. */
    private LambdaAtom extractLambdaInfoFromFunctionType(KType expected, LambdaExpression lambda,
                                                        boolean allowCoercionToExtensionReceiver, Candidate containing) {
        if (expected == null || !(Types.lowerBound(expected) instanceof KType.ClassType expectedClass)) return null;
        FunctionTypes.Kind expectedKind = FunctionTypes.kind(expectedClass.symbol());
        if (expectedKind == null) return null;
        // No conversion of anonymous functions to suspend functions.
        FunctionTypes.Kind actualKind = lambda.isSuspend ? FunctionTypes.Kind.SUSPEND_FUNCTION
                : !lambda.isLambda ? FunctionTypes.Kind.FUNCTION : null;
        KType returnType = lambda.declaredReturnType != null ? lambda.declaredReturnType
                : FunctionTypes.returnType(ts, expectedClass);
        // A lambda has the receiver the expected type has; an anonymous function, the one it declares.
        KType receiverType = lambda.isLambda ? FunctionTypes.receiverType(ts, expectedClass) : lambda.receiverType;
        int contextParameterCount = lambda.isLambda ? expectedClass.contextParams() : 0;
        List<KType> includingReceiver = FunctionTypes.valueParameterTypesIncludingReceiver(ts, expectedClass);
        boolean isExtensionFunctionType = expectedClass.extensionFunction();
        int toDrop = (receiverType != null && isExtensionFunctionType ? 1 : 0) + contextParameterCount;
        List<KType> expectedParameters = includingReceiver.subList(toDrop, includingReceiver.size());

        boolean coerceFirstParameterToExtensionReceiver = false;
        List<KType> parameters;
        if (lambda.isLambda && !lambda.hasExplicitParameterList && expectedParameters.size() < 2) {
            // The implicit parameter it, if any.
            parameters = expectedParameters;
        } else {
            List<KType> declared = lambda.declaredParamTypes;
            if (allowCoercionToExtensionReceiver && lambda.isLambda && isExtensionFunctionType
                    && includingReceiver.size() == declared.size()) {
                coerceFirstParameterToExtensionReceiver = true;
                declared = declared.subList(1, declared.size());
            }
            parameters = new ArrayList<>();
            for (int i = 0; i < declared.size(); i++) {
                KType t = declared.get(i);
                // A parameter too many: any type does, as the arity does not match.
                parameters.add(t != null ? t : i < expectedParameters.size() ? expectedParameters.get(i) : ts.nullableAny());
            }
        }
        List<KType> contextParameters = includingReceiver.subList(0, contextParameterCount);
        return new LambdaAtom(lambda, expectedClass, actualKind != null ? actualKind : expectedKind, receiverType,
                contextParameters, parameters, returnType, null, coerceFirstParameterToExtensionReceiver, containing);
    }

    /** The lambda's types from its shape, with variables for those not declared, as kotlinc's extractLambdaInfo. */
    private static LambdaAtom extractLambdaInfo(ConstraintSystem system, KType expected, LambdaExpression lambda,
                                                Candidate containing) {
        TypeVariable returnVariable = new TypeVariable("_R", null);
        KType returnType = lambda.declaredReturnType;
        if (returnType == null) {
            returnType = returnVariable.type();
            system.registerVariable(returnVariable);
        }
        List<KType> parameters = new ArrayList<>();
        for (int i = 0; i < lambda.declaredParamTypes.size(); i++) {
            KType t = lambda.declaredParamTypes.get(i);
            if (t == null) {
                TypeVariable v = new TypeVariable("_RP" + i, null);
                system.registerVariable(v);
                t = v.type();
            }
            parameters.add(t);
        }
        return new LambdaAtom(lambda, expected, null, lambda.receiverType, List.of(), parameters, returnType,
                lambda.declaredReturnType == null ? returnVariable : null, false, containing);
    }

    private KType unit() {
        return ts.classType("kotlin.Unit");
    }
}
