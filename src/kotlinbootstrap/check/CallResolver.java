package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.resolve.TypeResolver;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.Symbol;
import kotlinbootstrap.symbols.TypeAliasSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;

/**
 * Finds the candidates of a call, as FIR's FirTowerResolver: the levels
 * of the tower are searched in the order of their {@link TowerGroup}s,
 * and the search stops after the first group with an applicable candidate.
 * Each kind of receiver has its task of levels, as FIR's
 * FirTowerResolveTask; the tasks for the implicit invoke of a variable,
 * {@code f(x)} for {@code f.invoke(x)}, run alongside, as FIR's
 * FirInvokeResolveTowerExtension.
 */
final class CallResolver {
    private final TypeSystem ts;
    private final TypeResolver types;
    private final MemberScopes scopes;
    private final ResolutionStages stages;
    private final OverloadResolver overloads;
    private final Checker checker;
    private final CallCompleter completer;
    private final OverloadByLambdaReturnType byLambdaReturnType;
    private final DataFlowAnalyzer dfa;

    private static final Set<String> HIDES_MEMBERS_NAMES = Set.of("forEach", "addSuppressed");

    CallResolver(Checker checker, CallCompleter completer, OverloadByLambdaReturnType byLambdaReturnType,
                 DataFlowAnalyzer dfa) {
        this.ts = checker.ts();
        this.types = checker.resolver().types();
        this.scopes = checker.memberScopes();
        this.stages = checker.stages();
        this.overloads = checker.overloads();
        this.checker = checker;
        this.completer = completer;
        this.byLambdaReturnType = byLambdaReturnType;
        this.dfa = dfa;
    }

    /**
     * The candidates of a call: if successful, the most specific of the
     * applicable ones (more than one if ambiguous); otherwise the least bad.
     */
    record Result(List<Candidate> candidates, boolean success) {
    }

    Result resolve(CallInfo info, Context ctx) {
        return resolve(info, ctx, null);
    }

    /**
     * @param baseSystem a system each candidate starts with a copy of: for
     *                   the operator calls of a delegated property, the
     *                   delegate's; nullable
     */
    Result resolve(CallInfo info, Context ctx, ConstraintSystem baseSystem) {
        Resolution resolution = new Resolution(ctx, null, baseSystem);
        resolution.enqueueTasks(info);
        resolution.run();
        return resolution.reduce();
    }

    /**
     * A {@code this(...)} or {@code super(...)} call, or a supertype
     * initializer, as FIR's runResolverForDelegatingConstructor: the
     * constructors of the class constructed, with the type arguments of
     * the type constructed; of an inner class, on the nearest receiver of
     * the outer class outside the class being constructed.
     */
    Result resolveDelegatingConstructor(CallInfo info, KType.ClassType constructed, Context ctx) {
        ClassSymbol cls = constructed.symbol();
        TExpr dispatch = null;
        if (cls.isInner()) {
            List<Context.ImplicitReceiver> receivers = ctx.receivers();
            for (Context.ImplicitReceiver r : receivers.subList(Math.min(1, receivers.size()), receivers.size())) {
                if (ts.asSupertype(r.type(), cls.outer()) != null) {
                    dispatch = r.expr(info.pos());
                    break;
                }
            }
        }
        CallInfo withTypeArgs = new CallInfo(info.kind(), info.name(), null, info.arguments(),
                constructed.args().subList(0, cls.typeParams().size()), info.origin(), false, info.pos());
        Resolution resolution = new Resolution(ctx, null, null);
        TExpr outer = dispatch;
        resolution.enqueue(resolution.levels(resolution.mainTask(), t -> t.levels.add(new Level(
                TowerGroup.EMPTY_ROOT.member(), new ConstructorsLevel(cls, outer), withTypeArgs,
                Candidate.ExplicitReceiverKind.NONE))));
        resolution.run();
        return resolution.reduce();
    }

    // ==== Callable references ====

    /**
     * Resolves a callable reference against the type now expected of it,
     * as FIR's resolveCallableReference: each candidate starts with a copy
     * of the containing call's system, and the one chosen gives it back,
     * with its constraints added. Ambiguous, it is postponed once, to be
     * resolved again during completion.
     *
     * @param containing the call being resolved or completed, whose system has the reference's variables
     * @return whether the reference is resolved, or may still be
     */
    boolean resolveCallableReference(Candidate containing, CallableReferenceAtom atom, boolean hasSyntheticOuterCall) {
        CallableReferenceExpression e = atom.expression;
        ConstraintSystem system = containing.system;
        KType expected = atom.expectedType() == null ? null : system.buildCurrentSubstitutor().apply(atom.expectedType());
        CallInfo info = new CallInfo(CallInfo.Kind.CALLABLE_REFERENCE, e.name, e.lhs, List.of(), List.of(),
                TExpr.CallOrigin.PLAIN, false, e.pos);
        Resolution resolution = new Resolution(e.context,
                new CallInfo.ReferenceInfo(e, expected, system, hasSyntheticOuterCall), null);
        resolution.enqueueTasks(info);
        resolution.run();
        Result result = resolution.reduce();
        List<Candidate> candidates = result.candidates();
        if (candidates.isEmpty()) {
            atom.fail("Unresolved reference " + e.name);
            return false;
        }
        if (!result.success()) {
            atom.fail("No applicable " + e.name + ": " + candidates);
            return false;
        }
        if (candidates.size() > 1) {
            if (atom.state == CallableReferenceAtom.State.POSTPONED_BECAUSE_OF_AMBIGUITY) {
                atom.fail("Ambiguous reference " + e.name + ": " + candidates);
                return false;
            }
            atom.state = CallableReferenceAtom.State.POSTPONED_BECAUSE_OF_AMBIGUITY;
            return true;
        }
        Candidate chosen = candidates.getFirst();
        system.replaceContentWith(chosen.system);
        atom.resolve(chosen);
        return true;
    }

