package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.infer.CompletionMode;
import kotlinbootstrap.infer.CompletionModeCalculator;
import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.infer.ConstraintSystemCompleter;
import kotlinbootstrap.infer.PostponedAtom;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;

/**
 * Completes calls, as FIR's FirCallCompleter: adds the constraint from the
 * expected type, then either completes the call fully and writes the
 * results, or, for an argument of another call, partially, leaving it to
 * be completed with that call.
 */
final class CallCompleter {
    private final TypeSystem ts;
    private final BodyChecker checker;

    CallCompleter(TypeSystem ts, BodyChecker checker) {
        this.ts = ts;
        this.checker = checker;
    }

    TExpr completeCall(Candidate candidate, Mode mode) {
        KType initialType = candidate.returnType();
        addConstraintFromExpectedType(candidate, initialType, mode);
        CompletionMode completionMode = mode.forceFullCompletion() ? CompletionMode.FULL
                : CompletionModeCalculator.compute(candidate.system, initialType, notAnalyzedPostponedArguments(candidate));
        runCompletion(candidate, completionMode, initialType);
        if (completionMode == CompletionMode.PARTIAL) return new TExpr.Unresolved(candidate.pos, initialType, candidate);

        ConstraintSystem system = candidate.system;
        system.prepareForReading();
        if (system.hasContradiction() || !candidate.applicability().isSuccess()) {
            throw checker.error(candidate.pos, "Inapplicable " + candidate.symbol.name() + ": "
                    + (candidate.diagnostics.isEmpty() ? system.errors() : candidate.diagnostics));
        }
        return candidate.builder.apply(new CompletionWriter(ts, system.buildCurrentSubstitutor()));
    }

    private void addConstraintFromExpectedType(Candidate candidate, KType initialType, Mode mode) {
        if (!(mode instanceof Mode.WithExpectedType w)) return;
        KType expected = w.type();
        ConstraintSystem system = candidate.system;
        ConstraintPosition position = ConstraintPosition.of(ConstraintPosition.Kind.EXPECTED_TYPE);
        if (w.forceFullCompletion() && isSyntheticCallThatShouldUseEqualityConstraint(candidate, expected)) {
            ArgumentChecker.addEqualityConstraintIfCompatible(system, initialType, expected, position);
        } else if (w.fromCast()) {
            // TODO: expected types from casts for functions like findViewById
        } else if (isUnitOrFlexibleUnit(expected) && w.lastStatementInBlock()) {
            if (system.notFixedTypeVariables().isEmpty()) return;
            if (!(expected instanceof KType.Flexible)) {
                ArgumentChecker.addEqualityConstraintIfCompatible(system, initialType, expected, position);
            } else {
                ArgumentChecker.addSubtypeConstraintIfCompatible(system, initialType, expected, position);
            }
        } else {
            ArgumentChecker.addSubtypeConstraintIfCompatible(system, initialType, expected, position);
        }
    }

    /**
     * For the synthetic functions of when, try and !! (but not elvis), the
     * expected type gives an equality constraint, so that the variable is
     * not inferred to Nothing from one of the branches.
     */
    private boolean isSyntheticCallThatShouldUseEqualityConstraint(Candidate candidate, KType expected) {
        if (candidate.symbol.origin() != Origin.SYNTHETIC || candidate.callable().owner() != null) return false;
        KType upper = Types.upperBound(expected);
        if (upper instanceof KType.ClassType c && c.args().isEmpty()
                && (c.symbol() == ts.anyClass() || c.symbol().id().fqName().equals("kotlin.Unit"))) {
            return false;
        }
        if (candidate.symbol.name().equals(SyntheticCalls.CHECK_NOT_NULL) && Types.canBeNull(expected)) return false;
        // An elvis anywhere inside could have its right side's type changed.
        for (TypeVariable v : candidate.system.allTypeVariables()) {
            if (v.typeParam() != null && checker.synthetic().isElvisTypeParam(v.typeParam())) return false;
        }
        return true;
    }

    private static boolean isUnitOrFlexibleUnit(KType t) {
        return Types.lowerBound(t) instanceof KType.ClassType c && !c.nullable()
                && c.symbol().id().fqName().equals("kotlin.Unit");
    }

    // ==== Running completion ====

    void runCompletion(Candidate candidate, CompletionMode mode, KType topLevelType) {
        ConstraintSystem system = candidate.system;
        system.startCompletion();
        ConstraintSystemCompleter.complete(system, mode, topLevelType, new ConstraintSystemCompleter.Context() {
            @Override
            public List<TypeVariable> orderedTypeVariables() {
                return CallCompleter.orderedTypeVariables(candidate);
            }

            @Override
            public List<? extends PostponedAtom> postponedArguments() {
                return notAnalyzedPostponedArguments(candidate);
            }

            @Override
            public void analyze(PostponedAtom argument) {
                checker.analyzePostponed(system, (PostponedArgument) argument, candidate);
            }
        });
    }

    // ==== Traversal ====

    /** The fresh variables of the candidate and of the uncompleted calls among its arguments, in order. */
    static List<TypeVariable> orderedTypeVariables(Candidate candidate) {
        Set<TypeVariable> result = new LinkedHashSet<>();
        traverse(candidate, c -> result.addAll(c.freshVariables), p -> result.addAll(p.typeVariables()));
        return new ArrayList<>(result);
    }

    static List<PostponedArgument> notAnalyzedPostponedArguments(Candidate candidate) {
        List<PostponedArgument> result = new ArrayList<>();
        traverse(candidate, c -> { }, p -> {
            if (!p.analyzed()) result.add(p);
        });
        return result;
    }

    private interface Visitor<T> {
        void visit(T t);
    }

    /** Visits the candidate, and the uncompleted calls and postponed arguments among its arguments, recursively. */
    private static void traverse(Candidate candidate, Visitor<Candidate> candidates, Visitor<PostponedArgument> postponed) {
        Map<Object, Boolean> visited = new IdentityHashMap<>();
        traverse(candidate, candidates, postponed, visited);
    }

    private static void traverse(Candidate candidate, Visitor<Candidate> candidates, Visitor<PostponedArgument> postponed,
                                 Map<Object, Boolean> visited) {
        if (visited.put(candidate, true) != null) return;
        candidates.visit(candidate);
        for (TExpr argument : candidate.arguments) traverseArgument(argument, candidates, postponed, visited);
    }

    private static void traverseArgument(TExpr argument, Visitor<Candidate> candidates,
                                         Visitor<PostponedArgument> postponed, Map<Object, Boolean> visited) {
        switch (argument) {
            case TExpr.Unresolved u when u.state() instanceof Candidate c -> traverse(c, candidates, postponed, visited);
            case TExpr.Unresolved u when u.state() instanceof PostponedArgument p -> {
                if (visited.put(p, true) != null) return;
                postponed.visit(p);
                for (TExpr nested : p.nestedArguments()) traverseArgument(nested, candidates, postponed, visited);
            }
            case TExpr.Block b when TExprs.lastExpression(b) != null ->
                    traverseArgument(TExprs.lastExpression(b), candidates, postponed, visited);
            case TExpr.SafeCall s -> traverseArgument(s.selector(), candidates, postponed, visited);
            default -> {
            }
        }
    }
}
