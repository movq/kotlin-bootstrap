package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.Symbol;
import kotlinbootstrap.symbols.TypeAliasSymbol;
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
    /** The function or property; or, for a name that is an object or class used as a value, the classifier. */
    final Symbol symbol;
    final int pos;
    final ConstraintSystem system;
    /** The call; null for a synthetic call. */
    final CallInfo call;
    /** The instance of a member: given, implicit or the object it was imported from; null if none. */
    TExpr dispatchReceiver;
    /** The receiver of an extension; null if not an extension. */
    TExpr extensionReceiver;
    /** Which receiver the explicit receiver of the call is, if any. */
    ExplicitReceiverKind explicitReceiverKind = ExplicitReceiverKind.NONE;
    TowerGroup group = TowerGroup.START_GROUP;
    /**
     * For a member, from the type parameters of its class to the arguments of
     * the receiver's type; for a constructor through a type alias, to the
     * alias's expansion.
     */
    Substitution memberSubstitution = Substitution.EMPTY;
    /** The type parameters to infer, if not the symbol's: for a constructor through a type alias, the alias's. */
    List<TypeParamSymbol> typeParams;
    /** For a constructor through a type alias, the alias. */
    TypeAliasSymbol typeAlias;
    /** For a classifier used as a value, the type of the object it stands for. */
    KType valueType;
    /** For an implicit invoke, the candidate of the variable it is called on, as FIR's candidateForCommonInvokeReceiver. */
    Candidate invokeReceiver;
    /** From the symbol's type parameters (and its class's, for a member) to the fresh variables and receiver's arguments. */
    Substitution substitutor = Substitution.EMPTY;
    List<TypeVariable> freshVariables = List.of();
    /** The arguments, in source order, which may contain uncompleted calls and postponed arguments. */
    final List<TExpr> arguments;
    /** For each argument, the index of its parameter; filled in by mapping the arguments. */
    int[] argumentMapping;
    /** How many parameters take their default values. */
    int numDefaults;
    /** Lambdas and callable references among the arguments, analyzed during completion. */
    final List<PostponedArgument> postponedArguments = new ArrayList<>();
    /** What is wrong with the candidate, if anything. */
    final List<Diagnostic> diagnostics = new ArrayList<>();
    private Applicability applicability = Applicability.RESOLVED;
    /** For the callable of a reference, what resolving the reference needs; null otherwise. */
    CallInfo.ReferenceInfo reference;
    /** For the callable of a reference, the reference's type, with the fresh variables. */
    KType referenceType;
    /** For the callable of a reference, how it is adapted to the type expected; null if it is not. */
    TExpr.ReferenceAdaptation adaptation;
    /** The arguments converted to SAM types, by identity; null if none. */
    Map<TExpr, SamResolver.SamConversion> samConversions;
    /** Builds the call's checked form once completed. */
    Function<CompletionWriter, TExpr> builder;

    enum ExplicitReceiverKind { NONE, DISPATCH, EXTENSION }

    /** Why a candidate is not fully applicable, as FIR's ResolutionDiagnostic. */
    record Diagnostic(Applicability applicability, String message) {
        @Override
        public String toString() {
            return message;
        }
    }

    Candidate(Symbol symbol, int pos, ConstraintSystem system, CallInfo call, List<TExpr> arguments) {
        this.symbol = symbol;
        this.pos = pos;
        this.system = system;
        this.call = call;
        this.arguments = arguments;
    }

    CallableSymbol callable() {
        return (CallableSymbol) symbol;
    }

    void addDiagnostic(Applicability applicability, String message) {
        diagnostics.add(new Diagnostic(applicability, message));
        if (applicability.compareTo(this.applicability) < 0) this.applicability = applicability;
    }

    Applicability applicability() {
        return applicability;
    }

    List<TypeParamSymbol> typeParams() {
        if (typeParams != null) return typeParams;
        return symbol instanceof CallableSymbol c ? c.typeParams() : List.of();
    }

    /**
     * Creates a variable for each type parameter, constrained by the
     * parameter's bounds, as FIR's CreateFreshTypeVariableSubstitutorStage.
     */
    void createFreshVariables() {
        List<TypeParamSymbol> typeParams = typeParams();
        if (typeParams.isEmpty()) {
            substitutor = memberSubstitution;
            return;
        }
        List<TypeVariable> variables = new ArrayList<>();
        Map<Object, TypeArg> fresh = new HashMap<>();
        for (TypeParamSymbol tp : typeParams) {
            TypeVariable v = new TypeVariable(tp.name(), tp);
            variables.add(v);
            fresh.put(tp, TypeArg.of(v.type()));
            system.registerVariable(v);
        }
        freshVariables = variables;
        // The member substitution may be in terms of the type parameters, as for a type alias.
        Substitution toVariables = new Substitution(fresh);
        Map<Object, TypeArg> map = new HashMap<>(fresh);
        memberSubstitution.map().forEach((k, v) -> map.putIfAbsent(k, toVariables.applyArg(v)));
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
        if (system.hasContradiction()) addDiagnostic(Applicability.INAPPLICABLE, "Contradictory bounds");
    }

    /** A type of the symbol's declaration with the candidate's variables and receiver's arguments. */
    KType substitute(KType type) {
        return substitutor.apply(type);
    }

    /** The type of the call before completion: the return type with the fresh variables. */
    KType returnType() {
        return symbol instanceof CallableSymbol c ? substitutor.apply(c.returnType()) : valueType;
    }

    boolean isSuccessful() {
        return applicability.isSuccess() && !system.hasContradiction();
    }

    @Override
    public String toString() {
        return symbol + (isSuccessful() ? " OK" : " FAIL " + diagnostics);
    }
}
