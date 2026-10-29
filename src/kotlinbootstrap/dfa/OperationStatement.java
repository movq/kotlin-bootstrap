package kotlinbootstrap.dfa;

/** That a variable is true, false, null or not null. */
public record OperationStatement(DataFlowVariable variable, Operation operation) implements Statement {
    public static OperationStatement eq(DataFlowVariable variable, boolean value) {
        return new OperationStatement(variable, value ? Operation.EQ_TRUE : Operation.EQ_FALSE);
    }

    public static OperationStatement eqNull(DataFlowVariable variable) {
        return new OperationStatement(variable, Operation.EQ_NULL);
    }

    public static OperationStatement notEqNull(DataFlowVariable variable) {
        return new OperationStatement(variable, Operation.NOT_EQ_NULL);
    }

    public Implication implies(Statement effect) {
        return new Implication(this, effect);
    }

    @Override
    public String toString() {
        return variable + " " + operation;
    }
}
