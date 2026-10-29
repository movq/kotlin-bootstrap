package kotlinbootstrap.check;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.Stmt;
import kotlinbootstrap.ast.ValueArg;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeSystem;

/**
 * Whether a local {@code var} is stable where it is read, as FIR's
 * FirLocalVariableAssignmentAnalyzer: a lambda not called in place, local
 * function or local class may run at any time, so a variable it assigns
 * is unstable outside it, and one assigned after it is unstable inside it.
 * Which variables are assigned where comes from a mini control flow graph
 * of the syntax of the outermost body (the root), built when first needed;
 * the {@link DataFlowAnalyzer} reports entering and leaving functions,
 * classes, loops and calls as it goes.
 *
 * <p>Declarations, loops and variables are keyed by their syntax nodes:
 * {@link Expr.Lambda}, {@link Expr.AnonymousFunction}, {@link Decl.Function},
 * {@link Decl.Class}, {@link Decl.Initializer}, the loop expressions, and
 * {@link Decl.Property} or {@link Decl.DestructuringEntry} for variables.
 */
final class LocalVariableAssignments {
    /** An assignment; its type is known once it is checked, if it is. */
    private static final class Assignment {
        final boolean operator;
        KType type;

        Assignment(boolean operator) {
            this.operator = operator;
        }
    }

    /** Assignments by the declaration of the variable assigned. */
    private static final class VariableAssignments {
        private final Map<Object, Set<Assignment>> assignments = new IdentityHashMap<>();

        Set<Assignment> get(Object declaration) {
            return assignments.get(declaration);
        }

        boolean contains(Object declaration) {
            return assignments.containsKey(declaration);
        }

        boolean add(Object declaration, Assignment assignment) {
            return assignments.computeIfAbsent(declaration, k -> new LinkedHashSet<>()).add(assignment);
        }

        boolean merge(VariableAssignments other) {
            if (other == null) return false;
            boolean modified = false;
            for (Map.Entry<Object, Set<Assignment>> e : other.assignments.entrySet()) {
                modified |= assignments.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
            }
            return modified;
        }

        void retain(Set<Object> declarations) {
            assignments.keySet().retainAll(declarations);
        }

        /** Shares the sets, as FIR's copy does. */
        VariableAssignments copy() {
            VariableAssignments copy = new VariableAssignments();
            copy.assignments.putAll(assignments);
            return copy;
        }

        /** The variables assigned other than by operators (KT-57563). */
        List<Object> assignedVariables() {
            List<Object> result = new ArrayList<>();
            for (Map.Entry<Object, Set<Assignment>> e : assignments.entrySet()) {
                if (e.getValue().stream().anyMatch(a -> !a.operator)) result.add(e.getKey());
            }
            return result;
        }
    }

    /**
     * A declaration or loop in the graph.
     *
     * @param assignedLater  what is assigned on the paths after it
     * @param assignedInside what it assigns of the variables declared outside it
     */
    private record Fork(VariableAssignments assignedLater, VariableAssignments assignedInside) {
    }

    /** A function, class or loop entered, with the assignments that make variables unstable in it. */
    private record Scope(Fork fork, VariableAssignments prohibited) {
    }

    private final TypeSystem ts;
    private Object root;
    private Map<Object, Fork> forks;
    private Map<Object, List<Assignment>> assignments;
    private final ArrayDeque<Scope> scopes = new ArrayDeque<>();
    /**
     * The lambdas of the calls being checked, by whether only data flows
     * from them: those of a call not completed, whose lambdas may be checked
     * after later arguments, as in {@code f(run { a = null }, a.x)}. Those
     * of one call run in an unknown order, as in {@code f({ a.x }, { a = null })}.
     */
    private final ArrayDeque<Map<Fork, Boolean>> postponedLambdas = new ArrayDeque<>();
    private final Map<LocalVariable, Object> declarations = new HashMap<>();
    private final Map<Object, LocalVariable> variables = new IdentityHashMap<>();

    LocalVariableAssignments(TypeSystem ts) {
        this.ts = ts;
    }

    // ==== Queries ====

    /** A local variable declared by the syntax. */
    void declare(LocalVariable v, Object declaration) {
        declarations.put(v, declaration);
        variables.put(declaration, v);
    }

