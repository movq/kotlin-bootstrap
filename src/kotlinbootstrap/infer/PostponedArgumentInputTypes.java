package kotlinbootstrap.infer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.SubtypeChecker;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;

/**
 * Finds what the input types of postponed arguments depend on, as kotlinc's
 * PostponedArgumentInputTypesResolver: builds function types for arguments
 * expected to have the type of a variable, from the function types among
 * the variable's constraints and the parameters the arguments declare; and
 * finds the variables to fix before an argument can be analyzed.
 */
final class PostponedArgumentInputTypes {
    /** A step of the path to a type variable inside a type: the constructor and the argument's index. */
    record PathStep(Object constructor, int index) {
    }

    private record TypeWithKind(KType type, ConstraintKind direction) {
    }

    /**
     * @param parametersFromDeclaration   the argument's declared parameter types, null where not declared
     * @param parametersFromRelatedLambdas the declared parameter types of the lambdas with related expected types
     * @param parametersFromConstraints    the parameter types of the function types among the constraints
     */
    private record ParameterTypesInfo(List<KType> parametersFromDeclaration,
                                      Set<List<KType>> parametersFromRelatedLambdas,
                                      Set<List<TypeWithKind>> parametersFromConstraints,
                                      boolean isExtensionFunction, int contextParameterCount,
                                      FunctionTypes.Kind functionTypeKind, boolean isNullable) {
    }

    private record RelatedLambdasInfo(Set<List<KType>> parameterTypes, boolean isAnyFunctionExpressionWithReceiver,
                                      int contextParameterCountFromFunctionExpression, int maxParameterCount,
                                      boolean isAnyArgumentSuspend) {
    }

    private final ConstraintSystem c;
    private final VariableFixationFinder finder;

    PostponedArgumentInputTypes(ConstraintSystem c, VariableFixationFinder finder) {
        this.c = c;
        this.finder = finder;
    }

    // ==== Building function types ====

    /**
     * Revises the expected type of the first argument it can build a
     * function type for; returns whether it did.
     */
    boolean collectParameterTypesAndBuildNewExpectedTypes(List<PostponedAtomWithRevisableExpectedType> arguments,
                                                          CompletionMode mode, TypeVariableDependencies dependencies,
                                                          Set<TypeVariable> topLevelTypeVariables) {
        // Declared parameter types do not change during completion.
        for (PostponedAtomWithRevisableExpectedType argument : arguments) {
            if (argument instanceof LambdaWithTypeVariableAsExpectedType l && l.parameterTypesFromDeclaration() == null) {
                l.updateParameterTypesFromDeclaration(l.declaredParameterTypes());
            }
        }
        for (PostponedAtomWithRevisableExpectedType argument : arguments) {
            // Only anonymous functions in partial mode: a lambda's type may still change, e.g. become an extension.
            if (mode == CompletionMode.PARTIAL && !isFunctionExpression(argument)) continue;
            if (argument.revisedExpectedType() != null) continue;
            ParameterTypesInfo info = extractParameterTypesInfo(argument, arguments, dependencies);
            if (info == null) continue;
            KType newExpectedType = buildNewFunctionalExpectedType(argument, info, dependencies, topLevelTypeVariables);
            if (newExpectedType == null) continue;
            argument.reviseExpectedType(newExpectedType);
            return true;
        }
        return false;
    }

