package kotlinbootstrap.check;

import java.util.List;

import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.types.KType;

/**
 * A lambda or anonymous function that is an argument, whose analysis waits
 * for the completion of its call, as FIR's anonymous function expressions
 * in dependent mode: the state of the {@link TExpr.Unresolved} it is
 * checked to. The types it declares are resolved at once.
 */
final class LambdaExpression {
    /** An {@link Expr.Lambda} or {@link Expr.AnonymousFunction}. */
    final Expr expr;
    /** The label its returns use: given, or the name of the call it is passed to; nullable. */
    final String label;
    /** The context it is in, which its body is checked in. */
    final Context context;
    /** The declared receiver of an anonymous function; nullable. */
    final KType receiverType;
    /** The declared types of the value parameters, null where not declared. */
    final List<KType> declaredParamTypes;
    /** For an anonymous function, the declared return type, or Unit for a block body; else null. */
    final KType declaredReturnType;
    final boolean isLambda;
    /** A lambda with {@code ->}, or any anonymous function: one without may have an implicit {@code it}. */
    final boolean hasExplicitParameterList;
    final boolean isSuspend;
    /** The atom of the candidate being completed. */
    PostponedArgument atom;

    LambdaExpression(Expr expr, String label, Context context, KType receiverType, List<KType> declaredParamTypes,
                     KType declaredReturnType, boolean isLambda, boolean hasExplicitParameterList, boolean isSuspend) {
        this.expr = expr;
        this.label = label;
        this.context = context;
        this.receiverType = receiverType;
        this.declaredParamTypes = declaredParamTypes;
        this.declaredReturnType = declaredReturnType;
        this.isLambda = isLambda;
        this.hasExplicitParameterList = hasExplicitParameterList;
        this.isSuspend = isSuspend;
    }

    int pos() {
        return expr.pos();
    }

    @Override
    public String toString() {
        return (isLambda ? "lambda" : "fun") + (label != null ? "@" + label : "") + " at " + pos();
    }
}
