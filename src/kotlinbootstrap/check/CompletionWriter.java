package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;

/**
 * Writes the results of completing a call into its checked form, as FIR's
 * FirCallCompletionResultsWriterTransformer: the inferred types replace
 * the type variables, and the call and the uncompleted calls and analyzed
 * lambdas among its arguments get their final form.
 */
final class CompletionWriter {
    private final TypeSystem ts;
    private final Substitution substitution;
    /** The lambdas being written, by function: the expected return type and the types of the returned values. */
    private final Map<CheckedFunction, LambdaReturns> lambdaReturns = new IdentityHashMap<>();

    /** @param expectedType nullable */
    private record LambdaReturns(KType expectedType, List<KType> types) {
    }

    /** @param substitution from the type variables to their results */
    CompletionWriter(TypeSystem ts, Substitution substitution) {
        this.ts = ts;
        this.substitution = substitution;
    }

    /** A type with the type variables substituted, and integer literal types replaced by their default types. */
    KType substitute(KType type) {
        return approximateIntegerLiteral(substitution.apply(type), null);
    }

    /** The type of a call: substituted and approximated, as substituteType in kotlinc. */
    KType callType(Candidate candidate, KType type) {
        KType substituted = substitute(candidate.substitutor.apply(type));
        return TypeApproximator.approximateToSuperTypeOrSelf(ts, substituted,
                TypeApproximator.Config.INTERMEDIATE_APPROXIMATION_AFTER_COMPLETION);
    }

    /** The type arguments of a call, for the type parameters of the candidate's symbol. */
    List<KType> typeArguments(Candidate candidate) {
        List<KType> result = new ArrayList<>();
        for (var tp : candidate.callable().typeParams()) {
            KType substituted = substitute(candidate.substitutor.apply(new KType.TypeParamType(tp, false)));
            result.add(TypeApproximator.approximateToSuperTypeOrSelf(ts, substituted,
                    TypeApproximator.Config.TYPE_ARGUMENT_AFTER_COMPLETION));
        }
        return result;
    }

    /** Completes an expression: the uncompleted calls and postponed arguments in it, and the types. */
    TExpr write(TExpr e) {
        return switch (e) {
            case TExpr.Unresolved u when u.state() instanceof Candidate c -> c.builder.apply(this);
            case TExpr.Unresolved u when u.state() instanceof PostponedArgument p -> write(p.result());
            case TExpr.Unresolved u when u.state() instanceof LambdaExpression l -> writeLambda(l, null);
            case TExpr.Unresolved u when u.state() instanceof CallableReferenceExpression r -> write(r.atom.result());
            case TExpr.Return r when lambdaReturns.containsKey(r.target()) -> {
                LambdaReturns returns = lambdaReturns.get(r.target());
                TExpr value = r.value() == null ? null : writeArgument(r.value(), returns.expectedType());
                returns.types().add(value == null ? unit() : value.type());
                yield new TExpr.Return(r.pos(), r.type(), r.target(), value);
            }
            default -> TExprs.map(e, this::write, this::substitute);
        };
    }

    /**
     * Completes an argument, given the type of its parameter (with the
     * candidate's type variables): an integer literal gets the type of the
     * parameter if that is one of its possible types.
     */
    TExpr writeArgument(TExpr e, KType expected) {
        if (expected == null) return write(e);
        return switch (e) {
            case TExpr.Unresolved u when u.state() instanceof LambdaExpression l -> writeLambda(l, expected);
            case TExpr.Const c when Types.lowerBound(c.type()) instanceof KType.IntegerLiteral ->
                    new TExpr.Const(c.pos(), approximateIntegerLiteral(c.type(), substitute(expected)), c.value());
            case TExpr.Block b when TExprs.lastExpression(b) != null -> {
                List<TExpr> statements = new ArrayList<>();
                for (TExpr s : b.statements().subList(0, b.statements().size() - 1)) statements.add(write(s));
                TExpr original = b.statements().getLast();
                TExpr last = writeArgument(original, expected);
                statements.add(last);
                // A block coerced to Unit keeps its type.
                KType type = Objects.equals(b.type(), original.type()) ? last.type() : substitute(b.type());
                yield new TExpr.Block(b.pos(), type, statements);
            }
            default -> write(e);
        };
    }

    // ==== Lambdas ====