    private ParameterTypesInfo extractParameterTypesInfo(PostponedAtomWithRevisableExpectedType argument,
                                                         List<PostponedAtomWithRevisableExpectedType> arguments,
                                                         TypeVariableDependencies dependencies) {
        KType expectedType = argument.expectedType();
        if (expectedType == null) return null;
        TypeVariable variable = InferenceTypes.typeVariable(expectedType);
        if (variable == null || !c.notFixedTypeVariables().containsKey(variable)) return null;
        List<TypeWithKind> functionalTypes = functionalTypesInConstraints(variable, dependencies);

        // Different numbers of parameters are an error, reported elsewhere.
        if (functionalTypes.stream().map(t -> InferenceTypes.argumentsCount(t.type())).distinct().count() > 1) return null;

        List<KType> parametersFromDeclaration =
                argument instanceof LambdaWithTypeVariableAsExpectedType l ? l.parameterTypesFromDeclaration() : null;
        Set<List<TypeWithKind>> parametersFromConstraints = new LinkedHashSet<>();
        for (TypeWithKind t : functionalTypes) {
            List<TypeWithKind> parameters = new ArrayList<>();
            // Parameters are contravariant.
            for (KType p : argumentsForFunctionTypeOrSubtype(t.type())) parameters.add(new TypeWithKind(p, t.direction().opposite()));
            parametersFromConstraints.add(parameters);
        }
        boolean extensionInConstraints = functionalTypes.stream().anyMatch(t -> isExtensionFunctionType(t.type()));
        int contextParameterCountFromConstraints = functionalTypes.stream()
                .mapToInt(t -> Types.lowerBound(t.type()) instanceof KType.ClassType ct ? ct.contextParams() : 0)
                .max().orElse(0);

        RelatedLambdasInfo related = relatedLambdasInfo(argument, arguments, dependencies, extensionInConstraints,
                contextParameterCountFromConstraints, parametersFromConstraints, parametersFromDeclaration);

        FunctionTypes.Kind kind = null;
        boolean isNullable = false;
        if (!functionalTypes.isEmpty()) {
            isNullable = true;
            for (TypeWithKind t : functionalTypes) {
                if (kind == null) {
                    FunctionTypes.Kind k = FunctionTypes.kind(t.type());
                    if (k != FunctionTypes.Kind.FUNCTION && k != FunctionTypes.Kind.K_FUNCTION) kind = k;
                }
                if (isNullable && !Types.isMarkedNullable(t.type())) isNullable = false;
                if (kind != null && !isNullable) break;
            }
        }
        if (kind == null && related.isAnyArgumentSuspend()) kind = FunctionTypes.Kind.SUSPEND_FUNCTION;

        boolean isLambda = argument instanceof LambdaWithTypeVariableAsExpectedType l && l.isLambda();
        boolean isExtensionFunction = related.isAnyFunctionExpressionWithReceiver() || extensionInConstraints;
        int contextParameterCount = Math.max(contextParameterCountFromConstraints,
                related.contextParameterCountFromFunctionExpression());
        int extraParameterCount = (isExtensionFunction ? 1 : 0) + contextParameterCount;

        List<KType> declared = parametersFromDeclaration;
        if (declared != null && isLambda && extraParameterCount > 0
                && declared.size() + extraParameterCount == related.maxParameterCount()) {
            declared = withNullPrefix(extraParameterCount, declared);
        }
        return new ParameterTypesInfo(declared, related.parameterTypes(), parametersFromConstraints, isExtensionFunction,
                contextParameterCount, kind != null ? kind : FunctionTypes.Kind.FUNCTION, isNullable);
    }

    /** The function types (or their function supertypes) among the constraints of the variable and those it shallowly depends on. */
    private List<TypeWithKind> functionalTypesInConstraints(TypeVariable variable, TypeVariableDependencies dependencies) {
        Set<TypeVariable> dependent = new LinkedHashSet<>();
        Set<TypeVariable> shallow = dependencies.shallowlyDependentVariables(variable);
        if (shallow != null) dependent.addAll(shallow);
        dependent.add(variable);
        List<TypeWithKind> result = new ArrayList<>();
        for (TypeVariable v : dependent) {
            VariableWithConstraints withConstraints = c.notFixedTypeVariables().get(v);
            if (withConstraints == null) continue;
            for (Constraint constraint : withConstraints.constraints()) {
                KType functionType = FunctionTypes.functionTypeFromSupertypes(c.ts(), constraint.type());
                if (functionType != null) result.add(new TypeWithKind(functionType, constraint.kind()));
            }
        }
        return result;
    }

