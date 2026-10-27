package kotlinbootstrap.infer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeVariable;

/**
 * Completes a call's constraint system, as FIR's ConstraintSystemCompleter:
 * analyzes postponed arguments once their input types are known and fixes
 * the variables one by one, in the order {@link VariableFixationFinder}
 * chooses, until nothing is left to do.
 */
public final class ConstraintSystemCompleter {
    /** The calls being completed. */
    public interface Context {
        /** The variables of the calls and their arguments, in order of appearance. */
        List<TypeVariable> orderedTypeVariables();

        /** The postponed arguments of the calls, in order of appearance. */
        List<? extends PostponedAtom> postponedArguments();

        /** Analyzes the argument, adding constraints for its output type to the system. */
        void analyze(PostponedAtom argument);

        /**
         * Replaces a lambda expected to have the type of a variable by one
         * expected to have the function type built for it.
         */
        void transformToResolvedLambda(LambdaWithTypeVariableAsExpectedType argument, KType expectedType);
    }

    private ConstraintSystemCompleter() {
    }

    /**
     * @param topLevelType the return type of the outermost call; in partial
     *                     mode, the variables related to it are not fixed
     */
    public static void complete(ConstraintSystem c, CompletionMode mode, KType topLevelType, Context context) {
        VariableFixationFinder finder = new VariableFixationFinder(c);
        PostponedArgumentInputTypes inputTypes = new PostponedArgumentInputTypes(c, finder);
        Set<TypeVariable> topLevelTypeVariables = new LinkedHashSet<>();
        if (topLevelType != null) {
            InferenceTypes.contains(topLevelType, t -> {
                TypeVariable v = InferenceTypes.typeVariable(t);
                if (v != null) topLevelTypeVariables.add(v);
                return false;
            });
        }
        completion:
        while (true) {
            if (mode.shouldForkPointConstraintsBeResolved) c.resolveForkPointsConstraints();

            List<PostponedAtom> postponedArguments = notAnalyzed(context.postponedArguments());

            // Stage 1: analyze postponed arguments with fixed input types.
            PostponedAtom withFixedInputTypes = postponedArguments.stream()
                    .filter(a -> a.inputTypes().stream().allMatch(c::containsOnlyFixedVariables))
                    .findFirst().orElse(null);
            if (withFixedInputTypes != null) {
                context.analyze(withFixedInputTypes);
                continue;
            }

            VariableFixationFinder.VariableForFixation variableForFixation =
                    finder.findFirstVariableForFixation(orderedNotFixed(c, context), postponedArguments, mode, topLevelType);

            // Nothing to do.
            if (postponedArguments.isEmpty() && variableForFixation == null) break;

            List<PostponedAtomWithRevisableExpectedType> withRevisableType = new ArrayList<>();
            for (PostponedAtom a : postponedArguments) {
                if (a instanceof PostponedAtomWithRevisableExpectedType r) withRevisableType.add(r);
            }
            TypeVariableDependencies dependencies =
                    new TypeVariableDependencies(c.notFixedTypeVariables(), postponedArguments, topLevelType);

            // Stage 2: build function types for arguments expected to have the type of a variable.
            if (inputTypes.collectParameterTypesAndBuildNewExpectedTypes(withRevisableType, mode, dependencies,
                    topLevelTypeVariables)) {
                continue;
            }

            if (mode.allLambdasShouldBeAnalyzed()) {
                // Stage 3: fix variables for the parameter types of postponed arguments.
                for (PostponedAtom argument : postponedArguments) {
                    VariableFixationFinder.VariableForFixation next = inputTypes.findNextReadyVariableForParameterType(
                            argument, postponedArguments, topLevelType, CompletionMode.FULL, dependencies);
                    if (next != null && fixVariableIfReady(c, next)) continue completion;
                }

                // Stage 4: lambdas with function types built for them become lambdas expected to have those.
                for (PostponedAtomWithRevisableExpectedType argument : withRevisableType) {
                    KType revised = argument.revisedExpectedType();
                    if (revised == null || !FunctionTypes.isFunctionType(revised)) continue;
                    if (argument instanceof LambdaWithTypeVariableAsExpectedType lambda) {
                        context.transformToResolvedLambda(lambda, revised);
                        continue completion;
                    }
                }

                // Stage 5: analyze the next argument with a revisable expected type.
                if (!withRevisableType.isEmpty()) {
                    context.analyze(withRevisableType.getFirst());
                    continue;
                }
            }

            // Stage 6: fix the next ready variable.
            if (variableForFixation != null && fixVariableIfReady(c, variableForFixation)) continue;

            // Stage 7, builder inference, is not supported yet.

            // Stage 9: "not enough information" for the variables without proper constraints.
            if (mode.fixNotInferredTypeVariablesToErrorType) {
                reportNotEnoughTypeInformation(c, finder, context, postponedArguments, mode, topLevelType);
            }

            // Stage 10: analyze the remaining postponed arguments, even without their input types.
            if (mode.allLambdasShouldBeAnalyzed() && !postponedArguments.isEmpty()) {
                context.analyze(postponedArguments.getFirst());
                continue;
            }

            break;
        }
    }

    private static List<PostponedAtom> notAnalyzed(List<? extends PostponedAtom> arguments) {
        List<PostponedAtom> result = new ArrayList<>();
        for (PostponedAtom a : arguments) {
            if (!a.analyzed()) result.add(a);
        }
        return result;
    }

    private static List<TypeVariable> orderedNotFixed(ConstraintSystem c, Context context) {
        List<TypeVariable> result = new ArrayList<>();
        for (TypeVariable v : context.orderedTypeVariables()) {
            if (c.notFixedTypeVariables().containsKey(v) && !result.contains(v)) result.add(v);
        }
        return result;
    }

    private static void reportNotEnoughTypeInformation(ConstraintSystem c, VariableFixationFinder finder, Context context,
                                                       List<PostponedAtom> postponedArguments, CompletionMode mode,
                                                       KType topLevelType) {
        while (true) {
            VariableFixationFinder.VariableForFixation variableForFixation =
                    finder.findFirstVariableForFixation(orderedNotFixed(c, context), postponedArguments, mode, topLevelType);
            if (variableForFixation == null) break;
            assert !variableForFixation.isReady() : "No variables with proper constraints should remain";
            TypeVariable variable = variableForFixation.variable();
            c.addError(new ConstraintError(ConstraintError.Kind.NOT_ENOUGH_INFORMATION, variable, null, null, null));
            // kotlinc fixes to an error type; the error makes the result unused anyway.
            c.fixVariable(variable, c.ts().nullableAny(), new ConstraintPosition(ConstraintPosition.Kind.FIX_VARIABLE, variable));
        }
    }

    private static boolean fixVariableIfReady(ConstraintSystem c, VariableFixationFinder.VariableForFixation v) {
        if (!v.isReady()) return false;
        fixVariable(c, c.notFixedTypeVariables().get(v.variable()));
        return true;
    }

    private static void fixVariable(ConstraintSystem c, VariableWithConstraints v) {
        KType resultType = c.resultTypeResolver.findResultType(c, v, ResultTypeResolver.Direction.UNKNOWN);
        c.fixVariable(v.variable(), resultType, new ConstraintPosition(ConstraintPosition.Kind.FIX_VARIABLE, v.variable()));
    }
}
