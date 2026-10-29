package kotlinbootstrap.dfa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;

/**
 * The operations on flows, as FIR's LogicSystem: joining the flows of
 * branches, adding statements and implications, and deriving the type
 * statements an operation statement implies.
 */
public final class LogicSystem {
    private final TypeSystem ts;
    private final VariableStorage variableStorage;

    public LogicSystem(TypeSystem ts, VariableStorage variableStorage) {
        this.ts = ts;
        this.variableStorage = variableStorage;
    }

    /** As FIR's isAcceptableForSmartcast, taking all classes as visible. */
    private boolean isAcceptableForSmartcast(KType t) {
        if (isNullableNothing(t)) return false;
        return switch (t) {
            case KType.ClassType c -> true;
            case KType.TypeParamType p -> p.symbol().bounds().stream().allMatch(this::isAcceptableForSmartcast);
            case KType.Flexible f -> isAcceptableForSmartcast(f.lower()) && isAcceptableForSmartcast(f.upper());
            case KType.Intersection i -> i.components().stream().allMatch(this::isAcceptableForSmartcast);
            case KType.DefinitelyNotNull d -> isAcceptableForSmartcast(d.inner());
            case KType.Captured c -> c.constructor().supertypes() != null
                    && c.constructor().supertypes().stream().allMatch(this::isAcceptableForSmartcast);
            case KType.IntegerLiteral l -> true;
            case KType.TypeVariableType v -> false;
        };
    }

    private boolean isNullableNothing(KType t) {
        return t instanceof KType.ClassType c && c.nullable() && c.symbol() == ts.nothingClass();
    }

    // ==== Joining flows ====

    /**
     * The flow where several flows meet.
     *
     * @param flows          all the flows that meet; they determine assignments and aliases
     * @param statementFlows those of them whose statements and implications count
     * @param union          whether all the flows happened (in an unknown order), rather than one of them
     */
    public Flow joinFlow(Collection<Flow> flows, Collection<Flow> statementFlows, boolean union) {
        if (flows.isEmpty()) return new Flow();
        if (flows.size() == 1) return flows.iterator().next().fork();
        Flow commonFlow = null;
        for (Flow f : flows) {
            commonFlow = commonFlow == null ? f : commonFlow.lowestCommonAncestor(f);
            if (commonFlow == null) throw new IllegalStateException("No common ancestor of flows");
        }
        Flow result = commonFlow.fork();
        mergeAssignments(result, flows);
        if (union) {
            copyNonConflictingAliases(result, flows, commonFlow);
        } else {
            copyCommonAliases(result, flows);
        }
        copyStatements(result, statementFlows, commonFlow, union);
        copyImplications(result, statementFlows);
        return result;
    }

    private void mergeAssignments(Flow result, Collection<Flow> flows) {
        // If a variable was reassigned in one branch, it was reassigned at the join point.
        Map<RealVariable, Integer> reassigned = new LinkedHashMap<>();
        for (Flow flow : flows) {
            for (var e : flow.assignmentIndex.entrySet()) {
                if (!Objects.equals(result.assignmentIndex.get(e.getKey()), e.getValue())) {
                    reassigned.merge(e.getKey(), e.getValue(), Math::max);
                }
            }
        }
        reassigned.forEach((variable, index) -> recordNewAssignment(result, variable, index));
    }

    private void copyCommonAliases(Flow result, Collection<Flow> flows) {
        for (var e : flows.iterator().next().directAliasMap.entrySet()) {
            RealVariable from = e.getKey();
            RealVariable to = e.getValue();
            // if (p) { y = x } else { y = x }: y -> x in all the flows, but not in their common ancestor.
            if (!to.equals(result.directAliasMap.get(from))
                    && flows.stream().allMatch(f -> f.unwrapVariable(from).equals(to))) {
                addLocalVariableAlias(result, from, to);
            }
        }
    }