    /**
     * Whether a read of a variable here is unstable: some assignment that
     * may run concurrently does not keep it of all the types; with types
     * null, any such assignment.
     */
    boolean isUnstableInCurrentScope(LocalVariable v, Collection<KType> types) {
        if (forks == null || !v.isVar()) return false;
        Object declaration = declarations.get(v);
        if (declaration == null) return false;
        if (!allAssignmentsPreserveType(scopes.peek().prohibited().get(declaration), types)) return true;
        // Control flow postponed lambdas are in the scope already, unless one is the lambda being checked.
        for (Map<Fork, Boolean> lambdas : postponedLambdas) {
            for (Map.Entry<Fork, Boolean> e : lambdas.entrySet()) {
                if (e.getValue() && e.getKey().assignedInside().contains(declaration)) return true;
            }
        }
        return false;
    }

    private boolean allAssignmentsPreserveType(Set<Assignment> assignments, Collection<KType> types) {
        if (assignments == null || assignments.isEmpty()) return true;
        if (types == null) return false;
        for (Assignment a : assignments) {
            if (a.type == null) return false;
            for (KType t : types) {
                if (!ts.isSubtype(a.type, t)) return false;
            }
        }
        return true;
    }

    /** The type of the next assignment of a variable in the graph. */
    void visitAssignment(LocalVariable v, KType type) {
        if (root == null) return;
        build();
        List<Assignment> list = assignments.get(declarations.get(v));
        if (list == null) return;
        for (Assignment a : list) {
            if (a.type == null) {
                a.type = type;
                return;
            }
        }
    }

    // ==== Scopes ====

    private Fork info(Object node) {
        if (root == null || root == node) return null;
        return build().get(node);
    }

    private Map<Object, Fork> build() {
        if (forks == null) {
            Builder b = new Builder();
            b.visitChildren(root);
            forks = b.forks;
            assignments = b.assignments;
        }
        return forks;
    }

    private Scope enterScope(Object node, boolean inPlace) {
        Fork info = info(node);
        VariableAssignments prohibitInThisScope = scopes.peek().prohibited().copy();
        scopes.push(new Scope(info, prohibitInThisScope));
        if (!inPlace) {
            for (Scope outer : scopes) {
                // It may be stored and called later, so what it assigns is unstable outside it, ever;
                // FIR does this to later branches too (KT-59692).
                outer.prohibited().merge(info == null ? null : info.assignedInside());
                // And what is assigned outside it after it is unstable inside it.
                prohibitInThisScope.merge(outer.fork() == null ? null : outer.fork().assignedLater());
            }
        }
        return scopes.peek();
    }

    /**
     * Enters a function, lambda or accessor, the root if none is entered;
     * returns the variables declared before it that it assigns.
     */
    Set<LocalVariable> enterFunction(Object node, boolean inPlace) {
        if (root == null) {
            root = node;
            scopes.push(new Scope(null, new VariableAssignments()));
            return Set.of();
        }
        Scope scope = enterScope(node, inPlace);
        for (Map<Fork, Boolean> lambdas : postponedLambdas) {
            for (Map.Entry<Fork, Boolean> e : lambdas.entrySet()) {
                if (!e.getValue() && e.getKey() != scope.fork()) scope.prohibited().merge(e.getKey().assignedInside());
            }
        }
        return assignedVariables(scope.fork());
    }

    void exitFunction() {
        scopes.pop();
        if (scopes.isEmpty()) {
            root = null;
            forks = null;
            assignments = null;
        }
    }

    /** Enters an init block: the root if none is entered, else nothing, as FIR's enterAnonymousInitializer. */
    void enterAnonymousInitializer(Decl.Initializer node) {
        if (root == null) enterFunction(node, false);
    }

    void exitAnonymousInitializer(Decl.Initializer node) {
        if (root == node) exitFunction();
    }

    void enterClass(Decl.Class node, boolean isAnonymousObject) {
        if (root == null) return;
        Scope scope = enterScope(node, isAnonymousObject);
        // Assignments in its initializers and members make smart casts unstable in the others.
        if (isAnonymousObject && scope.fork() != null) scope.prohibited().merge(scope.fork().assignedInside());
    }

    void exitClass() {
        if (root == null) return;
        scopes.pop();
    }

    /** Enters a call with lambdas among its arguments, as {@link Expr.Lambda} or {@link Expr.AnonymousFunction}. */
    void enterFunctionCall(List<Expr> lambdas) {
        if (root == null) return;
        Map<Fork, Boolean> frame = new LinkedHashMap<>();
        for (Expr l : lambdas) {
            Fork f = info(l);
            if (f != null) frame.put(f, false);
        }
        postponedLambdas.push(frame);
    }

    /** Leaves a call; the lambdas of one not completed are checked with the call it is an argument of. */
    void exitFunctionCall(boolean completed) {
        if (root == null) return;
        Map<Fork, Boolean> lambdas = postponedLambdas.pop();
        if (completed || postponedLambdas.isEmpty()) return;
        for (Fork f : lambdas.keySet()) postponedLambdas.peek().put(f, true);
    }