    // ==== Levels ====

    /** As FIR's ConstructorFilter: which classes' constructors a level offers. */
    private enum ConstructorFilter { ONLY_INNER, ONLY_NESTED, BOTH }

    private sealed interface TowerLevel {
    }

    /** The members of a receiver's type, as FIR's MemberScopeTowerLevel. */
    private record MemberLevel(TExpr dispatch, TExpr extension) implements TowerLevel {
    }

    /** The declarations of a scope, as FIR's ScopeBasedTowerLevel. */
    private record ScopeLevel(TowerScope scope, TExpr extension, boolean hidesMembersOnly, ConstructorFilter filter)
            implements TowerLevel {
        ScopeLevel(TowerScope scope, TExpr extension) {
            this(scope, extension, false, extension == null ? ConstructorFilter.ONLY_NESTED : ConstructorFilter.BOTH);
        }
    }

    /** The constructors of a class, for a delegating constructor call; with the outer instance for an inner class. */
    private record ConstructorsLevel(ClassSymbol cls, TExpr dispatch) implements TowerLevel {
    }

    private record Level(TowerGroup group, TowerLevel level, CallInfo info, Candidate.ExplicitReceiverKind kind) {
    }

    /** Only the functions and properties of a scope, as FIR's FirOnlyCallablesScope. */
    private record OnlyCallables(TowerScope scope) implements TowerScope {
        @Override
        public List<ScopeMember> functions(String name) {
            return scope.functions(name);
        }

        @Override
        public List<ScopeMember> properties(String name) {
            return scope.properties(name);
        }

        @Override
        public List<ClassifierSymbol> classifiers(String name) {
            return List.of();
        }
    }

    /** Only the classifiers of a scope, as FIR's FirOnlyClassifiersScope. */
    private record OnlyClassifiers(TowerScope scope) implements TowerScope {
        @Override
        public List<ScopeMember> functions(String name) {
            return List.of();
        }

        @Override
        public List<ScopeMember> properties(String name) {
            return List.of();
        }

        @Override
        public List<ClassifierSymbol> classifiers(String name) {
            return scope.classifiers(name);
        }
    }

    /** The nested classifiers of a class, as reached through it as a qualifier. */
    private record NestedClassifiers(ClassSymbol owner) implements TowerScope {
        @Override
        public List<ScopeMember> functions(String name) {
            return List.of();
        }

        @Override
        public List<ScopeMember> properties(String name) {
            return List.of();
        }

        @Override
        public List<ClassifierSymbol> classifiers(String name) {
            ClassifierSymbol c = owner.members().classifier(name);
            return c == null ? List.of() : List.of(c);
        }
    }

    // ==== Collecting candidates ====

    /** The best candidates found so far, as FIR's CandidateCollector. */
    private static final class Collector {
        final List<Candidate> candidates = new ArrayList<>();
        Applicability current = Applicability.HIDDEN;
        TowerGroup bestGroup = TowerGroup.LAST_GROUP;

        void consume(TowerGroup group, Candidate candidate, Applicability applicability) {
            if (applicability.compareTo(current) > 0 || applicability == current && group.compareTo(bestGroup) < 0) {
                // Only a successful candidate drops the others, so that the least bad are kept for errors.
                if (applicability.compareTo(Applicability.RESOLVED_LOW_PRIORITY) >= 0) candidates.clear();
                current = applicability;
                bestGroup = group;
            }
            if (applicability == current && group.equals(bestGroup)
                    || current.compareTo(Applicability.RESOLVED_LOW_PRIORITY) < 0) {
                candidates.add(candidate);
            }
        }

        boolean shouldStopResolve() {
            return current.shouldStopResolve();
        }

        boolean shouldStopAtGroup(TowerGroup group) {
            return shouldStopResolve() && bestGroup.compareTo(group) < 0;
        }

        boolean isSuccess() {
            return current.isSuccess();
        }

        void reset() {
            candidates.clear();
            current = Applicability.HIDDEN;
            bestGroup = TowerGroup.LAST_GROUP;
        }
    }

    /**
     * Levels to process in order, with the collector their candidates go
     * to: a suspended FirTowerResolveTask.
     */
    private static final class Task {
        final Collector collector;
        final List<Level> levels = new ArrayList<>();
        /** For the variable of an implicit invoke, the priority all its groups get. */
        final TowerGroup.InvokePriority priority;
        /** For an implicit invoke, the candidate of the variable, as FIR's candidateForCommonInvokeReceiver. */
        final Candidate invokeReceiver;
        /** Called after a level that leaves the collector successful. */
        final Consumer<TowerGroup> onSuccessfulLevel;
        /** For the variable of an implicit invoke, called with a local variable found and its group. */
        final BiConsumer<LocalVariable, TowerGroup> onLocalVariable;
        int next;

        Task(Collector collector, TowerGroup.InvokePriority priority, Candidate invokeReceiver,
             Consumer<TowerGroup> onSuccessfulLevel, BiConsumer<LocalVariable, TowerGroup> onLocalVariable) {
            this.collector = collector;
            this.priority = priority;
            this.invokeReceiver = invokeReceiver;
            this.onSuccessfulLevel = onSuccessfulLevel;
            this.onLocalVariable = onLocalVariable;
        }

        TowerGroup group(Level level) {
            return level.group().withInvokePriority(priority);
        }
    }