    private void copyNonConflictingAliases(Flow result, Collection<Flow> flows, Flow commonFlow) {
        Map<RealVariable, RealVariable> candidates = new LinkedHashMap<>();
        for (Flow flow : flows) {
            for (var e : flow.directAliasMap.entrySet()) {
                RealVariable from = e.getKey();
                // f({ a = b }, { notReassigning() }) -> a = b
                if (Objects.equals(commonFlow.assignmentIndex.get(from), flow.assignmentIndex.get(from))) continue;
                // f({ a = b }, { a = c }) -> a = b or c, which can't be said; f({ a = b }, { a = b }) -> a = b
                if (candidates.containsKey(from) && !Objects.equals(candidates.get(from), e.getValue())) {
                    candidates.put(from, null);
                } else {
                    candidates.put(from, e.getValue());
                }
            }
        }
        candidates.forEach((from, to) -> {
            if (to != null) addLocalVariableAlias(result, from, to);
        });
    }

    private void copyStatements(Flow result, Collection<Flow> flows, Flow commonFlow, boolean union) {
        Set<DataFlowVariable> variables = new LinkedHashSet<>();
        for (Flow f : flows) variables.addAll(f.knownVariables());
        for (DataFlowVariable variable : variables) {
            // Statements about an alias are statements about the aliased variable.
            if (variable instanceof RealVariable r && result.directAliasMap.containsKey(r)) continue;
            TypeStatement statement;
            if (!union) {
                // if (c) { /* x: S1 */ } else { /* x: S2 */ } -> x: S1 | S2
                List<TypeStatement> statements = statementsInAll(flows, variable);
                if (statements == null) continue;
                statement = or(new LinkedHashSet<>(statements));
            } else if (Objects.equals(index(result, variable), index(commonFlow, variable))) {
                // callAllInSomeOrder({ /* x: S1 */ }, { /* x: S2 */ }) -> x: S1 & S2
                List<TypeStatement> statements = new ArrayList<>();
                for (Flow f : flows) {
                    TypeStatement s = f.getTypeStatement(variable);
                    if (s != null) statements.add(s);
                }
                statement = and(statements);
            } else {
                // callAllInSomeOrder({ x = ...; /* x: S1 */ }, { x = ...; /* x: S2 */ }, { /* x: S3 */ }) -> x: S1 | S2;
                // S3 is unordered with respect to the assignments.
                Map<Integer, List<TypeStatement>> byAssignment = new LinkedHashMap<>();
                for (Flow f : flows) {
                    Integer index = index(f, variable);
                    byAssignment.computeIfAbsent(index == null ? -1 : index, k -> new ArrayList<>())
                            .add(f.getTypeStatement(variable));
                }
                Integer commonIndex = index(commonFlow, variable);
                byAssignment.remove(commonIndex == null ? -1 : commonIndex);
                Set<TypeStatement> alternatives = new LinkedHashSet<>();
                boolean unknown = false;
                for (List<TypeStatement> group : byAssignment.values()) {
                    TypeStatement s = and(group.stream().filter(Objects::nonNull).toList());
                    if (s == null) {
                        unknown = true;
                        break;
                    }
                    alternatives.add(s);
                }
                if (unknown) continue;
                statement = or(alternatives);
            }
            if (statement != null && !statement.isEmpty()) {
                result.approvedTypeStatements.put(variable, statement.withVariable(variable));
            }
        }
    }

    private static List<TypeStatement> statementsInAll(Collection<Flow> flows, DataFlowVariable variable) {
        List<TypeStatement> result = new ArrayList<>();
        for (Flow f : flows) {
            TypeStatement s = f.getTypeStatement(variable);
            if (s == null) return null;
            result.add(s);
        }
        return result;
    }

    private static Integer index(Flow flow, DataFlowVariable variable) {
        return variable instanceof RealVariable r ? flow.assignmentIndex.get(r) : null;
    }

    private static void copyImplications(Flow result, Collection<Flow> flows) {
        // kotlinc computes no common implications of several flows (KT-65293).
        if (flows.size() == 1) result.implications.putAll(flows.iterator().next().implications);
    }

    // ==== Adding statements ====