    /** What the lambdas expected to have a type related to the argument's declare, as kotlinc's computeParameterInfoFromRelatedLambdas. */
    private RelatedLambdasInfo relatedLambdasInfo(PostponedAtomWithRevisableExpectedType argument,
                                                  List<PostponedAtomWithRevisableExpectedType> arguments,
                                                  TypeVariableDependencies dependencies, boolean extensionInConstraints,
                                                  int contextParameterCount,
                                                  Set<List<TypeWithKind>> parametersFromConstraints,
                                                  List<KType> parametersFromDeclaration) {
        boolean anyWithReceiver = false;
        int contextParameterCountFromFunctionExpression = 0;
        boolean anySuspend = false;
        record Declared(List<KType> types, boolean isLambda) {
        }
        List<Declared> fromRelated = new ArrayList<>();
        TypeVariable variable = InferenceTypes.typeVariable(argument.expectedType());
        if (variable != null) {
            for (PostponedAtomWithRevisableExpectedType another : arguments) {
                if (another == argument || !(another instanceof LambdaWithTypeVariableAsExpectedType l)) continue;
                TypeVariable anotherVariable = InferenceTypes.typeVariable(another.expectedType());
                if (anotherVariable == null || !dependencies.areVariablesDependentShallowly(variable, anotherVariable)) {
                    continue;
                }
                anyWithReceiver = anyWithReceiver || l.isFunctionExpressionWithReceiver();
                if (l.contextParameterCountOfFunctionExpression() > 0) {
                    contextParameterCountFromFunctionExpression = l.contextParameterCountOfFunctionExpression();
                }
                anySuspend = anySuspend || l.isSuspend();
                if (l.parameterTypesFromDeclaration() != null) {
                    fromRelated.add(new Declared(l.parameterTypesFromDeclaration(), l.isLambda()));
                }
            }
        }
        int maxParameterCount = Math.max(Math.max(
                parametersFromConstraints.stream().mapToInt(List::size).max().orElse(0),
                fromRelated.stream().mapToInt(d -> d.types().size()).max().orElse(0)),
                parametersFromDeclaration != null ? parametersFromDeclaration.size() : 0);
        int extraParameterCount = (extensionInConstraints || anyWithReceiver ? 1 : 0)
                + Math.max(contextParameterCount, contextParameterCountFromFunctionExpression);
        Set<List<KType>> declaredParameterTypes = new LinkedHashSet<>();
        for (Declared d : fromRelated) {
            boolean withPrefix = d.isLambda() && extraParameterCount > 0
                    && d.types().size() + extraParameterCount == maxParameterCount;
            declaredParameterTypes.add(withPrefix ? withNullPrefix(extraParameterCount, d.types()) : d.types());
        }
        return new RelatedLambdasInfo(declaredParameterTypes, anyWithReceiver, contextParameterCountFromFunctionExpression,
                maxParameterCount, anySuspend);
    }

