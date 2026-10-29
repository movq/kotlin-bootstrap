package kotlinbootstrap.check;

import kotlinbootstrap.types.KType;

/**
 * How an expression is checked, as FIR's ResolutionMode: whether its calls
 * are completed at once or left for the call it is an argument of, and the
 * type expected of it.
 */
sealed interface Mode {
    /** Whether calls are completed fully, rather than left for an enclosing call. */
    boolean forceFullCompletion();

    /** On its own: statements, receivers, initializers of declarations with inferred types. */
    record ContextIndependent() implements Mode {
        @Override
        public boolean forceFullCompletion() {
            return true;
        }
    }

    /** An argument of a call, completed with the call. */
    record ContextDependent() implements Mode {
        @Override
        public boolean forceFullCompletion() {
            return false;
        }
    }

    /** The receiver of a call or property access. */
    record Receiver() implements Mode {
        @Override
        public boolean forceFullCompletion() {
            return true;
        }
    }

    /** The delegate of a delegated property, completed with its operator calls. */
    record Delegate() implements Mode {
        @Override
        public boolean forceFullCompletion() {
            return false;
        }
    }

    /**
     * With a type expected of it, which guides inference but is not checked.
     *
     * @param lastStatementInBlock for the last statement of a block, where Unit is
     *                             expected only as coercion to Unit
     * @param fromCast             for the operand of a cast, {@code f() as T}
     * @param forceFullCompletion  false only for the branches of a {@code when}
     */
    record WithExpectedType(KType type, boolean lastStatementInBlock, boolean fromCast, boolean forceFullCompletion)
            implements Mode {
        WithExpectedType(KType type) {
            this(type, false, false, true);
        }
    }

    Mode INDEPENDENT = new ContextIndependent();
    Mode DEPENDENT = new ContextDependent();
    Mode RECEIVER = new Receiver();
    Mode DELEGATE = new Delegate();

    static Mode expecting(KType type) {
        return new WithExpectedType(type);
    }

    /** The expected type, if any, unless from a cast. */
    static KType expectedType(Mode mode) {
        return mode instanceof WithExpectedType w && !w.fromCast() ? w.type() : null;
    }
}
