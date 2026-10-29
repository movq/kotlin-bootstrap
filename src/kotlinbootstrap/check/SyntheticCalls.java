package kotlinbootstrap.check;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;
import kotlinbootstrap.types.Variance;

/**
 * The synthetic functions whose calls give control structures their types,
 * as FIR's FirSyntheticCallGenerator: a {@code when} (or {@code if}) is a
 * call of {@code fun <K> WHEN(vararg branches: K): K}, so that its type is
 * inferred from its branches and the expected type like any call's.
 */
final class SyntheticCalls {
    static final String WHEN = "WHEN_CALL";
    static final String TRY = "TRY_CALL";
    static final String CHECK_NOT_NULL = "CHECK_NOT_NULL_CALL";
    static final String ELVIS = "ELVIS_CALL";
    static final String EQUALITY = "EQUALITY_CALL";
    static final String ACCEPT = "ACCEPT_SPECIFIC_TYPE";

    private final TypeSystem ts;
    private final ArgumentChecker arguments;
    /** fun <K> WHEN(vararg branches: K): K */
    private final FunctionSymbol whenFunction;
    /** fun <K> TRY(vararg branches: K): K */
    private final FunctionSymbol tryFunction;
    /** fun <K> CHECK_NOT_NULL(arg: K?): K & Any */
    private final FunctionSymbol checkNotNullFunction;
    /** fun <K> ELVIS(x: K?, y: K): K */
    private final FunctionSymbol elvisFunction;
    /** fun EQUALITY(x: Any?, y: Any?): Boolean */
    private final FunctionSymbol equalityFunction;

    SyntheticCalls(TypeSystem ts, ArgumentChecker arguments) {
        this.ts = ts;
        this.arguments = arguments;
        this.whenFunction = select(WHEN);
        this.tryFunction = select(TRY);
        TypeParamSymbol k = typeParam();
        KType kType = new KType.TypeParamType(k, false);
        this.checkNotNullFunction = function(CHECK_NOT_NULL, k, List.of(param("arg", kType.withNullable(true), false)),
                Types.makeDefinitelyNotNull(kType));
        TypeParamSymbol e = typeParam();
        KType eType = new KType.TypeParamType(e, false);
        this.elvisFunction = function(ELVIS, e, List.of(param("x", eType.withNullable(true), false),
                param("y", eType, false)), eType);
        this.equalityFunction = new FunctionSymbol(EQUALITY, Origin.SYNTHETIC, null, Visibility.PUBLIC, Modality.FINAL,
                Set.of(), false, null, List::of,
                () -> new CallableSymbol.Signature(null, List.of(),
                        List.of(param("x", ts.nullableAny(), false), param("y", ts.nullableAny(), false))),
                () -> ts.classType("kotlin.Boolean"));
    }

    boolean isElvisTypeParam(TypeParamSymbol tp) {
        return elvisFunction.typeParams().getFirst() == tp;
    }

    private FunctionSymbol select(String name) {
        TypeParamSymbol k = typeParam();
        KType kType = new KType.TypeParamType(k, false);
        return function(name, k, List.of(param("branches", kType, true)), kType);
    }

    private TypeParamSymbol typeParam() {
        return new TypeParamSymbol("K", Origin.SYNTHETIC, Variance.INVARIANT, false, () -> List.of(ts.nullableAny()));
    }

    private static ValueParamSymbol param(String name, KType type, boolean vararg) {
        return new ValueParamSymbol(name, type, false, vararg ? Set.of(Modifier.VARARG) : Set.of(), null);
    }

    private static FunctionSymbol function(String name, TypeParamSymbol tp, List<ValueParamSymbol> params, KType returnType) {
        return new FunctionSymbol(name, Origin.SYNTHETIC, null, Visibility.PUBLIC, Modality.FINAL, Set.of(), false, null,
                () -> List.of(tp), () -> new CallableSymbol.Signature(null, List.of(), params), () -> returnType);
    }

    // ==== Calls ====

    Candidate whenCall(int pos, List<TExpr> branches, Function<CompletionWriter, TExpr> builder) {
        return call(whenFunction, pos, branches, builder);
    }

    Candidate tryCall(int pos, List<TExpr> branches, Function<CompletionWriter, TExpr> builder) {
        return call(tryFunction, pos, branches, builder);
    }

    Candidate checkNotNullCall(int pos, TExpr arg, Function<CompletionWriter, TExpr> builder) {
        return call(checkNotNullFunction, pos, List.of(arg), builder);
    }

    Candidate elvisCall(int pos, TExpr lhs, TExpr rhs, Function<CompletionWriter, TExpr> builder) {
        return call(elvisFunction, pos, List.of(lhs, rhs), builder);
    }

    /** For {@code a == b} whose right side's type is not known yet, e.g. an integer literal. */
    Candidate equalityCall(int pos, TExpr left, TExpr right, Function<CompletionWriter, TExpr> builder) {
        return call(equalityFunction, pos, List.of(left, right), builder);
    }

    /** A call of {@code fun accept(p: type): Unit}, for a lambda or callable reference not passed to a call. */
    Candidate acceptCall(int pos, KType type, TExpr arg, Function<CompletionWriter, TExpr> builder) {
        FunctionSymbol accept = new FunctionSymbol(ACCEPT, Origin.SYNTHETIC, null, Visibility.PUBLIC, Modality.FINAL,
                Set.of(), false, null, List::of,
                () -> new CallableSymbol.Signature(null, List.of(), List.of(param("reference", type, false))),
                () -> ts.classType("kotlin.Unit"));
        return call(accept, pos, List.of(arg), builder);
    }

    /** The candidate for a synthetic call, with the constraints from its arguments. */
    private Candidate call(FunctionSymbol function, int pos, List<TExpr> args,
                           Function<CompletionWriter, TExpr> builder) {
        ConstraintSystem system = new ConstraintSystem(ts);
        for (TExpr arg : args) ArgumentChecker.addSubsystemFromArgument(system, arg);
        Candidate candidate = new Candidate(function, pos, system, null, args);
        candidate.builder = builder;
        candidate.createFreshVariables();
        List<ValueParamSymbol> params = function.signature().params();
        for (int i = 0; i < args.size(); i++) {
            ValueParamSymbol p = params.get(Math.min(i, params.size() - 1));
            arguments.resolveArgument(candidate, args.get(i), candidate.substitutor.apply(p.type()), false, false);
        }
        ResolutionStages.resolveCallableReferences(candidate);
        return candidate;
    }

    /** The type the parameter of the synthetic function for the argument has, with the candidate's variables. */
    static KType parameterType(Candidate candidate, int argument) {
        List<ValueParamSymbol> params = candidate.callable().signature().params();
        return candidate.substitutor.apply(params.get(Math.min(argument, params.size() - 1)).type());
    }
}
