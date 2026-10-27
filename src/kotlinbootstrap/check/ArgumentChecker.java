package kotlinbootstrap.check;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeSystem;
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

    /** Adds the system of the argument's uncompleted call, if any. */
    static void addSubsystemFromArgument(ConstraintSystem system, TExpr argument) {
        Candidate sub = subCandidate(argument);
        if (sub != null) system.addOtherSystem(sub.system);
    }

    // ==== Arguments ====

    /**
     * Checks an argument against the parameter type (with the candidate's
     * type variables), recording an error in the candidate if it does not fit.
     *
     * @param expected null if there is nothing to check
     */
    void resolveArgument(Candidate candidate, TExpr argument, KType expected, boolean isReceiver, boolean isDispatch) {
        switch (argument) {
            case TExpr.Unresolved u when u.state() instanceof PostponedArgument p ->
                    throw new IllegalStateException("Postponed arguments are not supported yet");
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
                    resolveArgument(candidate, last, expected, isReceiver, isDispatch);
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
                candidate.errors.add("Nullable receiver of type " + argumentType);
            }
            return;
        }
        if (addSubtypeConstraintIfCompatible(system, argumentType, expected, position)) return;
        // TODO: unstable smart casts
        if (!isReceiver) {
            candidate.errors.add("Argument type mismatch: " + argumentType + " is not " + expected);
            return;
        }
        if (addSubtypeConstraintIfCompatible(system, argumentType, expected.withNullable(true), position)) {
            candidate.errors.add("Unsafe call on nullable receiver of type " + argumentType);
        } else {
            system.addSubtypeConstraint(argumentType, expected, position);
            candidate.errors.add("Wrong receiver: " + argumentType + " is not " + expected);
        }
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

    private KType unit() {
        return ts.classType("kotlin.Unit");
    }
}
