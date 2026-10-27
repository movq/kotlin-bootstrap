package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.List;

import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeVariable;

/**
 * A lambda argument whose types are known up to the call's variables, as
 * FIR's ConeResolvedLambdaAtom: from the function type expected of it, or
 * else from its shape, with variables for what it does not declare.
 */
final class LambdaAtom extends PostponedArgument {
    final LambdaExpression expression;
    /** The type expected of the lambda; nullable. */
    final KType expectedType;
    final FunctionTypes.Kind kind;
    /** Nullable. */
    final KType receiverType;
    final List<KType> contextParameterTypes;
    final List<KType> parameterTypes;
    final KType returnType;
    /** The variable for the return type, if one was made for it. */
    final TypeVariable returnTypeVariable;
    /** {@code (T, P) -> R} passed for {@code T.(P) -> R}: the first parameter is the receiver. */
    final boolean coerceFirstParameterToExtensionReceiver;
    /** The call the lambda is an argument of; for a lambda returned from another, that lambda's call. */
    final Candidate containingCandidate;
    /** Once analyzed, the values it returns, explicitly or as its last expression. */
    List<TExpr> returnValues = List.of();
    /** Once analyzed, whether it has a {@code return} without a value. */
    boolean explicitEmptyReturn;

    LambdaAtom(LambdaExpression expression, KType expectedType, FunctionTypes.Kind kind, KType receiverType,
               List<KType> contextParameterTypes, List<KType> parameterTypes, KType returnType,
               TypeVariable returnTypeVariable, boolean coerceFirstParameterToExtensionReceiver,
               Candidate containingCandidate) {
        this.expression = expression;
        this.expectedType = expectedType;
        this.kind = kind;
        this.receiverType = receiverType;
        this.contextParameterTypes = contextParameterTypes;
        this.parameterTypes = parameterTypes;
        this.returnType = returnType;
        this.returnTypeVariable = returnTypeVariable;
        this.coerceFirstParameterToExtensionReceiver = coerceFirstParameterToExtensionReceiver;
        this.containingCandidate = containingCandidate;
    }

    @Override
    public List<KType> inputTypes() {
        List<KType> result = new ArrayList<>(parameterTypes);
        if (receiverType != null) result.add(receiverType);
        result.addAll(contextParameterTypes);
        return result;
    }

    @Override
    public KType outputType() {
        return returnType;
    }

    @Override
    public KType expectedType() {
        return expectedType;
    }

    @Override
    void activate() {
        expression.atom = this;
    }

    @Override
    List<TypeVariable> typeVariables() {
        return returnTypeVariable == null ? List.of() : List.of(returnTypeVariable);
    }

    @Override
    List<TExpr> nestedArguments() {
        return returnValues;
    }

    @Override
    public String toString() {
        return expression.toString();
    }
}