    private record Entry(Task task, TowerGroup group, long seq) {
    }

    /** The resolution of one call, as FIR's TowerResolveManager with the tasks it runs. */
    private final class Resolution {
        final Context ctx;
        /** For a callable reference, what resolving it needs; null otherwise. */
        final CallInfo.ReferenceInfo reference;
        /** The system each candidate starts with a copy of, besides a reference's; nullable. */
        final ConstraintSystem baseSystem;
        final Collector main = new Collector();
        final PriorityQueue<Entry> queue = new PriorityQueue<>(
                Comparator.comparing(Entry::group).thenComparingLong(Entry::seq));
        long seq;
        /** The implicit values for context arguments, made once for all the candidates. */
        List<List<TExpr>> implicitValues;

        Resolution(Context ctx, CallInfo.ReferenceInfo reference, ConstraintSystem baseSystem) {
            this.ctx = ctx;
            this.reference = reference;
            this.baseSystem = baseSystem;
        }

        void run() {
            while (!queue.isEmpty()) {
                Entry e = queue.poll();
                // A task stops at the first level after the best group.
                if (main.shouldStopAtGroup(e.group())) continue;
                Task task = e.task();
                Level level = task.levels.get(task.next++);
                process(task, level, e.group());
                if (task.collector.isSuccess() && task.onSuccessfulLevel != null) task.onSuccessfulLevel.accept(e.group());
                enqueue(task);
            }
        }

        List<List<TExpr>> implicitValues(int pos) {
            if (implicitValues == null) {
                implicitValues = ctx.implicitValueGroups(r -> dfa.smartCast(r.expr(pos)),
                        v -> dfa.smartCast(new TExpr.LocalGet(pos, v.type(), v)));
            }
            return implicitValues;
        }

        void enqueue(Task task) {
            if (task.next < task.levels.size()) {
                queue.add(new Entry(task, task.group(task.levels.get(task.next)), seq++));
            }
        }

        /** As FIR's reduceCandidates: without success, the least bad of the candidates processed fully. */
        Result reduce() {
            if (!main.isSuccess()) {
                if (main.candidates.isEmpty()) return new Result(List.of(), false);
                for (Candidate c : main.candidates) stages.fullyProcess(c, ctx);
                Applicability best = main.candidates.stream().map(Candidate::applicability)
                        .max(Comparator.naturalOrder()).orElseThrow();
                Set<Candidate> group = new LinkedHashSet<>();
                for (Candidate c : main.candidates) {
                    if (c.applicability() == best) group.add(c);
                }
                if (group.size() > 1) group = overloads.chooseMaximallySpecific(group);
                return new Result(List.copyOf(group), false);
            }
            Set<Candidate> best = overloads.chooseMaximallySpecific(new LinkedHashSet<>(main.candidates));
            return new Result(List.copyOf(byLambdaReturnType.reduceCandidates(best)), true);
        }

        // ==== Tasks ====

        /** As FIR's FirTowerResolver.enqueueResolutionTasks. */
        void enqueueTasks(CallInfo info) {
            switch (info.explicitReceiver()) {
                case null -> {
                    enqueue(levels(mainTask(), t -> noReceiver(t, info)));
                    if (info.kind() == CallInfo.Kind.FUNCTION) {
                        CallInfo receiverInfo = asInvokeReceiver(info);
                        enqueueInvokeReceiverTask(info, false, t -> noReceiver(t, receiverInfo));
                    }
                }
                case CallInfo.Qualifier q -> {
                    enqueue(levels(mainTask(), t -> qualifierReceiver(t, info, q)));
                    if (info.kind() == CallInfo.Kind.FUNCTION) {
                        enqueueForExplicitReceiver(info, (t, receiverInfo) -> qualifierReceiver(t, receiverInfo, q));
                    }
                }
                case CallInfo.SuperReceiver s -> {
                    enqueue(levels(mainTask(), t -> superReceiver(t, info, s)));
                    if (info.kind() == CallInfo.Kind.FUNCTION) {
                        CallInfo receiverInfo = asInvokeReceiver(info);
                        enqueueInvokeReceiverTask(info, false, t -> superReceiver(t, receiverInfo, s));
                    }
                }
                case CallInfo.ValueReceiver v when info.isImplicitInvoke() -> {
                    // (expr)(x): the invoke and both builtin forms, as FIR's enqueueResolveTasksForImplicitInvokeCall.
                    TowerGroup receiverGroup = TowerGroup.EMPTY_ROOT.withInvokePriority(
                            TowerGroup.InvokePriority.INVOKE_RECEIVER);
                    enqueue(levels(invokeTask(null), t -> invoke(t, info, v.value(), receiverGroup)));
                    enqueue(levels(invokeTask(null),
                            t -> builtinInvokeWithExplicitArgument(t, info, v.value(), receiverGroup)));
                    enqueue(levels(invokeTask(null),
                            t -> builtinInvokeWithImplicitArgument(t, info, v.value(), receiverGroup)));
                }
                case CallInfo.ValueReceiver v -> {
                    enqueue(levels(mainTask(), t -> expressionReceiver(t, info, v.value(), TowerGroup.EMPTY_ROOT)));
                    if (info.kind() == CallInfo.Kind.FUNCTION) {
                        enqueueForExplicitReceiver(info,
                                (t, receiverInfo) -> expressionReceiver(t, receiverInfo, v.value(), TowerGroup.EMPTY_ROOT));
                    }
                }
            }
        }

        Task mainTask() {
            return new Task(main, TowerGroup.InvokePriority.NONE, null, null, null);
        }

        Task levels(Task task, Consumer<Task> addLevels) {
            addLevels.accept(task);
            return task;
        }