    /** Enters a loop; returns the variables declared before it that it assigns. */
    Set<LocalVariable> enterLoop(Expr node) {
        if (root == null) return Set.of();
        return assignedVariables(enterScope(node, true).fork());
    }

    void exitLoop() {
        if (root == null) return;
        scopes.pop();
    }

    private Set<LocalVariable> assignedVariables(Fork fork) {
        if (fork == null) return Set.of();
        Set<LocalVariable> result = new LinkedHashSet<>();
        for (Object declaration : fork.assignedInside().assignedVariables()) {
            LocalVariable v = variables.get(declaration);
            if (v != null) result.add(v);
        }
        return result;
    }

    /** The lambda an argument is, if it is one. */
    static Expr lambdaArgument(Expr e) {
        while (true) {
            switch (e) {
                case Expr.Paren p -> e = p.expr();
                case Expr.Labeled l -> e = l.expr();
                case Expr.Annotated a -> e = a.expr();
                case Expr.Lambda l -> {
                    return l;
                }
                case Expr.AnonymousFunction f -> {
                    return f;
                }
                default -> {
                    return null;
                }
            }
        }
    }

    // ==== The graph ====

    /**
     * A point in the graph, with what is assigned on the paths after it.
     * Only points where paths fork or join are kept; assignments elsewhere
     * are recorded at the last one before.
     */
    private static final class MiniFlow {
        final Set<MiniFlow> parents;
        final VariableAssignments assignedLater = new VariableAssignments();

        MiniFlow(Set<MiniFlow> parents) {
            this.parents = parents;
        }

        MiniFlow fork() {
            return new MiniFlow(Set.of(this));
        }

        void record(Object declaration, Assignment assignment) {
            ArrayDeque<MiniFlow> work = new ArrayDeque<>(List.of(this));
            while (!work.isEmpty()) {
                MiniFlow f = work.pop();
                // Parents need no update if this was not updated.
                if (f.assignedLater.add(declaration, assignment)) work.addAll(f.parents);
            }
        }

        void recordAll(VariableAssignments assignments) {
            ArrayDeque<MiniFlow> work = new ArrayDeque<>(List.of(this));
            while (!work.isEmpty()) {
                MiniFlow f = work.pop();
                if (f.assignedLater.merge(assignments)) work.addAll(f.parents);
            }
        }
    }

    /**
     * Builds the graph, as FIR's MiniCfgBuilder, resolving the names
     * assigned to the local variables declared before them.
     */
    private static final class Builder {
        MiniFlow flow = new MiniFlow(Set.of());
        final List<Map<String, Object>> scopes = new ArrayList<>(List.of(new HashMap<>()));
        final Map<Object, List<Assignment>> assignments = new IdentityHashMap<>();
        final Map<Object, Fork> forks = new IdentityHashMap<>();

        void visit(Object node) {
            switch (node) {
                case null -> {
                }
                case Expr.Lambda l -> localDeclaration(l);
                case Expr.AnonymousFunction f -> localDeclaration(f);
                case Decl.Function f -> localDeclaration(f);
                case Decl.Class c -> localDeclaration(c);
                case Decl.Initializer i -> localDeclaration(i);
                case Expr.Block b -> block(b.statements());
                case Expr.DeclExpr d -> statement(d.decl());
                // As in FIR's when, the condition is in the first branch.
                case Expr.If i -> branches(List.of(Arrays.asList(i.condition(), i.then()),
                        Collections.singletonList(i.otherwise())));
                case Expr.When w -> {
                    visit(w.subject());
                    if (w.subjectVariable() != null) visit(w.subjectVariable().initializer());
                    List<List<?>> branches = new ArrayList<>();
                    for (Expr.WhenEntry e : w.entries()) branches.add(List.of(e));
                    branches(branches);
                }
                case Expr.Try t -> {
                    visit(t.body());
                    List<List<?>> branches = new ArrayList<>();
                    for (Expr.Catch c : t.catches()) branches.add(List.of(c));
                    branches(branches);
                    visit(t.finallyBlock());
                }
                case Expr.While w -> loop(w, () -> visitChildren(w));
                case Expr.DoWhile w -> loop(w, () -> visitChildren(w));
                case Expr.For f -> {
                    visit(f.iterable());
                    loop(f, () -> visit(f.body()));
                }
                case Expr.Call c -> {
                    visit(c.callee());
                    // Lambdas run after the other arguments are evaluated.
                    List<Expr> lambdas = new ArrayList<>();
                    for (ValueArg a : c.args()) {
                        if (a.name() == null && !a.spread() && lambdaArgument(a.value()) != null) {
                            lambdas.add(a.value());
                        } else {
                            visit(a.value());
                        }
                    }
                    lambdas.forEach(this::visit);
                    c.lambdas().forEach(this::visit);
                }
                case Expr.Assign a -> {
                    visitChildren(a);
                    recordAssignment(a.target(), a.op() != Expr.AssignOp.ASSIGN);
                }
                case Expr.Prefix p when p.op() == Expr.PrefixOp.INC || p.op() == Expr.PrefixOp.DEC -> {
                    visitChildren(p);
                    recordAssignment(p.expr(), true);
                }
                case Expr.Postfix p when p.op() == Expr.PostfixOp.INC || p.op() == Expr.PostfixOp.DEC -> {
                    visitChildren(p);
                    recordAssignment(p.expr(), true);
                }
                case Record r -> visitChildren(r);
                case Collection<?> c -> c.forEach(this::visit);
                default -> {
                }
            }
        }

