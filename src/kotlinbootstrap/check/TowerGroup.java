package kotlinbootstrap.check;

/**
 * The priority of a level of the tower, as FIR's TowerGroup: candidates
 * from a smaller group win over those from a bigger one. A group is a
 * path of kinds, each with a depth for the kinds that have one, packed
 * into a long that compares as the paths do: 4 bits per kind, 16 per depth.
 *
 * <p>The implicit invoke of a variable, {@code f(x)} for {@code f.invoke(x)},
 * has the group of its invoke with the variable's as the receiver group.
 */
final class TowerGroup implements Comparable<TowerGroup> {
    // Kinds, in order of priority.
    private static final int START = 0;
    private static final int QUALIFIER_OR_CLASSIFIER = 1;
    private static final int TOP_PRIORITIZED = 3;
    private static final int MEMBER = 4;
    private static final int INVOKE_EXTENSION_WITH_IMPLICIT_RECEIVER = 5;
    private static final int LOCAL = 6;
    private static final int IMPLICIT_OR_NON_LOCAL = 7;
    private static final int CONTEXT_RECEIVER = 8;
    private static final int QUALIFIER_VALUE = 9;
    private static final int LAST = 15;

    private static final int KIND_BITS = 4;
    private static final int DEPTH_BITS = 16;
    private static final long USED_BITS_MASK = 0b111111;
    private static final int USABLE_BITS = Long.numberOfLeadingZeros(USED_BITS_MASK);

    /** For the implicit invoke of a variable, how the invoke was found. */
    enum InvokePriority { NONE, INVOKE_RECEIVER, COMMON_INVOKE, INVOKE_EXTENSION }

    static final TowerGroup EMPTY_ROOT = new TowerGroup(0, InvokePriority.NONE, null);
    static final TowerGroup START_GROUP = EMPTY_ROOT.kind(START);
    static final TowerGroup QUALIFIER_OR_CLASSIFIER_GROUP = EMPTY_ROOT.kind(QUALIFIER_OR_CLASSIFIER);
    static final TowerGroup QUALIFIER_VALUE_GROUP = EMPTY_ROOT.kind(QUALIFIER_VALUE);
    static final TowerGroup LAST_GROUP = EMPTY_ROOT.kind(LAST);

    private final long code;
    private final InvokePriority invokePriority;
    /** For an implicit invoke, the group of the variable. */
    private final TowerGroup receiverGroup;

    private TowerGroup(long code, InvokePriority invokePriority, TowerGroup receiverGroup) {
        this.code = code;
        this.invokePriority = invokePriority;
        this.receiverGroup = receiverGroup;
    }

    // ==== Subgroups ====

    TowerGroup member() {
        return kind(MEMBER);
    }

    TowerGroup invokeExtensionWithImplicitReceiver() {
        return kind(INVOKE_EXTENSION_WITH_IMPLICIT_RECEIVER);
    }

    TowerGroup local(int depth) {
        return kind(LOCAL, depth);
    }

    TowerGroup implicit(int depth) {
        return kind(IMPLICIT_OR_NON_LOCAL, depth);
    }

    TowerGroup nonLocal(int depth) {
        return kind(IMPLICIT_OR_NON_LOCAL, depth);
    }

    TowerGroup contextReceiver(int depth) {
        return kind(CONTEXT_RECEIVER, depth);
    }

    TowerGroup topPrioritized(int depth) {
        return kind(TOP_PRIORITIZED, depth);
    }

    /** The group of an invoke found in this group, for a variable found in the receiver group. */
    TowerGroup invokeReceiver(TowerGroup receiverGroup, InvokePriority priority) {
        return new TowerGroup(code, priority, receiverGroup);
    }

    TowerGroup withInvokePriority(InvokePriority priority) {
        return priority == InvokePriority.NONE ? this : new TowerGroup(code, priority, null);
    }

    private TowerGroup kind(int index) {
        long usedBits = code & USED_BITS_MASK;
        long newUsedBits = usedBits + KIND_BITS;
        if (newUsedBits > USABLE_BITS) throw new IllegalStateException("Tower group overflow");
        return new TowerGroup(code | (long) index << (64 - newUsedBits) | newUsedBits, invokePriority, receiverGroup);
    }

    private TowerGroup kind(int index, int depth) {
        if (depth > 0xFFFF) throw new IllegalStateException("Tower group depth overflow: " + depth);
        long usedBits = code & USED_BITS_MASK;
        long kindUsedBits = usedBits + KIND_BITS;
        long depthUsedBits = kindUsedBits + DEPTH_BITS;
        if (depthUsedBits > USABLE_BITS) throw new IllegalStateException("Tower group overflow");
        return new TowerGroup(code | (long) index << (64 - kindUsedBits) | (long) depth << (64 - depthUsedBits)
                | depthUsedBits, invokePriority, receiverGroup);
    }

    // ==== Comparison ====

    @Override
    public int compareTo(TowerGroup other) {
        if (receiverGroup == null && other.receiverGroup == null
                && invokePriority == InvokePriority.NONE && other.invokePriority == InvokePriority.NONE) {
            return Long.compareUnsigned(code, other.code);
        }
        long receiverCode = receiverGroup == null ? 0 : receiverGroup.code;
        long otherReceiverCode = other.receiverGroup == null ? 0 : other.receiverGroup.code;
        long max = Long.compareUnsigned(code, receiverCode) >= 0 ? code : receiverCode;
        long otherMax = Long.compareUnsigned(other.code, otherReceiverCode) >= 0 ? other.code : otherReceiverCode;
        int result = Long.compareUnsigned(max, otherMax);
        if (result != 0) return result;
        int byPriority = invokePriority.compareTo(other.invokePriority);
        if (byPriority != 0) return byPriority;
        if (receiverCode == max && otherReceiverCode == max) return Long.compareUnsigned(code, other.code);
        return Long.compareUnsigned(receiverCode, otherReceiverCode);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TowerGroup g) || code != g.code || invokePriority != g.invokePriority) return false;
        return receiverGroup == null || g.receiverGroup == null || receiverGroup.equals(g.receiverGroup);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(code) * 31 + invokePriority.hashCode();
    }

    @Override
    public String toString() {
        return Long.toBinaryString(code) + (invokePriority == InvokePriority.NONE ? "" : " " + invokePriority)
                + (receiverGroup == null ? "" : " receiver " + receiverGroup);
    }
}