    public void addLocalVariableAlias(Flow flow, RealVariable alias, RealVariable underlying) {
        flow.checkMutable();
        if (underlying.equals(alias)) return; // x = x
        flow.directAliasMap.put(alias, underlying);
        Set<RealVariable> siblings = new LinkedHashSet<>(flow.backwardsAliasMap.getOrDefault(underlying, Set.of()));
        siblings.add(alias);
        flow.backwardsAliasMap.put(underlying, Set.copyOf(siblings));
    }

    /** Adds a type statement; the new statement about its variable, or null if nothing changed. */
    public TypeStatement addTypeStatement(Flow flow, TypeStatement statement) {
        flow.checkMutable();
        if (statement.isEmpty()) return null;
        DataFlowVariable variable = statement.variable();
        TypeStatement old = flow.approvedTypeStatements.get(variable);
        if (old != null && old.upperTypes().containsAll(statement.upperTypes())
                && old.lowerTypes().containsAll(statement.lowerTypes())) {
            return null;
        }
        TypeStatement result = old == null ? statement : old.and(statement);
        flow.approvedTypeStatements.put(variable, result);
        return result;
    }

    public void addImplication(Flow flow, Implication implication) {
        flow.checkMutable();
        Statement effect = implication.effect();
        boolean redundant = effect.equals(implication.condition()) || switch (effect) {
            case TypeStatement t -> t.isEmpty() || containsAlready(flow, t);
            // Synthetic variables can only be referenced in tree order: if nothing is known about a part of an
            // expression yet, nothing will be, as the whole expression is being exited.
            case OperationStatement o ->
                    o.variable() instanceof SyntheticVariable && !flow.implications.containsKey(o.variable());
        };
        if (redundant) return;
        DataFlowVariable variable = implication.condition().variable();
        List<Implication> list = new ArrayList<>(flow.implications.getOrDefault(variable, List.of()));
        list.add(implication);
        flow.implications.put(variable, List.copyOf(list));
    }

    private static boolean containsAlready(Flow flow, TypeStatement effect) {
        TypeStatement approved = flow.approvedTypeStatements.get(effect.variable());
        return approved != null && approved.upperTypes().containsAll(effect.upperTypes())
                && approved.lowerTypes().containsAll(effect.lowerTypes());
    }

    /**
     * Makes the implications with conditions on one variable conditions on
     * another, through the transform (null to drop one); a synthetic
     * original loses its implications.
     */
    public void translateVariableFromConditionInStatements(Flow flow, DataFlowVariable original,
                                                           DataFlowVariable newVariable,
                                                           UnaryOperator<Implication> transform) {
        flow.checkMutable();
        List<Implication> statements = original instanceof SyntheticVariable
                ? flow.implications.remove(original) : flow.implications.get(original);
        if (statements == null || statements.isEmpty()) return;
        List<Implication> result = new ArrayList<>(flow.implications.getOrDefault(newVariable, List.of()));
        for (Implication i : statements) {
            Implication translated = transform.apply(
                    new OperationStatement(newVariable, i.condition().operation()).implies(i.effect()));
            if (translated != null) result.add(translated);
        }
        flow.implications.put(newVariable, List.copyOf(result));
    }

    // ==== Approving statements ====

    /** The type statements implied by an operation statement in a flow, which is left as it is. */
    public Map<DataFlowVariable, TypeStatement> approveOperationStatement(Flow flow, OperationStatement statement) {
        return approveOperationStatement(new HashMap<>(flow.implications), statement, false);
    }

    /**
     * The type statements implied by an operation statement.
     *
     * @param removeApprovedOrImpossible whether to drop the implications whose conditions are now known
     */
    public Map<DataFlowVariable, TypeStatement> approveOperationStatement(Flow flow, OperationStatement statement,
                                                                          boolean removeApprovedOrImpossible) {
        flow.checkMutable();
        return approveOperationStatement(flow.implications, statement, removeApprovedOrImpossible);
    }