    private KType buildNewFunctionalExpectedType(PostponedAtomWithRevisableExpectedType argument, ParameterTypesInfo info,
                                                 TypeVariableDependencies dependencies,
                                                 Set<TypeVariable> topLevelTypeVariables) {
        KType expectedType = argument.expectedType();
        TypeVariable variable = expectedType == null ? null : InferenceTypes.typeVariable(expectedType);
        if (variable == null || !c.notFixedTypeVariables().containsKey(variable)) return null;

        TypeVariable relatedTopLevelVariable = firstRelatedVariable(topLevelTypeVariables, variable, dependencies);
        List<PathStep> path = null;
        if (relatedTopLevelVariable != null) {
            for (Constraint constraint : c.notFixedTypeVariables().get(relatedTopLevelVariable).constraints()) {
                if (InferenceTypes.contains(constraint.type(), t -> SubtypeChecker.constructor(t) == variable)) {
                    path = pathToVariable(constraint.type(), variable, new ArrayList<>());
                    break;
                }
            }
        }
        // The same function type for the same variable, e.g. in both branches of an if.
        KType cached = path != null ? c.builtFunctionType(relatedTopLevelVariable, path) : c.builtFunctionType(variable);
        if (cached != null) return cached;

        Set<List<TypeWithKind>> parametersFromConstraints = info.parametersFromConstraints();
        List<KType> parametersFromDeclaration = info.parametersFromDeclaration();
        boolean allParameterTypesSpecified = parametersFromDeclaration != null && !parametersFromDeclaration.isEmpty()
                && parametersFromDeclaration.stream().allMatch(t -> t != null);
        boolean isFunctionExpression = isFunctionExpression(argument);
        // Lambdas with all parameter types declared are analyzed without one.
        if (allParameterTypesSpecified && !info.isExtensionFunction() && !isFunctionExpression
                && info.contextParameterCount() == 0 && info.functionTypeKind() == FunctionTypes.Kind.FUNCTION) {
            return null;
        }

        Set<List<KType>> parametersFromDeclarations = new LinkedHashSet<>(info.parametersFromRelatedLambdas());
        parametersFromDeclarations.add(parametersFromDeclaration);
        List<List<TypeWithKind>> allParameterTypes = new ArrayList<>(parametersFromConstraints);
        for (List<KType> parameters : parametersFromDeclarations) {
            if (parameters == null) continue;
            List<TypeWithKind> wrapped = new ArrayList<>();
            for (KType t : parameters) wrapped.add(t == null ? null : new TypeWithKind(t, ConstraintKind.UPPER));
            allParameterTypes.add(wrapped);
        }
        if (allParameterTypes.isEmpty() && info.functionTypeKind() == FunctionTypes.Kind.FUNCTION) return null;

        List<KType> parameterTypes = createTypeVariablesForParameters(allParameterTypes);
        TypeVariable returnVariable = new TypeVariable("_R", null);
        c.registerVariable(returnVariable);

        boolean parameterCountsEqual = parametersFromDeclaration != null && !parametersFromDeclaration.isEmpty()
                && !parametersFromConstraints.isEmpty()
                && parametersFromDeclaration.size() == parametersFromConstraints.iterator().next().size();
        // `val x: String.() -> Int = id { x: String -> 42 }`: the receiver is a declared parameter.
        boolean discriminateExtension = info.isExtensionFunction() && allParameterTypesSpecified && parameterCountsEqual;
        boolean extension = discriminateExtension ? false
                : isFunctionExpressionWithReceiver(argument) || info.isExtensionFunction();
        List<TypeArg> args = new ArrayList<>();
        for (KType t : parameterTypes) args.add(TypeArg.of(t));
        args.add(TypeArg.of(returnVariable.type()));
        int contextParams = Math.max(info.contextParameterCount(), argument instanceof LambdaWithTypeVariableAsExpectedType l
                ? l.contextParameterCountOfFunctionExpression() : 0);
        KType newExpectedType = new KType.ClassType(
                FunctionTypes.functionClass(c.ts().table(), info.functionTypeKind(), parameterTypes.size()),
                args, info.isNullable(), extension, contextParams);

        c.addSubtypeConstraint(newExpectedType, expectedType, ConstraintPosition.of(ConstraintPosition.Kind.ARGUMENT));
        if (path != null) {
            c.putBuiltFunctionType(relatedTopLevelVariable, path, newExpectedType);
        } else {
            c.putBuiltFunctionType(variable, newExpectedType);
        }
        return newExpectedType;
    }

    /** For each parameter, a variable constrained by the types for it; its type if fixed meanwhile. */
    private List<KType> createTypeVariablesForParameters(List<List<TypeWithKind>> parameterTypes) {
        if (parameterTypes.isEmpty()) return List.of();
        ConstraintPosition position = ConstraintPosition.of(ConstraintPosition.Kind.ARGUMENT);
        List<KType> result = new ArrayList<>();
        int count = parameterTypes.getFirst().size();
        for (int i = 0; i < count; i++) {
            TypeVariable v = new TypeVariable("_RP" + i, null);
            c.registerVariable(v);
            for (List<TypeWithKind> types : parameterTypes) {
                if (c.fixedTypeVariables().containsKey(v)) break;
                TypeWithKind t = i < types.size() ? types.get(i) : null;
                if (t == null) continue;
                switch (t.direction()) {
                    case EQUALITY -> c.addEqualityConstraint(v.type(), t.type(), position);
                    case UPPER -> c.addSubtypeConstraint(v.type(), t.type(), position);
                    case LOWER -> c.addSubtypeConstraint(t.type(), v.type(), position);
                }
            }
            KType fixed = c.fixedTypeVariables().get(v);
            result.add(fixed != null ? fixed : v.type());
        }
        return result;
    }

    private TypeVariable firstRelatedVariable(Set<TypeVariable> variables, TypeVariable target,
                                              TypeVariableDependencies dependencies) {
        Set<TypeVariable> related = new LinkedHashSet<>();
        Set<TypeVariable> deep = dependencies.deeplyDependentVariables(target);
        if (deep != null) related.addAll(deep);
        Set<TypeVariable> shallow = dependencies.shallowlyDependentVariables(target);
        if (shallow != null) related.addAll(shallow);
        for (TypeVariable v : variables) {
            if (related.contains(v) && c.notFixedTypeVariables().containsKey(v)) return v;
        }
        return null;
    }

