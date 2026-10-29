package kotlinbootstrap.dfa;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What is known at a point in a body, as FIR's PersistentFlow and
 * MutableFlow: type statements about variables, implications, which
 * assignment each variable's value is from, and which local variables
 * alias others. A flow is mutable until it is forked; then it is frozen,
 * and the fork remembers it as its previous flow, which joins use to
 * find a common ancestor.
 */
public final class Flow {
    private final Flow previous;
    private final int level;
    private boolean frozen;

    final Map<DataFlowVariable, TypeStatement> approvedTypeStatements;
    final Map<DataFlowVariable, List<Implication>> implications;
    /** With the variable, which value it has: a variable and its index form an SSA value. */
    final Map<RealVariable, Integer> assignmentIndex;
    /** Variables with the same value as another, the representative of their set. */
    final Map<RealVariable, RealVariable> directAliasMap;
    final Map<RealVariable, Set<RealVariable>> backwardsAliasMap;

    public Flow() {
        this(null, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    private Flow(Flow previous, Map<DataFlowVariable, TypeStatement> approvedTypeStatements,
                 Map<DataFlowVariable, List<Implication>> implications, Map<RealVariable, Integer> assignmentIndex,
                 Map<RealVariable, RealVariable> directAliasMap, Map<RealVariable, Set<RealVariable>> backwardsAliasMap) {
        this.previous = previous;
        this.level = previous == null ? 0 : previous.level + 1;
        this.approvedTypeStatements = approvedTypeStatements;
        this.implications = implications;
        this.assignmentIndex = assignmentIndex;
        this.directAliasMap = directAliasMap;
        this.backwardsAliasMap = backwardsAliasMap;
    }

    /** A mutable copy, whose previous flow this is; this is frozen from now on. */
    public Flow fork() {
        frozen = true;
        return new Flow(this, new HashMap<>(approvedTypeStatements), new HashMap<>(implications),
                new HashMap<>(assignmentIndex), new HashMap<>(directAliasMap), new HashMap<>(backwardsAliasMap));
    }

    public boolean isFrozen() {
        return frozen;
    }

    void checkMutable() {
        if (frozen) throw new IllegalStateException("Changing a frozen flow");
    }

    public Set<DataFlowVariable> knownVariables() {
        Set<DataFlowVariable> result = new HashSet<>(approvedTypeStatements.keySet());
        result.addAll(directAliasMap.keySet());
        return result;
    }

    public RealVariable unwrapVariable(RealVariable variable) {
        return directAliasMap.getOrDefault(variable, variable);
    }

    public DataFlowVariable unwrapVariable(DataFlowVariable variable) {
        return variable instanceof RealVariable r ? unwrapVariable(r) : variable;
    }

    public TypeStatement getTypeStatement(DataFlowVariable variable) {
        TypeStatement s = approvedTypeStatements.get(unwrapVariable(variable));
        return s == null ? null : s.withVariable(variable);
    }

    /** Nullable. */
    public List<Implication> getImplications(DataFlowVariable variable) {
        return implications.get(variable);
    }

    public Integer assignmentIndex(RealVariable variable) {
        return assignmentIndex.get(variable);
    }

    Flow lowestCommonAncestor(Flow other) {
        Flow left = this;
        Flow right = other;
        while (left.level > right.level) {
            left = left.previous;
            if (left == null) return null;
        }
        while (right.level > left.level) {
            right = right.previous;
            if (right == null) return null;
        }
        while (left != right) {
            left = left.previous;
            right = right.previous;
            if (left == null || right == null) return null;
        }
        return left;
    }

    @Override
    public String toString() {
        return "Flow" + approvedTypeStatements.values() + " " + implications.values();
    }
}
