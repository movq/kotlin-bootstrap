package kotlinbootstrap.infer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeVariable;

/**
 * How the not fixed variables of a system relate through their constraints
 * and postponed arguments, as kotlinc's
 * TypeVariableDependencyInformationProvider.
 */
final class TypeVariableDependencies {
    private final Map<TypeVariable, VariableWithConstraints> notFixed;

    /** Not oriented: A has {@code UPPER(Function1<B, R>)}, so A and B are related deeply. */
    private final Map<TypeVariable, Set<TypeVariable>> deepDependencies = new HashMap<>();
    /** Not oriented: A has {@code UPPER(B)}, so A and B are related shallowly. */
    private final Map<TypeVariable, Set<TypeVariable>> shallowDependencies = new HashMap<>();
    /** Oriented: from the variables of a postponed argument's input types to those of its output type. */
    private final Map<TypeVariable, Set<TypeVariable>> postponedArgumentEdges = new HashMap<>();

    private final Set<TypeVariable> relatedToAllOutputTypes = new HashSet<>();
    private final Set<TypeVariable> relatedToTopLevelType = new HashSet<>();

    TypeVariableDependencies(Map<TypeVariable, VariableWithConstraints> notFixed,
                             List<? extends PostponedAtom> postponedArguments, KType topLevelType) {
        this.notFixed = notFixed;
        computeConstraintEdges();
        computePostponedArgumentEdges(postponedArguments);
        computeRelatedToAllOutputTypes(postponedArguments);
        if (topLevelType != null) {
            forAllMyTypeVariables(topLevelType, v -> addAllRelatedNodes(relatedToTopLevelType, v, true));
        }
    }

    boolean isVariableRelatedToTopLevelType(TypeVariable variable) {
        return relatedToTopLevelType.contains(variable);
    }

    boolean isVariableRelatedToAnyOutputType(TypeVariable variable) {
        return relatedToAllOutputTypes.contains(variable);
    }

    Set<TypeVariable> deeplyDependentVariables(TypeVariable variable) {
        return deepDependencies.get(variable);
    }

    Set<TypeVariable> shallowlyDependentVariables(TypeVariable variable) {
        return shallowDependencies.get(variable);
    }

    boolean areVariablesDependentShallowly(TypeVariable a, TypeVariable b) {
        if (a == b) return true;
        Set<TypeVariable> dependencies = shallowDependencies.get(a);
        if (dependencies == null) return false;
        return dependencies.contains(b)
                || shallowDependencies.values().stream().anyMatch(d -> d.contains(a) && d.contains(b));
    }

    private void computeConstraintEdges() {
        for (VariableWithConstraints v : notFixed.values()) {
            TypeVariable from = v.variable();
            for (Constraint constraint : v.constraints()) {
                forAllMyTypeVariables(constraint.type(), to -> {
                    deepDependencies.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
                    deepDependencies.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);
                });
                TypeVariable to = InferenceTypes.typeVariable(constraint.type());
                if (notFixed.containsKey(to)) {
                    shallowDependencies.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
                    shallowDependencies.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);
                }
            }
        }
    }

    private void computePostponedArgumentEdges(List<? extends PostponedAtom> postponedArguments) {
        for (PostponedAtom argument : postponedArguments) {
            if (argument.analyzed() || argument.outputType() == null) continue;
            Set<TypeVariable> inOutputType = new LinkedHashSet<>();
            forAllMyTypeVariables(argument.outputType(), inOutputType::add);
            if (inOutputType.isEmpty()) continue;
            for (KType inputType : argument.inputTypes()) {
                forAllMyTypeVariables(inputType,
                        from -> postponedArgumentEdges.computeIfAbsent(from, k -> new HashSet<>()).addAll(inOutputType));
            }
        }
    }

    private void computeRelatedToAllOutputTypes(List<? extends PostponedAtom> postponedArguments) {
        for (PostponedAtom argument : postponedArguments) {
            if (argument.analyzed() || argument.outputType() == null) continue;
            forAllMyTypeVariables(argument.outputType(), v -> addAllRelatedNodes(relatedToAllOutputTypes, v, false));
        }
    }

    private void forAllMyTypeVariables(KType type, Consumer<TypeVariable> action) {
        InferenceTypes.contains(type, t -> {
            TypeVariable v = InferenceTypes.typeVariable(t);
            if (notFixed.containsKey(v)) action.accept(v);
            return false;
        });
    }

    private void addAllRelatedNodes(Set<TypeVariable> to, TypeVariable node, boolean includePostponedEdges) {
        if (!to.add(node)) return;
        for (TypeVariable related : deepDependencies.getOrDefault(node, Set.of())) {
            addAllRelatedNodes(to, related, includePostponedEdges);
        }
        if (includePostponedEdges) {
            for (TypeVariable related : postponedArgumentEdges.getOrDefault(node, Set.of())) {
                addAllRelatedNodes(to, related, true);
            }
        }
    }
}