        void visitChildren(Object node) {
            switch (node) {
                case Expr.Lambda l -> {
                    visit(l.params());
                    block(l.body());
                }
                case Expr.AnonymousFunction f -> visitChildren(f.function());
                case Record r -> {
                    for (RecordComponent c : r.getClass().getRecordComponents()) visit(get(r, c));
                }
                // The header of a class, as the root for its primary constructor.
                case Collection<?> c -> c.forEach(this::visit);
                default -> throw new IllegalArgumentException(node.toString());
            }
        }

        private void block(List<Stmt> statements) {
            scopes.add(new HashMap<>());
            statements.forEach(this::statement);
            scopes.removeLast();
        }

        /** A statement of a block, where properties are local variables, declared after their initializers. */
        private void statement(Stmt s) {
            switch (s) {
                case Decl.Property p -> {
                    visitChildren(p);
                    scopes.getLast().put(p.name(), p);
                }
                case Decl.Destructuring d -> {
                    visitChildren(d);
                    for (Decl.DestructuringEntry e : d.entries()) {
                        if (!e.name().equals("_")) scopes.getLast().put(e.name(), e);
                    }
                }
                default -> visit(s);
            }
        }

        /** Branches from here, each of which may run, or none. */
        private void branches(List<? extends List<?>> branches) {
            MiniFlow start = flow;
            Set<MiniFlow> ends = new LinkedHashSet<>(List.of(start));
            for (List<?> branch : branches) {
                flow = start;
                branch.forEach(this::visit);
                ends.add(flow);
            }
            flow = ends.size() == 1 ? start : new MiniFlow(ends);
        }

        private void localDeclaration(Object node) {
            MiniFlow before = flow;
            VariableAssignments assignedInside = withLexicalScope(() -> visitChildren(node));
            // Without its own variables, what it assigns is assigned after the declarations before it.
            before.recordAll(assignedInside);
            flow = before.fork();
            forks.put(node, new Fork(flow.assignedLater, assignedInside));
        }

        private void loop(Expr node, Runnable body) {
            MiniFlow entry = flow;
            VariableAssignments assignedInside = withLexicalScope(body);
            entry.recordAll(assignedInside);
            // All forks in the loop have what it assigns assigned later, as at its start.
            flow.recordAll(assignedInside);
            flow = entry.fork();
            forks.put(node, new Fork(flow.assignedLater, assignedInside));
        }

        /** Visits in a detached flow, so that the variables declared inside do not leak out. */
        private VariableAssignments withLexicalScope(Runnable visit) {
            MiniFlow detached = new MiniFlow(Set.of());
            Set<Object> free = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Map<String, Object> scope : scopes) free.addAll(scope.values());
            flow = detached;
            visit.run();
            detached.assignedLater.retain(free);
            return detached.assignedLater;
        }

        private void recordAssignment(Expr target, boolean operator) {
            while (target instanceof Expr.Paren p) target = p.expr();
            if (!(target instanceof Expr.Name n)) return;
            Object declaration = null;
            for (int i = scopes.size() - 1; i >= 0 && declaration == null; i--) declaration = scopes.get(i).get(n.name());
            if (declaration == null) return;
            Assignment a = new Assignment(operator);
            assignments.computeIfAbsent(declaration, k -> new ArrayList<>()).add(a);
            flow.record(declaration, a);
        }

        private static Object get(Record r, RecordComponent c) {
            try {
                return c.getAccessor().invoke(r);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