        /** As FIR's runResolverForNoReceiver. */
        void noReceiver(Task t, CallInfo info) {
            enumerate(TowerGroup.EMPTY_ROOT, info,
                    (scope, group) -> t.levels.add(new Level(group, new ScopeLevel(scope, null), info,
                            Candidate.ExplicitReceiverKind.NONE)),
                    (receiver, group) -> implicitReceiverAsValue(t, info, receiver, group));
        }

        /** As FIR's processCandidatesWithGivenImplicitReceiverAsValue. */
        void implicitReceiverAsValue(Task t, CallInfo info, TExpr receiver, TowerGroup parent) {
            extensionsThatHideMembers(t, info, receiver, parent, Candidate.ExplicitReceiverKind.NONE);
            t.levels.add(new Level(parent.member(), new MemberLevel(receiver, null), info,
                    Candidate.ExplicitReceiverKind.NONE));
            enumerate(parent, info,
                    (scope, group) -> t.levels.add(new Level(group, new ScopeLevel(scope, receiver), info,
                            Candidate.ExplicitReceiverKind.NONE)),
                    (other, group) -> t.levels.add(new Level(group, new MemberLevel(other, receiver), info,
                            Candidate.ExplicitReceiverKind.NONE)));
        }

        /** As FIR's runResolverForExpressionReceiver. */
        void expressionReceiver(Task t, CallInfo info, TExpr receiver, TowerGroup parent) {
            extensionsThatHideMembers(t, info, receiver, parent, Candidate.ExplicitReceiverKind.EXTENSION);
            t.levels.add(new Level(parent.member(), new MemberLevel(receiver, null), info,
                    Candidate.ExplicitReceiverKind.DISPATCH));
            enumerate(parent, info,
                    (scope, group) -> t.levels.add(new Level(group, new ScopeLevel(scope, receiver), info,
                            Candidate.ExplicitReceiverKind.EXTENSION)),
                    (implicit, group) -> t.levels.add(new Level(group.member(), new MemberLevel(implicit, receiver),
                            info, Candidate.ExplicitReceiverKind.EXTENSION)));
        }

        /** As FIR's runResolverForQualifierReceiver. */
        void qualifierReceiver(Task t, CallInfo info, CallInfo.Qualifier q) {
            TowerGroup group = TowerGroup.QUALIFIER_OR_CLASSIFIER_GROUP;
            if (q.classifier() == null) {
                TowerScope pkg = ImportingScopes.packageScope(ts.table(), q.packageName());
                t.levels.add(new Level(group, new ScopeLevel(new OnlyCallables(pkg), null), info,
                        Candidate.ExplicitReceiverKind.NONE));
                t.levels.add(new Level(group, new ScopeLevel(new OnlyClassifiers(pkg), null), info,
                        Candidate.ExplicitReceiverKind.NONE));
                return;
            }
            ClassSymbol cls = expandedClass(q.classifier());
            if (cls != null) {
                t.levels.add(new Level(group, new ScopeLevel(new OnlyCallables(new TowerScope.ClassStatics(cls, scopes)),
                        null), info, Candidate.ExplicitReceiverKind.NONE));
                // Not through a type alias, but for a reference.
                if (cls == q.classifier() || info.kind() == CallInfo.Kind.CALLABLE_REFERENCE) {
                    t.levels.add(new Level(group, new ScopeLevel(new NestedClassifiers(cls), null), info,
                            Candidate.ExplicitReceiverKind.NONE));
                }
            }
            if (reference != null && reference.expression().lhsAsType != null) {
                // An unbound reference: the members and extensions of the type.
                TExpr stub = reference.expression().unboundReceiver();
                expressionReceiver(t, info.withReceiver(new CallInfo.ValueReceiver(stub), false), stub,
                        TowerGroup.QUALIFIER_VALUE_GROUP);
            }
            if (q.value() != null && q.typeArgs().isEmpty()) {
                expressionReceiver(t, info, q.value(), TowerGroup.QUALIFIER_VALUE_GROUP);
            }
        }

        /** As FIR's runResolverForSuperReceiver. */
        void superReceiver(Task t, CallInfo info, CallInfo.SuperReceiver s) {
            t.levels.add(new Level(TowerGroup.EMPTY_ROOT.member(), new MemberLevel(superValue(s), null), info,
                    Candidate.ExplicitReceiverKind.DISPATCH));
        }

        /** As FIR's processExtensionsThatHideMembers: {@code @HidesMembers} extensions, before the members. */
        void extensionsThatHideMembers(Task t, CallInfo info, TExpr receiver, TowerGroup parent,
                                       Candidate.ExplicitReceiverKind kind) {
            if (info.kind() != CallInfo.Kind.FUNCTION || !HIDES_MEMBERS_NAMES.contains(info.name())) return;
            List<Context.TowerElement> importing = checker.importing(ctx.file()).reversed();
            for (int i = 0; i < importing.size(); i++) {
                if (!(importing.get(i) instanceof Context.ScopeElement s)) continue;
                t.levels.add(new Level(parent.topPrioritized(i),
                        new ScopeLevel(s.scope(), receiver, true, ConstructorFilter.BOTH), info, kind));
            }
        }

