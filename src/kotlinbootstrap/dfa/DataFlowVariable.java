package kotlinbootstrap.dfa;

import kotlinbootstrap.types.KType;

/**
 * What data flow facts are about, as FIR's DataFlowVariable: a storage
 * that keeps its value between reads, or the value of one expression.
 */
public sealed interface DataFlowVariable permits RealVariable, SyntheticVariable {
    KType originalType();
}