    private Map<DataFlowVariable, TypeStatement> approveOperationStatement(
            Map<DataFlowVariable, List<Implication>> logicStatements, OperationStatement approvedStatement,
            boolean removeApprovedOrImpossible) {
        Map<DataFlowVariable, TypeStatement> result = new LinkedHashMap<>();
        Deque<OperationStatement> queue = new ArrayDeque<>();
        queue.add(approvedStatement);
        Set<OperationStatement> approved = new HashSet<>();
        while (!queue.isEmpty()) {
            OperationStatement next = queue.removeFirst();
            // Against cycles in the facts.
            if (!removeApprovedOrImpossible && !approved.add(next)) continue;
            Operation operation = next.operation();
            DataFlowVariable variable = next.variable();
            KType impliedType = operation == Operation.EQ_NULL ? ts.nullableNothing() : ts.any();
            // For booleans, the opposite lower type, since EqTrue and EqFalse are not stored.
            Set<DfaType> lower = switch (operation) {
                case EQ_TRUE -> Set.of(new DfaType.BooleanLiteral(false));
                case EQ_FALSE -> Set.of(new DfaType.BooleanLiteral(true));
                default -> Set.of();
            };
            result.merge(variable, new TypeStatement(variable, Set.of(impliedType), lower), TypeStatement::and);

            List<Implication> statements = logicStatements.get(variable);
            if (statements == null) continue;
            List<Implication> stillUnknown = new ArrayList<>();
            for (Implication i : statements) {
                Boolean knownValue = i.condition().operation().valueIfKnown(operation);
                if (Boolean.TRUE.equals(knownValue)) {
                    switch (i.effect()) {
                        case OperationStatement o -> queue.add(o);
                        case TypeStatement t -> result.merge(t.variable(), t, TypeStatement::and);
                    }
                }
                if (!(removeApprovedOrImpossible && knownValue != null)) stillUnknown.add(i);
            }
            if (stillUnknown.size() != statements.size()) {
                if (stillUnknown.isEmpty()) {
                    logicStatements.remove(variable);
                } else {
                    logicStatements.put(variable, List.copyOf(stillUnknown));
                }
            }
        }
        return result;
    }

    /** Whether a type statement is consistent with what the flow knows of its variable, as FIR's approveTypeStatement. */
    public boolean approveTypeStatement(Flow flow, TypeStatement statement) {
        DataFlowVariable variable = statement.variable();
        KType typeFromVar = variable.originalType();
        TypeStatement known = flow.getTypeStatement(variable);
        TypeStatement approvedStatement;
        if (known != null && typeFromVar != null) {
            approvedStatement = known.and(TypeStatement.typeEq(variable, typeFromVar));
        } else if (known != null) {
            approvedStatement = known;
        } else if (typeFromVar != null) {
            approvedStatement = TypeStatement.typeEq(variable, typeFromVar);
        } else {
            return false;
        }
        KType approvedUpper = unifiedUpperType(List.of(approvedStatement));
        KType approvedLower = intersectedLowerType(List.of(approvedStatement));
        KType statementUpper = unifiedUpperType(List.of(statement));
        KType statementLower = intersectedLowerType(List.of(statement));
        return (approvedUpper == null || statementUpper == null || ts.isSubtype(approvedUpper, statementUpper))
                && (approvedLower == null || statementUpper == null || !ts.isSubtype(statementUpper, approvedLower))
                && (statementLower == null || approvedUpper == null || !ts.isSubtype(approvedUpper, statementLower));
    }

    // ==== Assignments ====

    public void recordNewAssignment(Flow flow, RealVariable variable, int index) {
        flow.checkMutable();
        replaceVariable(flow, variable, null);
        flow.assignmentIndex.put(variable, index);
    }

    public boolean isSameValueIn(Flow a, Flow b, RealVariable variable) {
        return Objects.equals(a.assignmentIndex.get(variable), b.assignmentIndex.get(variable));
    }