    /** The path to the variable through the type's arguments, or null if not in it. */
    private static List<PathStep> pathToVariable(KType type, TypeVariable target, List<PathStep> path) {
        Object constructor = SubtypeChecker.constructor(type);
        if (constructor == target) return List.of();
        if (!(Types.lowerBound(type) instanceof KType.ClassType ct)) return null;
        for (int i = 0; i < ct.args().size(); i++) {
            KType argument = ct.args().get(i).type();
            if (argument == null) continue;
            if (SubtypeChecker.constructor(argument) == target) {
                List<PathStep> result = new ArrayList<>(path);
                result.add(new PathStep(constructor, i));
                return result;
            }
            if (InferenceTypes.argumentsCount(argument) != 0) {
                path.add(new PathStep(constructor, i));
                List<PathStep> found = pathToVariable(argument, target, path);
                if (found != null) return found;
                path.removeLast();
            }
        }
        return null;
    }

    // ==== Variables for input types ====

    /**
     * The next variable to fix among those the argument's input types
     * depend on, if the argument is expected to have a function type.
     */
    VariableFixationFinder.VariableForFixation findNextReadyVariableForParameterType(
            PostponedAtom argument, List<PostponedAtom> postponedArguments, KType topLevelType, CompletionMode mode,
            TypeVariableDependencies dependencies) {
        KType revised = argument instanceof PostponedAtomWithRevisableExpectedType r ? r.revisedExpectedType() : null;
        KType expectedType = revised != null ? revised : argument.expectedType();
        if (expectedType == null || !FunctionTypes.isFunctionType(expectedType)) return null;
        // With the lexicographic readiness calculation, as of language version 2.4.
        if (argument instanceof LambdaWithTypeVariableAsExpectedType) return null;
        List<TypeVariable> related = new ArrayList<>();
        for (KType t : argument.inputTypes()) collectDeeplyRelatedTypeVariables(t, dependencies, related);
        Set<TypeVariable> outer = c.outerTypeVariables();
        if (outer != null) related.removeIf(outer::contains);
        return finder.findFirstVariableForFixation(related, postponedArguments, mode, topLevelType);
    }

    private void collectDeeplyRelatedTypeVariables(KType type, TypeVariableDependencies dependencies,
                                                   List<TypeVariable> result) {
        if (SubtypeChecker.constructor(type) instanceof TypeVariable v) {
            result.add(v);
            Set<TypeVariable> deep = dependencies.deeplyDependentVariables(v);
            if (deep != null) result.addAll(deep);
        } else if (Types.lowerBound(type) instanceof KType.ClassType ct) {
            for (TypeArg a : ct.args()) {
                if (!a.isStar()) collectDeeplyRelatedTypeVariables(a.type(), dependencies, result);
            }
        }
    }

    // ==== Helpers ====

    /** The parameter types of a function type or subtype, with Any? for stars. */
    private List<KType> argumentsForFunctionTypeOrSubtype(KType type) {
        KType functionType = FunctionTypes.functionTypeFromSupertypes(c.ts(), type);
        List<KType> result = new ArrayList<>();
        if (!(Types.lowerBound(functionType) instanceof KType.ClassType ct)) return result;
        for (TypeArg a : ct.args().subList(0, ct.args().size() - 1)) {
            result.add(a.isStar() ? c.ts().nullableAny() : a.type());
        }
        return result;
    }

    private static boolean isExtensionFunctionType(KType t) {
        return Types.lowerBound(t) instanceof KType.ClassType ct && ct.extensionFunction();
    }

    private static boolean isFunctionExpression(PostponedAtomWithRevisableExpectedType argument) {
        return argument instanceof LambdaWithTypeVariableAsExpectedType l && l.isFunctionExpression();
    }

    private static boolean isFunctionExpressionWithReceiver(PostponedAtomWithRevisableExpectedType argument) {
        return argument instanceof LambdaWithTypeVariableAsExpectedType l && l.isFunctionExpressionWithReceiver();
    }

    private static List<KType> withNullPrefix(int count, List<KType> types) {
        List<KType> result = new ArrayList<>(Collections.nCopies(count, null));
        result.addAll(types);
        return result;
    }
}
