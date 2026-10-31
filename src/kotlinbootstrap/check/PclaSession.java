package kotlinbootstrap.check;

import java.util.HashMap;
import java.util.Map;

import kotlinbootstrap.infer.CompletionMode;
import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;

/**
 * The analysis of a lambda whose input types have variables not fixed,
 * PCLA, as FIR's FirPCLAInferenceSession (see docs/fir/pcla.md in the
 * Kotlin sources): the calls in the lambda that use those variables, or
 * values of types with them, share a system that starts as a copy of the
 * system of the call the lambda is passed to; they are only completed as
 * far as they can be without fixing the variables related to the shared
 * ones, and are left to be completed with that call, which gets the shared
 * system once the lambda has been analyzed.
 */
final class PclaSession {
    /** The call being completed, whose postponed calls are those of the lambda. */
    private final Candidate outerCandidate;
    /** The system of the call being completed. */
    private final ConstraintSystem outerSystem;
    /** The system the calls of the lambda share, as FIR's currentCommonSystem. */
    private ConstraintSystem currentCommonSystem;
    /** The variables made equal to their current result types, as FIR's semiFixedVariables. */
    final Map<TypeVariable, KType> semiFixedVariables = new HashMap<>();

    PclaSession(TypeSystem ts, Candidate outerCandidate, ConstraintSystem outerSystem) {
        this.outerCandidate = outerCandidate;
        this.outerSystem = outerSystem;
        // As FIR's prepareSharedBaseSystem: all of the outer system's variables are outer ones.
        this.currentCommonSystem = new ConstraintSystem(ts);
        currentCommonSystem.addOuterSystem(outerSystem);
    }

    /**
     * The system a candidate starts with a copy of, as FIR's
     * baseConstraintStorageForCandidate: none for a call that can be
     * completed on its own, as FIR's mightBeAnalyzedAndCompletedIndependently;
     * that is, one with receivers and arguments that do not involve the
     * shared variables, and without postponed arguments.
     */
    ConstraintSystem baseSystemForCandidate(Candidate candidate) {
        if (isReceiverPostponed(candidate.dispatchReceiver) || isReceiverPostponed(candidate.extensionReceiver)) {
            return currentCommonSystem;
        }
        if (candidate.symbol instanceof CallableSymbol c && !c.signature().contextParams().isEmpty()) {
            return currentCommonSystem;
        }
        for (TExpr argument : candidate.arguments) {
            if (!isTrivialArgument(argument)) return currentCommonSystem;
        }
        return null;
    }

    private boolean isReceiverPostponed(TExpr receiver) {
        if (receiver == null) return false;
        return containsNotFixedTypeVariables(receiver.type())
                || ArgumentChecker.subCandidate(receiver) instanceof Candidate c && c.system.usesOuterSystem();
    }

    private boolean isTrivialArgument(TExpr argument) {
        return switch (argument) {
            case TExpr.Unresolved u when u.state() instanceof Candidate c -> !c.system.usesOuterSystem();
            // Lambdas and callable references.
            case TExpr.Unresolved u -> false;
            case TExpr.Block b when TExprs.lastExpression(b) != null -> isTrivialArgument(TExprs.lastExpression(b));
            case TExpr.SafeCall s -> isTrivialArgument(s.receiver()) && isTrivialArgument(s.selector());
            default -> !containsNotFixedTypeVariables(argument.type());
        };
    }

    /** Whether the type has variables of the shared system. */
    boolean containsNotFixedTypeVariables(KType type) {
        return Types.contains(type, t -> t instanceof KType.TypeVariableType v
                && currentCommonSystem.allTypeVariables().contains(v.variable()));
    }

    /**
     * After a call that uses the shared system is completed partially, or
     * as a postponed call, its system becomes the shared one, as FIR's
     * processPartiallyResolvedCall; a postponed call is completed with the
     * outer call.
     */
    void processPartiallyResolvedCall(Candidate candidate, CompletionMode mode) {
        if (!candidate.system.usesOuterSystem()) return;
        currentCommonSystem.replaceContentWith(candidate.system);
        if (mode == CompletionMode.PCLA_POSTPONED_CALL) outerCandidate.postponedPclaCalls.add(candidate);
    }

    /**
     * The type of a call left uncompleted, with the variables fixed so far
     * substituted, as FIR's updateReturnTypeWithCurrentSubstitutor; for a
     * receiver of a variable's type, the variable is semi-fixed first, to
     * give it a member scope.
     */
    KType updatedType(KType type, ConstraintSystem system, boolean receiver) {
        Map<Object, TypeArg> map = new HashMap<>(system.buildCurrentSubstitutor().map());
        if (receiver) {
            TypeVariable v = typeVariable(type);
            KType result = v == null ? null : semiFix(v, system);
            if (result != null) map.put(v, TypeArg.of(result));
        }
        return new Substitution(map).apply(type);
    }

    /** The type of a receiver, if a variable of the shared system, semi-fixed; otherwise as it is. */
    KType semiFixReceiverType(KType type) {
        TypeVariable v = typeVariable(type);
        if (v == null || !currentCommonSystem.allTypeVariables().contains(v)) return type;
        KType result = semiFix(v, currentCommonSystem);
        if (result == null) return type;
        return new Substitution(Map.of(v, TypeArg.of(result))).apply(type);
    }

    /** The current result type of a variable, which is made equal to it; null if there is none yet. */
    KType semiFix(TypeVariable v, ConstraintSystem system) {
        KType fixed = system.fixedTypeVariables().get(v);
        if (fixed != null) return fixed;
        if (!system.notFixedTypeVariables().containsKey(v)) return null;
        KType result = system.semiFixVariable(v);
        if (result != null) semiFixedVariables.put(v, result);
        return result;
    }

    private static TypeVariable typeVariable(KType type) {
        KType lower = Types.lowerBound(type);
        if (lower instanceof KType.DefinitelyNotNull d) lower = d.inner();
        return lower instanceof KType.TypeVariableType v ? v.variable() : null;
    }

    /**
     * A constraint from other than a call, like an assignment of a value
     * whose type has variables, as FIR's addSubtypeConstraintIfCompatible.
     */
    void addSubtypeConstraintIfCompatible(KType lower, KType upper) {
        ArgumentChecker.addSubtypeConstraintIfCompatible(currentCommonSystem, lower, upper,
                ConstraintPosition.of(ConstraintPosition.Kind.EXPECTED_TYPE));
    }

    ConstraintSystem currentCommonSystem() {
        return currentCommonSystem;
    }

    /**
     * Analyzes a lambda of a call in the lambda with that call's system as
     * the shared one, as FIR's runLambdaCompletion: the calls in it add to
     * that system, which becomes the shared one when the call is completed.
     */
    ConstraintSystem enterLambdaCompletion(ConstraintSystem system) {
        ConstraintSystem previous = currentCommonSystem;
        currentCommonSystem = system;
        return previous;
    }

    void exitLambdaCompletion(ConstraintSystem previous) {
        currentCommonSystem = previous;
    }

    /** Once the lambda is analyzed, the call it is passed to gets the shared system. */
    void applyResultsToMainCandidate() {
        outerSystem.replaceContentWith(currentCommonSystem);
    }
}
