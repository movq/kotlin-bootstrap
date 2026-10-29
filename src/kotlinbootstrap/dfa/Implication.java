package kotlinbootstrap.dfa;

/** That if the condition holds, so does the effect. */
public record Implication(OperationStatement condition, Statement effect) {
    @Override
    public String toString() {
        return condition + " -> " + effect;
    }
}
