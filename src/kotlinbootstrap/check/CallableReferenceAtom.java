package kotlinbootstrap.check;

import java.util.List;

import kotlinbootstrap.infer.PostponedAtomWithRevisableExpectedType;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;

/**
 * A callable reference argument, as FIR's ConeResolvedCallableReferenceAtom.
 * It is resolved with its call's candidate, against the type expected of
 * it then; if that is ambiguous, again during completion, when the
 * expected type may be known better.
 */
final class CallableReferenceAtom extends PostponedArgument implements PostponedAtomWithRevisableExpectedType {
    enum State {
        NOT_RESOLVED_YET,
        /** To be resolved again during completion. */
        POSTPONED_BECAUSE_OF_AMBIGUITY,
        /** Successfully or not. */
        RESOLVED
    }

    private final TypeSystem ts;
    final CallableReferenceExpression expression;
    private final KType initialExpectedType;
    final Candidate containingCandidate;
    State state = State.NOT_RESOLVED_YET;
    /** Once resolved, the candidate chosen, whose system the containing call's has taken; null if none. */
    private Candidate resolved;
    /** Once resolved without success, why. */
    private String error;
    private KType revisedExpectedType;

    CallableReferenceAtom(TypeSystem ts, CallableReferenceExpression expression, KType expectedType,
                          Candidate containingCandidate) {
        this.ts = ts;
        this.expression = expression;
        this.initialExpectedType = expectedType;
        this.containingCandidate = containingCandidate;
    }

    boolean needsResolution() {
        return state != State.RESOLVED;
    }

    void resolve(Candidate candidate) {
        state = State.RESOLVED;
        resolved = candidate;
    }

    void fail(String message) {
        state = State.RESOLVED;
        error = message;
    }

    /** The candidate chosen, or null if not resolved successfully. */
    Candidate resolved() {
        return resolved;
    }

    String error() {
        return error;
    }

    // ==== Types ====

    @Override
    public List<KType> inputTypes() {
        // Not resolved yet, it is resolved at once rather than waiting for anything.
        if (state == State.NOT_RESOLVED_YET) return List.of();
        KType expected = expectedType();
        KType.ClassType function = expectedFunctionType(expected);
        if (function != null) return FunctionTypes.valueParameterTypesIncludingReceiver(ts, function);
        return expected == null ? List.of() : List.of(expected);
    }

    @Override
    public KType outputType() {
        if (state == State.NOT_RESOLVED_YET) return null;
        KType.ClassType function = expectedFunctionType(expectedType());
        return function == null ? null : FunctionTypes.returnType(ts, function);
    }

    /** The expected type as a function type, if it is one, as FIR's extractInputOutputTypesFromCallableReferenceExpectedType. */
    static KType.ClassType expectedFunctionType(KType expected) {
        return expected != null && Types.lowerBound(expected) instanceof KType.ClassType c
                && FunctionTypes.isFunctionType(c) ? c : null;
    }

    @Override
    public KType expectedType() {
        if (state != State.POSTPONED_BECAUSE_OF_AMBIGUITY) return initialExpectedType;
        return revisedExpectedType != null ? revisedExpectedType : initialExpectedType;
    }

    @Override
    public KType revisedExpectedType() {
        return state == State.POSTPONED_BECAUSE_OF_AMBIGUITY ? revisedExpectedType : expectedType();
    }

    @Override
    public void reviseExpectedType(KType type) {
        revisedExpectedType = type;
    }

    // ==== Completion ====

    @Override
    void activate() {
        expression.atom = this;
    }

    /** The candidate chosen, to be completed with the call: its variables are the call's now. */
    @Override
    List<TExpr> nestedArguments() {
        return resolved == null ? List.of() : List.of(new TExpr.Unresolved(expression.pos, null, resolved));
    }

    @Override
    public String toString() {
        return expression.toString();
    }
}
