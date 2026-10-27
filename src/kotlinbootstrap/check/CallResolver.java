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

    private static final Set<String> HIDES_MEMBERS_NAMES = Set.of("forEach", "addSuppressed");

    CallResolver(Checker checker, CallCompleter completer) {
        this.ts = checker.ts();
        this.types = checker.resolver().types();
        this.scopes = checker.memberScopes();
        this.stages = checker.stages();
        this.overloads = checker.overloads();
        this.checker = checker;
        this.completer = completer;
    }

    /**
     * The candidates of a call: if successful, the most specific of the
     * applicable ones (more than one if ambiguous); otherwise the least bad.
     */
    record Result(List<Candidate> candidates, boolean success) {
    }

    Result resolve(CallInfo info, Context ctx) {
        Resolution resolution = new Resolution(ctx);
        resolution.enqueueTasks(info);
        resolution.run();
        return resolution.reduce();
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
        final Collector main = new Collector();
        final PriorityQueue<Entry> queue = new PriorityQueue<>(
                Comparator.comparing(Entry::group).thenComparingLong(Entry::seq));
        long seq;

        Resolution(Context ctx) {
            this.ctx = ctx;
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

        void enqueue(Task task) {
            if (task.next < task.levels.size()) {
                queue.add(new Entry(task, task.group(task.levels.get(task.next)), seq++));
            }
        }

        Result reduce() {
            if (!main.isSuccess()) return new Result(List.copyOf(main.candidates), false);
            return new Result(List.copyOf(overloads.chooseMaximallySpecific(new LinkedHashSet<>(main.candidates))),
                    true);
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
                // Not through a type alias.
                if (cls == q.classifier()) {
                    t.levels.add(new Level(group, new ScopeLevel(new NestedClassifiers(cls), null), info,
                            Candidate.ExplicitReceiverKind.NONE));
                }
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
                    case Context.ImplicitReceiver r -> onImplicitReceiver.accept(r.expr(info.pos()), parent.implicit(depth));
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
                        boolean isExtension = isExtensionFunctionType(v.type());
                        if (builtinMode && !isExtension) return;
                        TExpr receiver = new TExpr.LocalGet(info.pos(), v.type(), v);
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
                        withReceiverAsArgument(info, r.expr(info.pos())), Candidate.ExplicitReceiverKind.DISPATCH));
            }
        }

        // ==== Processing levels ====

        void process(Task task, Level level, TowerGroup group) {
            CallInfo info = level.info();
            switch (level.level()) {
                case MemberLevel m -> processMembers(task, level, group, m);
                case ScopeLevel s -> {
                    if (info.kind() == CallInfo.Kind.FUNCTION) {
                        processConstructors(task, level, group, s);
                        for (TowerScope.ScopeMember m : s.scope().functions(info.name())) {
                            consumeScopeMember(task, level, group, s, m);
                        }
                    } else {
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
            if (info.kind() == CallInfo.Kind.FUNCTION) {
                // Constructors of inner classes, as members.
                List<MemberScopes.Inner> inner = scopes.innerClasses(type, info.name());
                if (!inner.isEmpty() && m.extension() == null) {
                    MemberScopes.Inner first = inner.getFirst();
                    for (FunctionSymbol k : first.symbol().members().constructors()) {
                        consume(task, group, candidate(task, level, k, m.dispatch(), null, first.substitution()));
                    }
                }
            }
            List<MemberScopes.Member> members = info.kind() == CallInfo.Kind.FUNCTION
                    ? scopes.functions(type, info.name()) : scopes.properties(type, info.name());
            for (MemberScopes.Member member : members) {
                if ((member.symbol().signature().receiver() != null) != (m.extension() != null)) continue;
                consume(task, group, candidate(task, level, member.symbol(), m.dispatch(), m.extension(),
                        member.substitution()));
            }
            if (info.kind() == CallInfo.Kind.VARIABLE && m.extension() == null) {
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
                    for (FunctionSymbol k : cls.members().constructors()) {
                        consume(task, group, candidate(task, level, k, null, null, Substitution.EMPTY));
                    }
                }
                case TypeAliasSymbol alias when Types.lowerBound(alias.expansion()) instanceof KType.ClassType expanded -> {
                    Substitution toExpansion = Substitution.of(expanded.symbol().typeParams(), expanded.args());
                    for (FunctionSymbol k : expanded.symbol().members().constructors()) {
                        Candidate c = candidate(task, level, k, null, null, toExpansion);
                        c.typeParams = alias.typeParams();
                        c.typeAlias = alias;
                        consume(task, group, c);
                    }
                }
                case null, default -> {
                }
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
            for (CallInfo.Argument a : info.arguments()) ArgumentChecker.addSubsystemFromArgument(system, a.value());
            Candidate c = new Candidate(symbol, info.pos(), system, info, info.argumentValues());
            c.dispatchReceiver = dispatch;
            c.extensionReceiver = extension;
            c.explicitReceiverKind = level.kind();
            c.memberSubstitution = memberSubstitution;
            c.invokeReceiver = task.invokeReceiver;
            c.builder = w -> build(w, c);
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
                    dispatch, extension, List.of(), arguments(w, c, f), superCall, c.call.origin());
            case PropertySymbol p -> new TExpr.PropertyGet(c.pos, w.callType(c, p.returnType()), p,
                    w.typeArguments(c), dispatch, extension, superCall);
            default -> new TExpr.ObjectValue(c.pos, c.valueType, ((KType.ClassType) c.valueType).symbol());
        };
    }

    /** The arguments in source order, those of a vararg parameter together where the first of them is. */
    private List<TExpr.Argument> arguments(CompletionWriter w, Candidate c, FunctionSymbol f) {
        List<ValueParamSymbol> params = f.signature().params();
        List<CallInfo.Argument> args = c.call.arguments();
        List<TExpr.Argument> result = new ArrayList<>();
        int varargIndex = -1;
        List<TExpr.VarargElement> elements = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            int p = c.argumentMapping[i];
            ValueParamSymbol param = params.get(p);
            CallInfo.Argument arg = args.get(i);
            TExpr value = w.writeArgument(arg.value(), c.substitute(stages.parameterType(param, arg)));
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
