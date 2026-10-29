package kotlinbootstrap.check;

import java.util.List;

import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;

/**
 * A call to resolve, as FIR's CallInfo: what is called, on what, with
 * which arguments. The arguments and the explicit receiver are checked
 * already, the arguments without completing their calls.
 *
 * @param explicitReceiver null if none
 * @param typeArgs         explicit type arguments, empty if none; a null element is {@code _}
 * @param isImplicitInvoke a call of invoke on a value, written {@code f(x)}
 */
record CallInfo(Kind kind, String name, Receiver explicitReceiver, List<Argument> arguments, List<TypeArg> typeArgs,
                TExpr.CallOrigin origin, boolean isImplicitInvoke, int pos) {

    /** A call of a function, a variable read, or the callable a reference {@code ::f} is to: either. */
    enum Kind { FUNCTION, VARIABLE, CALLABLE_REFERENCE }

    /**
     * @param name   for a named argument; null otherwise
     * @param spread {@code *a}, for a vararg parameter
     * @param trailingLambda a lambda after the parentheses
     */
    record Argument(TExpr value, String name, boolean spread, boolean trailingLambda) {
    }

    /** What a call is on: a value, {@code super}, or a class or package named as a qualifier. */
    sealed interface Receiver {
    }

    record ValueReceiver(TExpr value) implements Receiver {
    }

    /** {@code super} or {@code super<T>}: the instance of this class, with the members of the supertype. */
    record SuperReceiver(TExpr thisValue, KType supertype) implements Receiver {
    }

    /**
     * A class, type alias or package used as a qualifier, as FIR's FirResolvedQualifier.
     *
     * @param classifier    null for a package
     * @param value         the object, or the companion object, the name also stands for; nullable
     */
    record Qualifier(int pos, String packageName, ClassifierSymbol classifier, List<KType> typeArgs, TExpr value)
            implements Receiver {
    }

    /**
     * What resolving a callable reference needs besides the call, as FIR's CallableReferenceInfo.
     *
     * @param expectedType          the type expected of the reference, with the containing call's variables; nullable
     * @param baseSystem            the containing call's system, which each candidate starts with a copy of
     * @param hasSyntheticOuterCall not an argument, but checked as one of a synthetic call
     */
    record ReferenceInfo(CallableReferenceExpression expression, KType expectedType, ConstraintSystem baseSystem,
                         boolean hasSyntheticOuterCall) {
    }

    List<TExpr> argumentValues() {
        return arguments.stream().map(Argument::value).toList();
    }

    CallInfo withKind(Kind newKind, String newName) {
        return new CallInfo(newKind, newName, explicitReceiver, arguments, typeArgs, origin, isImplicitInvoke, pos);
    }

    CallInfo withReceiver(Receiver receiver, boolean implicitInvoke) {
        return new CallInfo(kind, name, receiver, arguments, typeArgs, origin, implicitInvoke, pos);
    }
}
