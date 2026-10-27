package kotlinbootstrap.infer;

import java.util.List;

import kotlinbootstrap.types.KType;

/**
 * A lambda or anonymous function whose expected type is a type variable,
 * as kotlinc's LambdaWithTypeVariableAsExpectedTypeMarker: its declared
 * parameter types help to build the function type it is expected to have.
 */
public interface LambdaWithTypeVariableAsExpectedType extends PostponedAtomWithRevisableExpectedType {
    /** The declared parameter types, null where not declared; null if not collected yet. */
    List<KType> parameterTypesFromDeclaration();

    void updateParameterTypesFromDeclaration(List<KType> types);

    /**
     * The declared parameter types: for a lambda, of its value parameters,
     * or null if it declares none; for an anonymous function, also of its
     * context parameters and receiver.
     */
    List<KType> declaredParameterTypes();

    /** An anonymous function rather than a lambda. */
    boolean isFunctionExpression();

    default boolean isLambda() {
        return !isFunctionExpression();
    }

    /** An anonymous function with a receiver. */
    boolean isFunctionExpressionWithReceiver();

    /** The number of context parameters of an anonymous function; 0 for a lambda. */
    int contextParameterCountOfFunctionExpression();

    boolean isSuspend();
}
