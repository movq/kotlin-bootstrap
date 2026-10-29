package kotlinbootstrap.check;

import kotlinbootstrap.types.KType;

/**
 * A callable reference whose resolution waits for the type expected of
 * it, as FIR's FirCallableReferenceAccess in dependent mode: the state of
 * the {@link TExpr.Unresolved} it is checked to. Its left side is checked
 * at once.
 */
final class CallableReferenceExpression {
    final int pos;
    final String name;
    /** The left side: a value, or a class or package as a qualifier; null for {@code ::f}. */
    final CallInfo.Receiver lhs;
    /**
     * For a class (not an object) on the left, its type, which an unbound
     * reference takes as its first parameter, as FIR's CallableReferenceLhsAsType; nullable.
     */
    final KType lhsAsType;
    /** The context it is in, which it is resolved in. */
    final Context context;
    /** The resolver of the body it is in. */
    final CallResolver resolver;
    /** The atom of the candidate being completed. */
    CallableReferenceAtom atom;

    /** The stand-in for the receiver of an unbound reference, of the type on the left. */
    record UnboundReceiver(CallableReferenceExpression reference) {
    }

    CallableReferenceExpression(int pos, String name, CallInfo.Receiver lhs, KType lhsAsType, Context context,
                                CallResolver resolver) {
        this.pos = pos;
        this.name = name;
        this.lhs = lhs;
        this.lhsAsType = lhsAsType;
        this.context = context;
        this.resolver = resolver;
    }

    TExpr unboundReceiver() {
        return new TExpr.Unresolved(pos, lhsAsType, new UnboundReceiver(this));
    }

    static boolean isUnboundReceiver(TExpr e) {
        return e instanceof TExpr.Unresolved u && u.state() instanceof UnboundReceiver;
    }

    @Override
    public String toString() {
        return "::" + name + " at " + pos;
    }
}