        /** As FIR's enumerateTowerLevels: the local scopes, then the other levels, innermost first. */
        void enumerate(TowerGroup parent, CallInfo info, BiConsumer<TowerScope, TowerGroup> onScope,
                       BiConsumer<TExpr, TowerGroup> onImplicitReceiver) {
            List<LocalScope> locals = ctx.localsInnermostFirst();
            for (int i = 0; i < locals.size(); i++) onScope.accept(locals.get(i), parent.local(i));
            List<Context.TowerElement> elements = ctx.elementsInnermostFirst();
            for (int depth = 0; depth < elements.size(); depth++) {
                switch (elements.get(depth)) {
                    case Context.ScopeElement s -> onScope.accept(s.scope(), parent.nonLocal(depth));
                    case Context.ImplicitReceiver r ->
                            onImplicitReceiver.accept(dfa.smartCast(r.expr(info.pos())), parent.implicit(depth));
                    case Context.ContextValues v -> {
                    }
                }
            }
        }

        // ==== Implicit invoke ====

        /** The call of the variable an implicit invoke is on: {@code f} of {@code f(x)}. */
        CallInfo asInvokeReceiver(CallInfo info) {
            return new CallInfo(CallInfo.Kind.VARIABLE, info.name(), info.explicitReceiver(), List.of(), List.of(),
                    info.origin(), false, info.pos());
        }

        /**
         * As FIR's enqueueResolveForExplicitReceiver: for {@code a.f(x)}, the
         * variable {@code a.f} with invoke, and the variable {@code f} of an
         * extension function type with {@code a} as its first argument.
         */
        void enqueueForExplicitReceiver(CallInfo info, BiConsumer<Task, CallInfo> receiverLevels) {
            CallInfo receiverInfo = asInvokeReceiver(info);
            enqueueInvokeReceiverTask(info, false, t -> receiverLevels.accept(t, receiverInfo));
            CallInfo noReceiver = receiverInfo.withReceiver(null, false);
            enqueueInvokeReceiverTask(info, true, t -> noReceiver(t, noReceiver));
        }

        /**
         * As FIR's enqueueInvokeReceiverTask: a task finding the variable,
         * which for each level where it is found enqueues the tasks for invoke.
         *
         * @param builtinMode whether the variable must be of an extension function type, called with the
         *                    original explicit receiver as its first argument
         */
        void enqueueInvokeReceiverTask(CallInfo info, boolean builtinMode, Consumer<Task> receiverLevels) {
            Collector collector = new Collector();
            Task task = new Task(collector, TowerGroup.InvokePriority.INVOKE_RECEIVER, null,
                    group -> {
                        for (Candidate c : collector.candidates) invokeOnCandidate(info, builtinMode, c, group);
                        collector.reset();
                    },
                    (v, group) -> {
                        TExpr receiver = dfa.smartCast(new TExpr.LocalGet(info.pos(), v.type(), v));
                        boolean isExtension = isExtensionFunctionType(receiver.type());
                        if (builtinMode && !isExtension) return;
                        enqueueInvokeTasks(invokeInfo(info, receiver, builtinMode), receiver, group, null,
                                builtinMode, !builtinMode && isExtension);
                    });
            enqueue(levels(task, receiverLevels));
        }

        /** As FIR's enqueueResolverTasksForInvokeReceiverCandidates, for one variable. */
        void invokeOnCandidate(CallInfo info, boolean builtinMode, Candidate variable, TowerGroup receiverGroup) {
            boolean isExtension = variable.symbol instanceof CallableSymbol s && isExtensionFunctionType(s.returnType());
            if (builtinMode && !isExtension) return;
            TExpr receiver;
            try {
                receiver = completer.completeCall(variable, Mode.RECEIVER);
            } catch (CheckException e) {
                return;
            }
            boolean implicitArgument = !builtinMode && isExtension
                    && variable.explicitReceiverKind == Candidate.ExplicitReceiverKind.NONE;
            enqueueInvokeTasks(invokeInfo(info, receiver, builtinMode), receiver, receiverGroup,
                    builtinMode ? null : variable, builtinMode, implicitArgument);
        }

        CallInfo invokeInfo(CallInfo info, TExpr receiver, boolean builtinMode) {
            CallInfo invoke = new CallInfo(CallInfo.Kind.FUNCTION, "invoke", new CallInfo.ValueReceiver(receiver),
                    info.arguments(), info.typeArgs(), info.origin(), true, info.pos());
            return builtinMode ? withReceiverAsArgument(invoke, receiverValue(info.explicitReceiver())) : invoke;
        }

        /**
         * As FIR's enqueueResolverTasksForInvoke and InvokeFunctionResolveTask.
         *
         * @param builtinMode      for an explicit receiver passed as the first argument: only the
         *                         invoke of the function type
         * @param implicitArgument whether also to try the implicit receivers as the first argument
         */
        void enqueueInvokeTasks(CallInfo info, TExpr receiver, TowerGroup receiverGroup, Candidate variable,
                                boolean builtinMode, boolean implicitArgument) {
            if (builtinMode) {
                enqueue(levels(invokeTask(variable), t -> builtinInvokeWithExplicitArgument(t, info, receiver,
                        receiverGroup)));
                return;
            }
            if (implicitArgument) {
                enqueue(levels(invokeTask(variable), t -> builtinInvokeWithImplicitArgument(t, info, receiver,
                        receiverGroup)));
            }
            enqueue(levels(invokeTask(variable), t -> invoke(t, info, receiver, receiverGroup)));
        }

        Task invokeTask(Candidate variable) {
            return new Task(main, TowerGroup.InvokePriority.NONE, variable, null, null);
        }

