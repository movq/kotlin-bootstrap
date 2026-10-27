package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.infer.CompletionMode;
import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeVariable;

/**
 * Chooses between candidates annotated {@code @OverloadResolutionByLambdaReturnType}
 * by the type their lambda argument returns, as FIR's
 * FirOverloadByLambdaReturnTypeResolver: the lambda is analyzed once, and
 * what it returns is checked against each candidate's lambda return type.
 */
final class OverloadByLambdaReturnType {
    private final OverloadResolver overloads;
    private final CallCompleter completer;
    private final BodyChecker checker;

    OverloadByLambdaReturnType(OverloadResolver overloads, CallCompleter completer, BodyChecker checker) {
        this.overloads = overloads;
        this.completer = completer;
        this.checker = checker;
    }

    /** The candidates left of the most specific ones, if more than one. */
    Set<Candidate> reduceCandidates(Set<Candidate> candidates) {
        if (candidates.size() <= 1) return candidates;
        Set<Candidate> withAnnotation = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            if (Annotations.has(c.symbol, "OverloadResolutionByLambdaReturnType")) withAnnotation.add(c);
        }
        if (withAnnotation.isEmpty()) return candidates;
        Set<Candidate> newCandidates = analyzeLambdaAndReduceCandidates(candidates);
        if (newCandidates == null) return candidates;
        Set<Candidate> result = overloads.chooseMaximallySpecific(newCandidates);
        if (result.size() > 1 && result.stream().anyMatch(c -> !withAnnotation.contains(c))) {
            result = new LinkedHashSet<>(result);
            result.removeAll(withAnnotation);
        }
        return result;
    }

    private Set<Candidate> analyzeLambdaAndReduceCandidates(Set<Candidate> candidates) {
        if (candidates.stream().anyMatch(c -> !c.isSuccessful())) return candidates;
        // The one lambda not analyzed yet, and its atom in each candidate.
        Map<Candidate, LambdaAtom> lambdas = new LinkedHashMap<>();
        LambdaExpression expression = null;
        for (Candidate c : candidates) {
            for (PostponedArgument p : c.postponedArguments) {
                if (!(p instanceof LambdaAtom atom) || atom.analyzed()) continue;
                if (expression != null && atom.expression != expression) return null;
                expression = atom.expression;
                lambdas.put(c, atom);
            }
        }
        if (lambdas.isEmpty()) return null;
        if (lambdas.values().stream().map(a -> a.parameterTypes.size()).distinct().count() > 1) return null;
        if (!lambdas.values().stream().allMatch(a -> a.expectedType != null && FunctionTypes.isFunctionType(a.expectedType))) {
            return null;
        }

        List<List<KType>> inputTypes = new ArrayList<>();
        lambdas.forEach((candidate, atom) -> {
            activate(candidate);
            completer.runCompletion(candidate, CompletionMode.UNTIL_FIRST_LAMBDA, candidate.returnType());
            Substitution substitution = candidate.system.buildCurrentSubstitutor();
            inputTypes.add(atom.inputTypes().stream().map(substitution::apply).toList());
        });
        if (inputTypes.stream().distinct().count() > 1) return null;
        lambdas.forEach(OverloadByLambdaReturnType::prepareLambdaAtomForFactoryPattern);

        BodyChecker.LambdaResults results = null;
        for (Map.Entry<Candidate, LambdaAtom> e : lambdas.entrySet()) {
            Candidate candidate = e.getKey();
            activate(candidate);
            if (results == null) results = checker.analyzeLambdaBody(candidate.system, e.getValue());
            checker.applyLambdaResults(candidate.system, e.getValue(), candidate, results);
        }

        Set<Candidate> successful = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            if (c.isSuccessful()) successful.add(c);
        }
        return successful.isEmpty() ? candidates : successful;
    }

    private static void activate(Candidate candidate) {
        for (PostponedArgument p : candidate.postponedArguments) p.activate();
    }

    /**
     * Makes the lambda's return type a new variable, a subtype of the one
     * expected, so that what it returns is checked against the expected one.
     */
    private static void prepareLambdaAtomForFactoryPattern(Candidate candidate, LambdaAtom atom) {
        ConstraintSystem system = candidate.system;
        TypeVariable returnVariable = new TypeVariable("_R", null);
        system.registerVariable(returnVariable);
        KType.ClassType functionType = (KType.ClassType) system.buildCurrentSubstitutor().apply(atom.expectedType);
        List<TypeArg> args = new ArrayList<>(functionType.args());
        args.set(args.size() - 1, TypeArg.of(returnVariable.type()));
        KType expected = new KType.ClassType(functionType.symbol(), args, functionType.nullable(),
                functionType.extensionFunction(), functionType.contextParams());
        system.addSubtypeConstraint(expected, functionType, ConstraintPosition.of(ConstraintPosition.Kind.ARGUMENT));
        atom.replaceExpectedType(expected, returnVariable);
    }
}
