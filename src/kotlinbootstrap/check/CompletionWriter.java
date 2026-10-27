package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.List;

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
        for (var tp : candidate.symbol.typeParams()) {
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
            default -> TExprs.map(e, this::write, this::substitute);
        };
    }

    /**
     * Completes an argument, given the type of its parameter (with the
     * candidate's type variables): an integer literal gets the type of the
     * parameter if that is one of its possible types.
     */
    TExpr writeArgument(TExpr e, KType expected) {
        return switch (e) {
            case TExpr.Const c when Types.lowerBound(c.type()) instanceof KType.IntegerLiteral ->
                    new TExpr.Const(c.pos(), approximateIntegerLiteral(c.type(), substitute(expected)), c.value());
            case TExpr.Block b when TExprs.lastExpression(b) != null -> {
                List<TExpr> statements = new ArrayList<>();
                for (TExpr s : b.statements().subList(0, b.statements().size() - 1)) statements.add(write(s));
                TExpr original = b.statements().getLast();
                TExpr last = writeArgument(original, expected);
                statements.add(last);
                // A block coerced to Unit keeps its type.
                KType type = b.type().equals(original.type()) ? last.type() : substitute(b.type());
                yield new TExpr.Block(b.pos(), type, statements);
            }
            default -> write(e);
        };
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