        /** As FIR's runResolverForInvoke. */
        void invoke(Task t, CallInfo info, TExpr receiver, TowerGroup receiverGroup) {
            TowerGroup.InvokePriority common = TowerGroup.InvokePriority.COMMON_INVOKE;
            t.levels.add(new Level(TowerGroup.EMPTY_ROOT.member().invokeReceiver(receiverGroup, common),
                    new MemberLevel(receiver, null), info, Candidate.ExplicitReceiverKind.DISPATCH));
            enumerate(TowerGroup.EMPTY_ROOT, info,
                    (scope, group) -> t.levels.add(new Level(group.invokeReceiver(receiverGroup, common),
                            new ScopeLevel(scope, receiver), info, Candidate.ExplicitReceiverKind.EXTENSION)),
                    (implicit, group) -> t.levels.add(new Level(group.member().invokeReceiver(receiverGroup, common),
                            new MemberLevel(implicit, receiver), info, Candidate.ExplicitReceiverKind.EXTENSION)));
        }

        /** As FIR's runResolverForBuiltinInvokeExtensionWithExplicitArgument. */
        void builtinInvokeWithExplicitArgument(Task t, CallInfo info, TExpr receiver, TowerGroup receiverGroup) {
            t.levels.add(new Level(TowerGroup.EMPTY_ROOT.member().invokeReceiver(receiverGroup,
                    TowerGroup.InvokePriority.INVOKE_EXTENSION), new MemberLevel(receiver, null), info,
                    Candidate.ExplicitReceiverKind.DISPATCH));
        }

        /** As FIR's runResolverForBuiltinInvokeExtensionWithImplicitArgument. */
        void builtinInvokeWithImplicitArgument(Task t, CallInfo info, TExpr receiver, TowerGroup receiverGroup) {
            List<Context.TowerElement> elements = ctx.elementsInnermostFirst();
            for (int depth = 0; depth < elements.size(); depth++) {
                if (!(elements.get(depth) instanceof Context.ImplicitReceiver r)) continue;
                TowerGroup group = TowerGroup.EMPTY_ROOT.implicit(depth).invokeExtensionWithImplicitReceiver()
                        .invokeReceiver(receiverGroup, TowerGroup.InvokePriority.INVOKE_EXTENSION);
                t.levels.add(new Level(group, new MemberLevel(receiver, null),
                        withReceiverAsArgument(info, dfa.smartCast(r.expr(info.pos()))),
                        Candidate.ExplicitReceiverKind.DISPATCH));
            }
        }

        // ==== Processing levels ====

        void process(Task task, Level level, TowerGroup group) {
            CallInfo info = level.info();
            switch (level.level()) {
                case ConstructorsLevel k -> {
                    for (FunctionSymbol constructor : scopes.constructors(k.cls())) {
                        Candidate c = candidate(task, level, constructor, k.dispatch(), null, Substitution.EMPTY);
                        c.builder = w -> buildDelegatingCall(w, c);
                        if (k.cls().isInner() && k.dispatch() == null) {
                            c.addDiagnostic(Applicability.INAPPLICABLE, "No instance of " + k.cls().outer());
                        }
                        consume(task, group, c);
                    }
                }
                case MemberLevel m -> processMembers(task, level, group, m);
                case ScopeLevel s -> {
                    if (info.kind() != CallInfo.Kind.VARIABLE) {
                        processConstructors(task, level, group, s);
                        for (TowerScope.ScopeMember m : s.scope().functions(info.name())) {
                            consumeScopeMember(task, level, group, s, m);
                        }
                    }
                    if (info.kind() == CallInfo.Kind.CALLABLE_REFERENCE) {
                        for (TowerScope.ScopeMember m : s.scope().properties(info.name())) {
                            consumeScopeMember(task, level, group, s, m);
                        }
                    } else if (info.kind() == CallInfo.Kind.VARIABLE) {
                        for (TowerScope.ScopeMember m : s.scope().properties(info.name())) {
                            consumeScopeMember(task, level, group, s, m);
                        }
                        if (s.scope() instanceof LocalScope local && s.extension() == null
                                && task.onLocalVariable != null && local.variable(info.name()) != null) {
                            task.onLocalVariable.accept(local.variable(info.name()), group);
                        }
                        // Properties win over objects in the same scope.
                        if (!task.collector.shouldStopResolve() && s.extension() == null) {
                            for (ClassifierSymbol c : s.scope().classifiers(info.name())) {
                                consumeClassifier(task, level, group, c);
                            }
                        }
                    }
                }
            }
        }

        void processMembers(Task task, Level level, TowerGroup group, MemberLevel m) {
            CallInfo info = level.info();
            KType type = m.dispatch().type();
            if (info.kind() != CallInfo.Kind.VARIABLE) {
                // Constructors of inner classes, as members.
                List<MemberScopes.Inner> inner = scopes.innerClasses(type, info.name());
                if (!inner.isEmpty() && m.extension() == null) {
                    MemberScopes.Inner first = inner.getFirst();
                    for (FunctionSymbol k : first.symbol().members().constructors()) {
                        consume(task, group, candidate(task, level, k, m.dispatch(), null, first.substitution()));
                    }
                }
            }
            List<MemberScopes.Member> members = new ArrayList<>();
            if (info.kind() != CallInfo.Kind.VARIABLE) members.addAll(scopes.functions(type, info.name()));
            if (info.kind() != CallInfo.Kind.FUNCTION) members.addAll(scopes.properties(type, info.name()));
            for (MemberScopes.Member member : members) {
                if ((member.symbol().signature().receiver() != null) != (m.extension() != null)) continue;
                consume(task, group, candidate(task, level, member.symbol(), m.dispatch(), m.extension(),
                        member.substitution()));
            }
            if (info.kind() != CallInfo.Kind.FUNCTION && m.extension() == null) {
                for (MemberScopes.Member member : scopes.syntheticProperties(type, info.name())) {
                    consume(task, group, candidate(task, level, member.symbol(), m.dispatch(), null,
                            member.substitution()));
                }
            }
        }

