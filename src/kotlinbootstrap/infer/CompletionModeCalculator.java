package kotlinbootstrap.infer;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.SubtypeChecker;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;
import kotlinbootstrap.types.Variance;

/**
 * Chooses how to complete a call that is an argument of another, as FIR's
 * CompletionModeCalculator: fully if what the outer call could add cannot
 * change the result. For each variable in the return type, the outer call
 * can only add upper constraints where it occurs covariantly; so a proper
 * lower constraint there (or an equality constraint anywhere) settles it.
 */
public final class CompletionModeCalculator {
    private enum Direction { TO_SUBTYPE, EQUALITY }

    private final ConstraintSystem c;
    private final List<? extends PostponedAtom> postponedArguments;
    private final Map<VariableWithConstraints, Direction> directions = new LinkedHashMap<>();
    private final Set<TypeVariable> variablesWithQueuedConstraints = new HashSet<>();
    private final Queue<KType> typesToProcess = new ArrayDeque<>();

    private CompletionModeCalculator(ConstraintSystem c, List<? extends PostponedAtom> postponedArguments) {
        this.c = c;
        this.postponedArguments = postponedArguments;
    }

    /**
     * The mode for a call in a context that does not force full completion.
     *
     * @param postponedArguments the call's postponed arguments not analyzed yet
     */
    public static CompletionMode compute(ConstraintSystem c, KType returnType,
                                         List<? extends PostponedAtom> postponedArguments) {
        if (c.isProperType(returnType)) return CompletionMode.FULL;
        CompletionModeCalculator calculator = new CompletionModeCalculator(c, postponedArguments);
        calculator.typesToProcess.add(returnType);
        calculator.computeDirections();
        return calculator.directionRequirementsHold() ? CompletionMode.FULL : CompletionMode.PARTIAL;
    }

    private void computeDirections() {
        while (!typesToProcess.isEmpty()) {
            KType type = typesToProcess.poll();
            if (!InferenceTypes.contains(type, t -> t instanceof KType.TypeVariableType v
                    && c.notFixedTypeVariables().containsKey(v.variable()))) {
                continue;
            }
            Map<VariableWithConstraints, Direction> fromType = new LinkedHashMap<>();
            collectRequiredDirections(type, Variance.OUT, fromType);
            fromType.forEach((v, direction) -> {
                updateDirection(v, direction);
                enqueueTypesFromConstraints(v);
            });
        }
    }

    private void enqueueTypesFromConstraints(VariableWithConstraints v) {
        if (variablesWithQueuedConstraints.add(v.variable())) {
            for (Constraint constraint : v.constraints()) typesToProcess.add(constraint.type());
        }
    }

    private boolean directionRequirementsHold() {
        for (var e : directions.entrySet()) {
            if (!hasProperConstraint(e.getKey(), e.getValue())) return false;
        }
        return true;
    }

    private void updateDirection(VariableWithConstraints v, Direction direction) {
        Direction old = directions.get(v);
        if (old == null) {
            directions.put(v, direction);
        } else if (old != Direction.EQUALITY && old != direction) {
            directions.put(v, Direction.EQUALITY);
        }
    }

    // The set in kotlinc may hold both directions for a variable; they are merged in the same order.
    private void collectRequiredDirections(KType type, Variance outer, Map<VariableWithConstraints, Direction> collector) {
        KType lower = Types.lowerBound(type);
        if (lower instanceof KType.ClassType ct && !ct.args().isEmpty()) {
            List<TypeParamSymbol> params = Overrides.allTypeParams(ct.symbol());
            if (params.size() == ct.args().size()) {
                for (int i = 0; i < ct.args().size(); i++) {
                    TypeArg a = ct.args().get(i);
                    if (a.isStar()) continue;
                    collectRequiredDirections(a.type(), compositeVariance(outer, a, params.get(i)), collector);
                }
                return;
            }
        }
        processTypeWithoutParameters(type, outer, collector);
    }

    private static Variance compositeVariance(Variance outer, TypeArg argument, TypeParamSymbol param) {
        Variance effective = SubtypeChecker.effectiveVariance(param.variance(), argument.variance());
        if (effective == null) effective = Variance.INVARIANT;
        return switch (outer) {
            case INVARIANT -> Variance.INVARIANT;
            case OUT -> effective;
            case IN -> switch (effective) {
                case IN -> Variance.OUT;
                case OUT -> Variance.IN;
                case INVARIANT -> Variance.INVARIANT;
            };
        };
    }

    private void processTypeWithoutParameters(KType type, Variance variance,
                                              Map<VariableWithConstraints, Direction> collector) {
        TypeVariable variable = InferenceTypes.typeVariable(type);
        VariableWithConstraints v = variable == null ? null : c.notFixedTypeVariables().get(variable);
        if (v == null) return;
        // Variables in contravariant positions are assumed to be fixed to a subtype.
        Direction direction = variance == Variance.OUT ? Direction.TO_SUBTYPE : Direction.EQUALITY;
        Direction old = collector.get(v);
        collector.put(v, old != null && old != direction ? Direction.EQUALITY : direction);
    }

    private boolean hasProperConstraint(VariableWithConstraints v, Direction direction) {
        // Tracking integer literal constraints prevents completing from a Nothing constraint: for
        // ILT <: T and Nothing <: T, the result would be Int, ignoring what outer calls require.
        boolean iltConstraintPresent = false;
        boolean properConstraintPresent = false;
        boolean nonNothingProperConstraintPresent = false;
        for (Constraint constraint : v.constraints()) {
            boolean hasKind = direction == Direction.TO_SUBTYPE
                    ? constraint.kind() != ConstraintKind.UPPER : constraint.kind() == ConstraintKind.EQUALITY;
            if (!hasKind || !c.isProperType(constraint.type())) continue;
            if (InferenceTypes.isIntegerLiteral(constraint.type())) {
                iltConstraintPresent = true;
            } else if (!InferenceTypes.isNothingConstructor(c.ts(), constraint.type())) {
                properConstraintPresent = true;
                nonNothingProperConstraintPresent = true;
            } else if (!isLowerConstraintForPartiallyAnalyzedVariable(constraint, v.variable())) {
                properConstraintPresent = true;
            }
        }
        if (!properConstraintPresent) return false;
        return !iltConstraintPresent || nonNothingProperConstraintPresent;
    }

    private boolean isLowerConstraintForPartiallyAnalyzedVariable(Constraint constraint, TypeVariable variable) {
        if (constraint.kind() != ConstraintKind.LOWER) return false;
        KType defaultType = variable.type();
        for (PostponedAtom a : postponedArguments) {
            if (a.expectedType() != null && InferenceTypes.contains(a.expectedType(), t -> t.equals(defaultType))) {
                return true;
            }
        }
        return false;
    }
}
