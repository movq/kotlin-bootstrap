package kotlinbootstrap.dfa;

import kotlinbootstrap.types.KType;

/**
 * The value of one expression, as FIR's SyntheticVariable; the same
 * expression object is the same variable.
 */
public record SyntheticVariable(Object expression, KType originalType) implements DataFlowVariable {
    @Override
    public boolean equals(Object o) {
        return o instanceof SyntheticVariable v && expression == v.expression;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(expression);
    }

    @Override
    public String toString() {
        return "<" + expression.getClass().getSimpleName() + "@" + Integer.toHexString(hashCode()) + ">";
    }
}
