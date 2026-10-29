package kotlinbootstrap.dfa;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * The real variables made so far in a body, as FIR's VariableStorage: one
 * instance of each, and for each variable the member variables it is the
 * receiver of, so that statements about {@code x.p} can follow {@code x}.
 */
public final class VariableStorage {
    private final Map<RealVariable, RealVariable> realVariables = new HashMap<>();
    private final Map<RealVariable, Set<RealVariable>> memberVariables = new HashMap<>();

    /** The known instance equal to the variable; null if none. */
    public RealVariable getKnown(RealVariable variable) {
        return realVariables.get(variable);
    }

    /** The instance equal to the variable, remembering it if new; its receivers must be known. */
    public RealVariable rememberWithKnownReceivers(RealVariable variable) {
        RealVariable known = realVariables.get(variable);
        if (known != null) return known;
        realVariables.put(variable, variable);
        if (variable.dispatchReceiver() != null) members(variable.dispatchReceiver()).add(variable);
        if (variable.extensionReceiver() != null) members(variable.extensionReceiver()).add(variable);
        return variable;
    }

    /** The instance equal to the variable, remembering it and its receivers if new. */
    public RealVariable remember(RealVariable variable) {
        return rememberWithKnownReceivers(mapReceivers(variable, this::remember));
    }

    private Set<RealVariable> members(RealVariable receiver) {
        return memberVariables.computeIfAbsent(receiver, k -> new LinkedHashSet<>());
    }

    private static RealVariable mapReceivers(RealVariable v, UnaryOperator<RealVariable> f) {
        return new RealVariable(v.symbol(), v.implicit(),
                v.dispatchReceiver() == null ? null : f.apply(v.dispatchReceiver()),
                v.extensionReceiver() == null ? null : f.apply(v.extensionReceiver()), v.originalType());
    }

    /**
     * Calls the action with each known member variable of {@code from},
     * and if {@code to} is not null, the same member of {@code to}.
     */
    void replaceReceiverReferencesInMembers(RealVariable from, RealVariable to,
                                            BiConsumer<RealVariable, RealVariable> processMember) {
        Set<RealVariable> members = memberVariables.get(from);
        if (members == null) return;
        for (RealVariable member : List.copyOf(members)) {
            RealVariable remapped = to == null ? null
                    : rememberWithKnownReceivers(mapReceivers(member, r -> r.equals(from) ? to : r));
            processMember.accept(member, remapped);
        }
    }
}
