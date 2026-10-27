package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;

/**
 * A function or property considered for a call, with the constraint system
 * that decides whether it applies and what its type arguments are, as
 * FIR's Candidate. Control structures are calls of synthetic functions
 * (see {@link SyntheticCalls}), so that their types are inferred the same way.
 *
 * <p>The candidate a call resolves to stays with the call until it is
 * completed: a call that is an argument of another is completed with it,
 * and until then is a {@link TExpr.Unresolved} with the candidate as its state.
 */
final class Candidate {
    final CallableSymbol symbol;
    final int pos;
    final ConstraintSystem system;
    /** From the symbol's type parameters to the fresh variables. */
    Substitution substitutor = Substitution.EMPTY;
    List<TypeVariable> freshVariables = List.of();
    /** The arguments, in source order, which may contain uncompleted calls and postponed arguments. */
    final List<TExpr> arguments;
    /** Lambdas and callable references among the arguments, analyzed during completion. */
    final List<PostponedArgument> postponedArguments = new ArrayList<>();
    /** Why the candidate does not apply; empty if it does. */
    final List<String> errors = new ArrayList<>();
    /** Builds the call's checked form once completed. */
    Function<CompletionWriter, TExpr> builder;

    Candidate(CallableSymbol symbol, int pos, ConstraintSystem system, List<TExpr> arguments) {
        this.symbol = symbol;
        this.pos = pos;
        this.system = system;
        this.arguments = arguments;
    }

    /**
     * Creates a variable for each type parameter, constrained by the
     * parameter's bounds, as FIR's CreateFreshTypeVariableSubstitutorStage.
     */
    void createFreshVariables() {
        List<TypeParamSymbol> typeParams = symbol.typeParams();
        if (typeParams.isEmpty()) return;
        List<TypeVariable> variables = new ArrayList<>();
        Map<TypeParamSymbol, TypeArg> map = new HashMap<>();
        for (TypeParamSymbol tp : typeParams) {
            TypeVariable v = new TypeVariable(tp.name(), tp);
            variables.add(v);
            map.put(tp, TypeArg.of(v.type()));
            system.registerVariable(v);
        }
        freshVariables = variables;
        substitutor = new Substitution(map);
        for (TypeVariable v : variables) {
            for (KType bound : v.typeParam().bounds()) {
                // Any? adds nothing.
                if (Types.lowerBound(bound) instanceof KType.ClassType c && c.args().isEmpty()
                        && c.symbol().id().fqName().equals("kotlin.Any") && Types.isMarkedNullable(Types.upperBound(bound))) {
                    continue;
                }
                system.addSubtypeConstraint(v.type(), substitutor.apply(bound),
                        ConstraintPosition.of(ConstraintPosition.Kind.DECLARED_UPPER_BOUND));
            }
        }
        if (system.hasContradiction()) errors.add("Contradictory bounds");
    }

    /** The type of the call before completion: the return type with the fresh variables. */
    KType returnType() {
        return substitutor.apply(symbol.returnType());
    }

    boolean isSuccessful() {
        return errors.isEmpty() && !system.hasContradiction();
    }

    @Override
    public String toString() {
        return symbol + (isSuccessful() ? " OK" : " FAIL " + errors);
    }
}