        /** As FIR's processConstructorsByName: the constructors of the first class of the name. */
        void processConstructors(Task task, Level level, TowerGroup group, ScopeLevel s) {
            // Constructors have no extension receiver.
            if (s.hidesMembersOnly() || s.extension() != null) return;
            ClassifierSymbol found = null;
            for (ClassifierSymbol c : s.scope().classifiers(level.info().name())) {
                boolean inner = c instanceof ClassSymbol cls && cls.isInner();
                boolean accepted = switch (s.filter()) {
                    case ONLY_INNER -> inner;
                    case ONLY_NESTED -> !inner;
                    case BOTH -> true;
                };
                if (accepted && !(c instanceof TypeParamSymbol)) {
                    found = c;
                    break;
                }
            }
            switch (found) {
                case ClassSymbol cls -> {
                    for (FunctionSymbol k : scopes.constructors(cls)) {
                        consume(task, group, candidate(task, level, k, null, null, Substitution.EMPTY));
                    }
                }
                case TypeAliasSymbol alias when Types.lowerBound(alias.expansion()) instanceof KType.ClassType expanded -> {
                    Substitution toExpansion = Substitution.of(expanded.symbol().typeParams(), expanded.args());
                    for (FunctionSymbol k : scopes.constructors(expanded.symbol())) {
                        Candidate c = candidate(task, level, k, null, null, toExpansion);
                        c.typeParams = alias.typeParams();
                        c.typeAlias = alias;
                        consume(task, group, c);
                    }
                }
                case null, default -> {
                }
            }
            FunctionSymbol samConstructor = found == null ? null : checker.sam().samConstructor(found);
            if (samConstructor != null) {
                consume(task, group, candidate(task, level, samConstructor, null, null, Substitution.EMPTY));
            }
        }

        /** As FIR's ScopeBasedTowerLevel.consumeCallableCandidate. */
        void consumeScopeMember(Task task, Level level, TowerGroup group, ScopeLevel s, TowerScope.ScopeMember m) {
            if (s.hidesMembersOnly() && !Annotations.has(m.symbol(), "HidesMembers")) return;
            boolean receiverExpected = s.hidesMembersOnly() || s.extension() != null;
            if ((m.symbol().signature().receiver() != null) != receiverExpected) return;
            TExpr dispatch = m.importedFromObject() == null ? null
                    : new TExpr.ObjectValue(level.info().pos(), types.defaultType(m.importedFromObject()),
                    m.importedFromObject());
            consume(task, group, candidate(task, level, m.symbol(), dispatch, s.extension(), m.substitution()));
        }

        /** A class or object named as a value: the object, or the class's companion object. */
        void consumeClassifier(Task task, Level level, TowerGroup group, ClassifierSymbol classifier) {
            if (classifier instanceof TypeParamSymbol) return;
            Candidate c = candidate(task, level, classifier, null, null, Substitution.EMPTY);
            ClassSymbol cls = expandedClass(classifier);
            ClassSymbol object = cls == null ? null : cls.isObject() ? cls : cls.companion();
            if (object != null) {
                c.valueType = types.defaultType(object);
            } else {
                c.addDiagnostic(Applicability.K2_NO_COMPANION_OBJECT, classifier.name() + " has no companion object");
            }
            consume(task, group, c);
        }

        void consume(Task task, TowerGroup group, Candidate c) {
            c.group = group;
            task.collector.consume(group, c, stages.process(c, ctx));
        }

        /** As FIR's CandidateFactory.createCandidate. */
        Candidate candidate(Task task, Level level, Symbol symbol, TExpr dispatch, TExpr extension,
                            Substitution memberSubstitution) {
            CallInfo info = level.info();
            ConstraintSystem system = new ConstraintSystem(ts);
            // A reference's candidates each have a copy of the containing call's system, as FIR's createForCallableReferences.
            if (reference != null) system.addOtherSystem(reference.baseSystem());
            if (baseSystem != null) system.addOtherSystem(baseSystem);
            for (CallInfo.Argument a : info.arguments()) ArgumentChecker.addSubsystemFromArgument(system, a.value());
            Candidate c = new Candidate(symbol, info.pos(), system, info, info.argumentValues());
            c.reference = reference;
            c.dispatchReceiver = dispatch;
            c.extensionReceiver = extension;
            c.explicitReceiverKind = level.kind();
            c.memberSubstitution = memberSubstitution;
            c.invokeReceiver = task.invokeReceiver;
            c.implicitValues = () -> implicitValues(info.pos());
            c.builder = reference != null ? w -> buildReference(w, c) : w -> build(w, c);
            if (info.origin().isOperator() && (!(symbol instanceof FunctionSymbol)
                    || task.invokeReceiver != null && !(task.invokeReceiver.symbol instanceof FunctionSymbol))) {
                c.addDiagnostic(Applicability.K2_NOT_FUNCTION_AS_OPERATOR, symbol + " is not a function");
            }
            return c;
        }
    }

    // ==== Results ====

    /** The checked form of a completed call. */
    private TExpr build(CompletionWriter w, Candidate c) {
        TExpr dispatch = c.dispatchReceiver == null ? null : w.write(c.dispatchReceiver);
        TExpr extension = c.extensionReceiver == null ? null : w.write(c.extensionReceiver);
        boolean superCall = c.call.explicitReceiver() instanceof CallInfo.SuperReceiver
                && c.explicitReceiverKind == Candidate.ExplicitReceiverKind.DISPATCH;
        return switch (c.symbol) {
            case FunctionSymbol f -> new TExpr.Call(c.pos, w.callType(c, f.returnType()), f, w.typeArguments(c),
                    dispatch, extension, contextArguments(w, c), arguments(w, c, f), superCall, c.call.origin());
            case PropertySymbol p -> new TExpr.PropertyGet(c.pos, w.callType(c, p.returnType()), p,
                    w.typeArguments(c), dispatch, extension, contextArguments(w, c), superCall);
            default -> new TExpr.ObjectValue(c.pos, c.valueType, ((KType.ClassType) c.valueType).symbol());
        };
    }

