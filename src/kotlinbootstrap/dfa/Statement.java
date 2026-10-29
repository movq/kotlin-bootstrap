package kotlinbootstrap.dfa;

/** A fact about a variable: its value, or its types. */
public sealed interface Statement permits OperationStatement, TypeStatement {
    DataFlowVariable variable();
}
