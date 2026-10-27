package kotlinbootstrap.infer;

/**
 * How far to complete a call, as kotlinc's ConstraintSystemCompletionMode.
 * A call is completed fully when its result is not an argument of another
 * call; otherwise partially, leaving the variables of its return type to the
 * outer call's system.
 */
public enum CompletionMode {
    FULL(true, true, true, false),
    PARTIAL(false, false, false, true),
    /** For overload resolution by lambda return type: until a lambda can be analyzed. */
    UNTIL_FIRST_LAMBDA(false, true, false, false);

    final boolean allPostponedAtomsShouldBeAnalyzed;
    final boolean shouldForkPointConstraintsBeResolved;
    final boolean fixNotInferredTypeVariablesToErrorType;
    final boolean preventFixingTypeVariablesRelatedToReturnType;

    CompletionMode(boolean allPostponedAtomsShouldBeAnalyzed, boolean shouldForkPointConstraintsBeResolved,
                   boolean fixNotInferredTypeVariablesToErrorType, boolean preventFixingTypeVariablesRelatedToReturnType) {
        this.allPostponedAtomsShouldBeAnalyzed = allPostponedAtomsShouldBeAnalyzed;
        this.shouldForkPointConstraintsBeResolved = shouldForkPointConstraintsBeResolved;
        this.fixNotInferredTypeVariablesToErrorType = fixNotInferredTypeVariablesToErrorType;
        this.preventFixingTypeVariablesRelatedToReturnType = preventFixingTypeVariablesRelatedToReturnType;
    }

    /** Without PCLA, the same as allPostponedAtomsShouldBeAnalyzed. */
    boolean allLambdasShouldBeAnalyzed() {
        return allPostponedAtomsShouldBeAnalyzed;
    }
}
