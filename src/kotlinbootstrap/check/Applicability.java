package kotlinbootstrap.check;

/**
 * How well a candidate fits a call, worst first, as FIR's
 * CandidateApplicability. A candidate is as applicable as its worst
 * diagnostic allows; the best candidates of a call are those of the
 * highest applicability.
 */
enum Applicability {
    K1_RESOLVED_TO_SAM_WITH_VARARG,
    /** Deprecated with level HIDDEN, or not callable like this, e.g. an object's constructor. */
    HIDDEN,
    K2_UNSUPPORTED,
    INAPPLICABLE_WRONG_RECEIVER,
    INAPPLICABLE_ARGUMENTS_MAPPING_ERROR,
    INAPPLICABLE,
    /** A class without a companion object used as a value. */
    K2_NO_COMPANION_OBJECT,
    K1_IMPOSSIBLE_TO_GENERATE,
    K1_RUNTIME_ERROR,
    K2_VISIBILITY_ERROR,
    /** A member or non-null extension called on a nullable receiver. */
    UNSAFE_CALL,
    UNSTABLE_SMARTCAST,
    /** An operator or infix call of a function not declared so. */
    CONVENTION_ERROR,
    /** Fits, but is annotated {@code @LowPriorityInOverloadResolution}. */
    RESOLVED_LOW_PRIORITY,
    K2_NOT_FUNCTION_AS_OPERATOR,
    RESOLVED_NEED_PRESERVE_COMPATIBILITY,
    K2_SYNTHETIC_RESOLVED,
    RESOLVED_WITH_ERROR,
    RESOLVED;

    boolean isSuccess() {
        return compareTo(RESOLVED_LOW_PRIORITY) >= 0 && this != RESOLVED_WITH_ERROR;
    }

    /**
     * Whether a call is compiled with a candidate this applicable: if not
     * successful, then only not visible, an error kotlinc lets code
     * suppress with {@code @Suppress("INVISIBLE_REFERENCE")}.
     */
    boolean isUsable() {
        return isSuccess() || this == K2_VISIBILITY_ERROR;
    }

    /** Whether no candidate in a later group could be better. */
    boolean shouldStopResolve() {
        return compareTo(K2_SYNTHETIC_RESOLVED) >= 0;
    }
}