    /** Replaces a variable in the statements by another, or drops it from them if the replacement is null. */
    private void replaceVariable(Flow flow, RealVariable variable, RealVariable replacement) {
        RealVariable original = flow.directAliasMap.remove(variable);
        if (original != null) {
            // The statements are about what it aliases; nothing to replace.
            Set<RealVariable> siblings = flow.backwardsAliasMap.get(original);
            if (siblings.size() > 1) {
                Set<RealVariable> rest = new LinkedHashSet<>(siblings);
                rest.remove(variable);
                flow.backwardsAliasMap.put(original, Set.copyOf(rest));
            } else {
                flow.backwardsAliasMap.remove(original);
            }
            if (replacement != null) addLocalVariableAlias(flow, replacement, original);
            return;
        }
        Set<RealVariable> aliases = flow.backwardsAliasMap.remove(variable);
        // Without a replacement but with aliases, one of them becomes the representative.
        RealVariable replacementOrNext = replacement != null ? replacement
                : aliases != null ? aliases.iterator().next() : null;
        variableStorage.replaceReceiverReferencesInMembers(variable, replacementOrNext,
                (old, renamed) -> replaceVariable(flow, old, renamed));
        replaceVariableInImplications(flow.implications, variable, replacementOrNext);
        TypeStatement existing = flow.approvedTypeStatements.remove(variable);
        if (existing != null && replacementOrNext != null) {
            flow.approvedTypeStatements.put(replacementOrNext, existing.withVariable(replacementOrNext));
        }
        if (aliases != null && replacementOrNext != null) {
            flow.directAliasMap.remove(replacementOrNext);
            Set<RealVariable> withoutSelf = new LinkedHashSet<>(aliases);
            withoutSelf.remove(replacementOrNext);
            if (!withoutSelf.isEmpty()) {
                for (RealVariable a : withoutSelf) flow.directAliasMap.put(a, replacementOrNext);
                Set<RealVariable> merged = new LinkedHashSet<>(
                        flow.backwardsAliasMap.getOrDefault(replacementOrNext, Set.of()));
                merged.addAll(withoutSelf);
                flow.backwardsAliasMap.put(replacementOrNext, Set.copyOf(merged));
            }
        }
    }

    private static void replaceVariableInImplications(Map<DataFlowVariable, List<Implication>> implications,
                                                      RealVariable from, RealVariable to) {
        List<Implication> existing = implications.remove(from);
        Map<DataFlowVariable, List<Implication>> toReplace = new LinkedHashMap<>();
        for (var e : implications.entrySet()) {
            List<Implication> replaced = new ArrayList<>();
            for (Implication i : e.getValue()) {
                if (to != null) {
                    replaced.add(replaceVariable(i, from, to));
                } else if (!i.effect().variable().equals(from)) {
                    replaced.add(i);
                }
            }
            if (!replaced.equals(e.getValue())) toReplace.put(e.getKey(), replaced);
        }
        toReplace.forEach((variable, list) -> {
            if (list.isEmpty()) {
                implications.remove(variable);
            } else {
                implications.put(variable, List.copyOf(list));
            }
        });
        if (existing != null && to != null) {
            implications.put(to, existing.stream().map(i -> replaceVariable(i, from, to)).toList());
        }
    }

    private static Implication replaceVariable(Implication i, RealVariable from, RealVariable to) {
        if (i.condition().variable().equals(from)) {
            return new OperationStatement(to, i.condition().operation()).implies(replaceVariable(i.effect(), from, to));
        }
        if (i.effect().variable().equals(from)) return new Implication(i.condition(), replaceVariable(i.effect(), from, to));
        return i;
    }

    private static Statement replaceVariable(Statement s, RealVariable from, RealVariable to) {
        if (!s.variable().equals(from)) return s;
        return switch (s) {
            case OperationStatement o -> new OperationStatement(to, o.operation());
            case TypeStatement t -> t.withVariable(to);
        };
    }

    // ==== Combining type statements ====

