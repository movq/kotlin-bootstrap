package kotlinbootstrap.infer;

/**
 * How far to complete a call, as kotlinc's ConstraintSystemCompletionMode.
 * A call is completed fully when its result is not an argument of another
 * call; otherwise partially, leaving the variables of its return type to the
 * outer call's system.
 */
public enum CompletionMode {
    FULL(true, true, true, true, false),
    /**
     * For a call in a lambda analyzed with PCLA that uses the variables of
     * the call the lambda is passed to: its lambdas are analyzed, but its
     * variables related to those are left to that call.
     */
    PCLA_POSTPONED_CALL(false, true, false, false, false),
    PARTIAL(false, false, false, false, true),
    /** For overload resolution by lambda return type: until a lambda can be analyzed. */
    UNTIL_FIRST_LAMBDA(false, false, true, false, false);

    final boolean allPostponedAtomsShouldBeAnalyzed;
    final boolean allLambdasShouldBeAnalyzed;
    final boolean shouldForkPointConstraintsBeResolved;
    final boolean fixNotInferredTypeVariablesToErrorType;
    final boolean preventFixingTypeVariablesRelatedToReturnType;

    CompletionMode(boolean allPostponedAtomsShouldBeAnalyzed, boolean allLambdasShouldBeAnalyzed,
                   boolean shouldForkPointConstraintsBeResolved, boolean fixNotInferredTypeVariablesToErrorType,
                   boolean preventFixingTypeVariablesRelatedToReturnType) {
        this.allPostponedAtomsShouldBeAnalyzed = allPostponedAtomsShouldBeAnalyzed;
        this.allLambdasShouldBeAnalyzed = allLambdasShouldBeAnalyzed;
        this.shouldForkPointConstraintsBeResolved = shouldForkPointConstraintsBeResolved;
        this.fixNotInferredTypeVariablesToErrorType = fixNotInferredTypeVariablesToErrorType;
        this.preventFixingTypeVariablesRelatedToReturnType = preventFixingTypeVariablesRelatedToReturnType;
    }
}
