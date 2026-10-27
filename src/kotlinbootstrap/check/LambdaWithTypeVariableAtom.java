package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.List;

import kotlinbootstrap.infer.LambdaWithTypeVariableAsExpectedType;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.SubtypeChecker;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;

/**
 * A lambda argument expected to have the type of a variable, as FIR's
 * ConeLambdaWithTypeVariableAsExpectedTypeAtom: completion builds a
 * function type for it, then makes it a {@link LambdaAtom} expected to
 * have that type.
 */
final class LambdaWithTypeVariableAtom extends PostponedArgument implements LambdaWithTypeVariableAsExpectedType {
    final LambdaExpression expression;
    private final KType initialExpectedType;
    final Candidate containingCandidate;
    private KType revisedExpectedType;
    private List<KType> parameterTypesFromDeclaration;
    /** The atom it became, once transformed. */
    LambdaAtom subAtom;

    LambdaWithTypeVariableAtom(LambdaExpression expression, KType initialExpectedType, Candidate containingCandidate) {
        this.expression = expression;
        this.initialExpectedType = initialExpectedType;
        this.containingCandidate = containingCandidate;
    }

    void setSubAtom(LambdaAtom atom) {
        if (subAtom != null) throw new IllegalStateException("Already transformed: " + this);
        subAtom = atom;
        setAnalyzed();
    }

    @Override
    TExpr result() {
        return subAtom.result();
    }

    @Override
    void activate() {
        expression.atom = this;
    }

    @Override
    public List<KType> inputTypes() {
        return List.of(initialExpectedType);
    }

    @Override
    public KType outputType() {
        return null;
    }

    @Override
    public KType expectedType() {
        return revisedExpectedType != null ? revisedExpectedType : initialExpectedType;
    }

    @Override
    public KType revisedExpectedType() {
        return revisedExpectedType;
    }

    @Override
    public void reviseExpectedType(KType type) {
        revisedExpectedType = type;
    }

    @Override
    List<TypeVariable> typeVariables() {
        if (revisedExpectedType == null || !(Types.lowerBound(revisedExpectedType) instanceof KType.ClassType c)) {
            return List.of();
        }
        List<TypeVariable> result = new ArrayList<>();
        for (TypeArg a : c.args()) {
            if (!a.isStar() && SubtypeChecker.constructor(a.type()) instanceof TypeVariable v) result.add(v);
        }
        return result;
    }

    @Override
    List<TExpr> nestedArguments() {
        return subAtom == null ? List.of() : List.of(new TExpr.Unresolved(expression.pos(), null, subAtom));
    }

    // ==== Declared types ====

    @Override
    public List<KType> parameterTypesFromDeclaration() {
        return parameterTypesFromDeclaration;
    }

    @Override
    public void updateParameterTypesFromDeclaration(List<KType> types) {
        parameterTypesFromDeclaration = types;
    }

    @Override
    public List<KType> declaredParameterTypes() {
        LambdaExpression e = expression;
        if (e.declaredParamTypes.isEmpty() && e.receiverType == null) return null;
        List<KType> result = new ArrayList<>();
        if (e.receiverType != null) result.add(e.receiverType);
        result.addAll(e.declaredParamTypes);
        return result;
    }

    @Override
    public boolean isFunctionExpression() {
        return !expression.isLambda;
    }

    @Override
    public boolean isFunctionExpressionWithReceiver() {
        return !expression.isLambda && expression.receiverType != null;
    }

    @Override
    public int contextParameterCountOfFunctionExpression() {
        return 0;
    }

    @Override
    public boolean isSuspend() {
        return expression.isSuspend;
    }

    @Override
    public String toString() {
        return expression.toString();
    }
}