    public Map<DataFlowVariable, TypeStatement> orForTypeStatements(Map<DataFlowVariable, TypeStatement> left,
                                                                    Map<DataFlowVariable, TypeStatement> right) {
        if (left.isEmpty()) return left;
        if (right.isEmpty()) return right;
        Map<DataFlowVariable, TypeStatement> result = new LinkedHashMap<>();
        left.forEach((variable, leftStatement) -> {
            TypeStatement rightStatement = right.get(variable);
            if (rightStatement == null) return;
            TypeStatement s = or(List.of(leftStatement, rightStatement));
            if (s != null) result.put(variable, s);
        });
        return result;
    }

    public Map<DataFlowVariable, TypeStatement> andForTypeStatements(Map<DataFlowVariable, TypeStatement> left,
                                                                     Map<DataFlowVariable, TypeStatement> right) {
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        Map<DataFlowVariable, TypeStatement> result = new LinkedHashMap<>(left);
        right.forEach((variable, s) -> result.merge(variable, s, TypeStatement::and));
        return result;
    }

    /** The conjunction; null for none. */
    public TypeStatement and(Collection<TypeStatement> statements) {
        TypeStatement result = null;
        for (TypeStatement s : statements) result = result == null ? s : result.and(s);
        return result;
    }

    /** The disjunction, as far as it can be said; null for none or nothing known. */
    public TypeStatement or(Collection<TypeStatement> statements) {
        if (statements.isEmpty()) return null;
        if (statements.size() == 1) return statements.iterator().next();
        DataFlowVariable variable = statements.iterator().next().variable();
        if (statements.stream().anyMatch(TypeStatement::isEmpty)) return null;
        KType unified = unifiedUpperType(statements);
        Set<KType> upper;
        if (unified == null || isNullableAny(unified)) {
            upper = Set.of();
        } else if (isAcceptableForSmartcast(unified)) {
            upper = Set.of(unified);
        } else if (Types.canBeNull(unified)) {
            upper = Set.of();
        } else {
            upper = Set.of(ts.any());
        }
        Set<DfaType> lower = new LinkedHashSet<>();
        KType intersectedLower = intersectedLowerType(statements);
        if (intersectedLower != null) lower.add(new DfaType.Cone(intersectedLower));
        lower.addAll(commonExcludedValues(statements));
        return upper.isEmpty() && lower.isEmpty() ? null : new TypeStatement(variable, upper, lower);
    }

    private boolean isNullableAny(KType t) {
        return t instanceof KType.ClassType c && c.nullable() && c.symbol() == ts.anyClass();
    }

    private KType unifiedUpperType(Collection<TypeStatement> statements) {
        List<KType> intersected = new ArrayList<>();
        for (TypeStatement s : statements) {
            intersected.add(s.upperTypes().isEmpty() ? ts.nullableAny() : ts.intersect(s.upperTypes()));
        }
        return intersected.isEmpty() ? null : ts.commonSuperType(intersected);
    }

    /**
     * The intersection of the types the statements exclude, or Nothing for
     * a statement that excludes none; not quite correct, but sound.
     */
    private KType intersectedLowerType(Collection<TypeStatement> statements) {
        List<KType> types = new ArrayList<>();
        for (TypeStatement s : statements) {
            List<KType> cones = new ArrayList<>();
            for (DfaType t : s.lowerTypes()) {
                if (t instanceof DfaType.Cone c) cones.add(c.type());
            }
            types.addAll(cones.isEmpty() ? List.of(ts.nothing()) : cones);
        }
        KType result = ts.intersect(types);
        return ts.isNothing(result) ? null : result;
    }

    private static Set<DfaType.Symbol> commonExcludedValues(Collection<TypeStatement> statements) {
        Map<DfaType.Symbol, Integer> counts = new LinkedHashMap<>();
        for (TypeStatement s : statements) {
            for (DfaType t : s.lowerTypes()) {
                if (t instanceof DfaType.Symbol sym) counts.merge(sym, 1, Integer::sum);
            }
        }
        Set<DfaType.Symbol> result = new LinkedHashSet<>();
        counts.forEach((sym, n) -> {
            if (n == statements.size()) result.add(sym);
        });
        return result;
    }
}