    /**
     * Writes an analyzed lambda, as FIR's transformAnonymousFunction: the
     * values it returns are arguments for its expected return type, and its
     * return type is computed from them.
     *
     * @param expected the type of the parameter it is passed for (with the candidate's variables); nullable
     */
    private TExpr writeLambda(LambdaExpression lambda, KType expected) {
        LambdaAtom atom = lambda.atom instanceof LambdaWithTypeVariableAtom t ? t.subAtom : (LambdaAtom) lambda.atom;
        TExpr.Lambda analyzed = (TExpr.Lambda) atom.result();
        CheckedFunction function = analyzed.function();
        KType expectedType = expected == null ? null : Types.lowerBound(substitute(expected));
        if (!FunctionTypes.isFunctionType(expectedType)) expectedType = null;

        KType expectedReturnType;
        if (function.returnType() != null) {
            expectedReturnType = substitute(function.returnType());
        } else if (atom.explicitEmptyReturn) {
            expectedReturnType = unit();
        } else if (expectedType != null
                && FunctionTypes.returnType(ts, (KType.ClassType) expectedType) instanceof KType.ClassType c) {
            expectedReturnType = c;
        } else {
            expectedReturnType = substitute(atom.returnType);
        }

        LambdaReturns returns = new LambdaReturns(expectedReturnType, new ArrayList<>());
        lambdaReturns.put(function, returns);
        TExpr body = function.body();
        if (lambda.isLambda) {
            TExpr.Block block = (TExpr.Block) body;
            TExpr last = block.statements().isEmpty() ? null : block.statements().getLast();
            body = writeArgument(body, expectedReturnType);
            // The last statement is returned, unless a return or no expression.
            if (last == null || TExprs.isStatement(last)) {
                returns.types().add(unit());
            } else if (!(last instanceof TExpr.Return r && r.target() == function)) {
                returns.types().add(((TExpr.Block) body).statements().getLast().type());
            }
        } else {
            body = write(body);
        }
        lambdaReturns.remove(function);

        KType returnType = computeReturnType(lambda, expectedReturnType, returns.types(), atom.explicitEmptyReturn);
        function.setBody(body);
        function.setReturnType(returnType);
        FunctionTypes.Kind kind = expectedType != null ? FunctionTypes.kind(expectedType)
                : atom.kind != null ? atom.kind : FunctionTypes.Kind.FUNCTION;
        List<KType> params = new ArrayList<>();
        for (LocalVariable p : function.params()) params.add(p.type());
        KType type = FunctionTypes.create(ts.table(), kind.nonReflect(), List.of(), function.receiverType(), params,
                returnType);
        return new TExpr.Lambda(analyzed.pos(), type, function, analyzed.anonymousFunction());
    }

    /**
     * The return type of a lambda passed to a call, as kotlinc's
     * computeReturnType: the expected one, unless the lambda returns Unit.
     */
    private KType computeReturnType(LambdaExpression lambda, KType expectedReturnType, List<KType> returnedTypes,
                                    boolean explicitEmptyReturn) {
        if (lambda.isLambda) {
            if (BodyChecker.isUnitOrFlexibleUnit(expectedReturnType)) return unit();
            if (explicitEmptyReturn) return expectedReturnType != null ? expectedReturnType : unit();
        }
        KType commonSuperType = returnedTypes.isEmpty() ? unit() : ts.commonSuperType(returnedTypes);
        if (lambda.expr instanceof Expr.AnonymousFunction f && f.function().returnType() != null) {
            return lambda.declaredReturnType;
        }
        boolean isUnit = commonSuperType instanceof KType.ClassType c && !c.nullable()
                && c.symbol().id().fqName().equals("kotlin.Unit");
        return !isUnit && expectedReturnType != null ? expectedReturnType : commonSuperType;
    }

    private KType unit() {
        return ts.classType("kotlin.Unit");
    }

    /** An integer literal type replaced by the expected type if possible, else its default type. */
    static KType approximateIntegerLiteral(KType type, KType expected) {
        return switch (type) {
            case KType.IntegerLiteral l -> TypeApproximator.integerLiteralType(l, expected).withNullable(l.nullable());
            case KType.Flexible f when f.lower() instanceof KType.IntegerLiteral
                    || f.upper() instanceof KType.IntegerLiteral ->
                    Types.flexible(approximateIntegerLiteral(f.lower(), expected),
                            approximateIntegerLiteral(f.upper(), expected));
            default -> type;
        };
    }
}