    private TExpr buildDelegatingCall(CompletionWriter w, Candidate c) {
        FunctionSymbol constructor = (FunctionSymbol) c.symbol;
        TExpr outer = c.dispatchReceiver == null ? null : w.write(c.dispatchReceiver);
        return new TExpr.DelegatingConstructorCall(c.pos, types.unit(), constructor, w.typeArguments(c), outer,
                arguments(w, c, constructor));
    }

    /** The checked form of a reference whose callable is completed with the call it is an argument of. */
    private TExpr buildReference(CompletionWriter w, Candidate c) {
        TExpr dispatch = c.dispatchReceiver == null || CallableReferenceExpression.isUnboundReceiver(c.dispatchReceiver)
                ? null : w.write(c.dispatchReceiver);
        TExpr extension = c.extensionReceiver == null || CallableReferenceExpression.isUnboundReceiver(c.extensionReceiver)
                ? null : w.write(c.extensionReceiver);
        KType type = TypeApproximator.approximateToSuperTypeOrSelf(ts, w.substitute(c.referenceType),
                TypeApproximator.Config.INTERMEDIATE_APPROXIMATION_AFTER_COMPLETION);
        return new TExpr.CallableReference(c.pos, type, c.callable(), w.typeArguments(c), dispatch, extension,
                contextArguments(w, c), c.adaptation);
    }

    /** The context arguments, but those of an invoke, which are its first arguments. */
    private static List<TExpr> contextArguments(CompletionWriter w, Candidate c) {
        if (c.contextArgumentsOfInvoke > 0) return List.of();
        return c.contextArguments.stream().map(w::write).toList();
    }

    /**
     * The arguments in source order, those of a vararg parameter together
     * where the first of them is; after the context arguments of an invoke.
     */
    private List<TExpr.Argument> arguments(CompletionWriter w, Candidate c, FunctionSymbol f) {
        List<ValueParamSymbol> params = f.signature().params();
        List<CallInfo.Argument> args = c.call.arguments();
        List<TExpr.Argument> result = new ArrayList<>();
        for (int p = 0; p < c.contextArgumentsOfInvoke; p++) {
            result.add(new TExpr.Argument(p, w.write(c.contextArguments.get(p))));
        }
        int varargIndex = -1;
        List<TExpr.VarargElement> elements = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            int p = c.argumentMapping[i];
            ValueParamSymbol param = params.get(p);
            CallInfo.Argument arg = args.get(i);
            SamResolver.SamConversion sam = c.samConversions == null ? null : c.samConversions.get(arg.value());
            TExpr value = w.writeArgument(arg.value(),
                    c.substitute(sam != null ? sam.functionType() : stages.parameterType(param, arg)));
            if (sam != null) value = new TExpr.SamConversion(value.pos(), w.callType(c, sam.samType()), value);
            if (param.isVararg()) {
                if (varargIndex < 0) {
                    varargIndex = result.size();
                    result.add(null);
                }
                elements.add(new TExpr.VarargElement(value, arg.spread() || arg.name() != null));
            } else {
                result.add(new TExpr.Argument(p, value));
            }
        }
        for (int p = 0; p < params.size(); p++) {
            if (!params.get(p).isVararg()) continue;
            TExpr.Vararg vararg = new TExpr.Vararg(c.pos, w.callType(c, types.varargArrayType(params.get(p).type())),
                    elements);
            if (varargIndex >= 0) {
                result.set(varargIndex, new TExpr.Argument(p, vararg));
            } else {
                result.add(new TExpr.Argument(p, vararg));
            }
        }
        return result;
    }

    // ==== Helpers ====

    private static ClassSymbol expandedClass(ClassifierSymbol c) {
        return switch (c) {
            case ClassSymbol cls -> cls;
            case TypeAliasSymbol a when Types.lowerBound(a.expansion()) instanceof KType.ClassType t -> t.symbol();
            default -> null;
        };
    }

    private static boolean isExtensionFunctionType(KType type) {
        return Types.lowerBound(type) instanceof KType.ClassType t && t.extensionFunction();
    }

    /** The value of a receiver, as passed to the invoke of an extension function type. */
    private static TExpr receiverValue(CallInfo.Receiver receiver) {
        return switch (receiver) {
            case CallInfo.ValueReceiver v -> v.value();
            case CallInfo.Qualifier q -> q.value();
            case CallInfo.SuperReceiver s -> s.thisValue();
        };
    }

    /** {@code super}: the instance, with the type of the supertype whose members it calls. */
    private static TExpr superValue(CallInfo.SuperReceiver s) {
        return s.thisValue() instanceof TExpr.This t
                ? new TExpr.This(t.pos(), s.supertype(), t.owner(), false) : s.thisValue();
    }

    private static CallInfo withReceiverAsArgument(CallInfo info, TExpr receiver) {
        List<CallInfo.Argument> args = new ArrayList<>();
        args.add(new CallInfo.Argument(receiver, null, false, false));
        args.addAll(info.arguments());
        return new CallInfo(info.kind(), info.name(), info.explicitReceiver(), List.copyOf(args), info.typeArgs(),
                info.origin(), info.isImplicitInvoke(), info.pos());
    }
}
