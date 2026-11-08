package kotlinbootstrap.jvm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import kotlinbootstrap.check.CheckedFunction;
import kotlinbootstrap.check.LocalVariable;
import kotlinbootstrap.check.TExpr;
import kotlinbootstrap.check.TExprs;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;

/**
 * What closures capture: the variables and receivers that lambdas, local
 * functions and local classes use from the code around them, as kotlinc's
 * closure analysis.
 */
final class Closures {
    /**
     * What a closure uses from outside.
     *
     * @param variables the variables, in order of first use
     * @param receivers the owners of the receivers ({@code this}), in order of first use
     */
    record Captures(List<LocalVariable> variables, List<Object> receivers) {
        static final Captures NONE = new Captures(List.of(), List.of());

        boolean isEmpty() {
            return variables.isEmpty() && receivers.isEmpty();
        }
    }

    /** The bodies of a local class: its functions, accessors and constructors, and its initializers. */
    record Bodies(List<CheckedFunction> functions, List<TExpr> expressions) {
        static final Bodies NONE = new Bodies(List.of(), List.of());
    }

    /** The bodies a local class has, for its captures. */
    interface ClassBodies {
        Bodies bodies(ClassSymbol cls);
    }

    private final ClassBodies classBodies;
    private final Map<Object, Captures> cache = new IdentityHashMap<>();
    private final Map<FunctionSymbol, CheckedFunction> localFunctions = new IdentityHashMap<>();
    private final Set<Object> inProgress = Collections.newSetFromMap(new IdentityHashMap<>());

    Closures(ClassBodies classBodies) {
        this.classBodies = classBodies;
    }

    /** Calls a function for each child expression, not entering function bodies. */
    static void children(TExpr e, Consumer<TExpr> f) {
        TExprs.map(e, c -> {
            f.accept(c);
            return c;
        }, t -> t);
    }

    void registerLocalFunction(FunctionSymbol f, CheckedFunction body) {
        localFunctions.put(f, body);
    }

    CheckedFunction localFunction(FunctionSymbol f) {
        return localFunctions.get(f);
    }

    /** What a lambda or local function captures. */
    Captures of(CheckedFunction f) {
        return captures(f, () -> {
            Scan scan = new Scan();
            scan.declareFunction(f);
            scan.function(f);
            return scan.result();
        });
    }

    private static boolean isNestedIn(ClassSymbol c, ClassSymbol outer) {
        for (ClassSymbol o = c.outer(); o != null; o = o.outer()) if (o == outer) return true;
        return false;
    }

    /** What a local class or object literal captures. */
    Captures of(ClassSymbol cls) {
        return captures(cls, () -> {
            Scan scan = new Scan();
            scan.declaredReceivers.add(cls);
            Bodies bodies = classBodies.bodies(cls);
            for (CheckedFunction f : bodies.functions()) {
                scan.declareFunction(f);
                scan.function(f);
            }
            for (TExpr body : bodies.expressions()) scan.expr(body);
            // Those of nested classes are among the bodies.
            scan.usedReceivers.removeIf(r -> r instanceof ClassSymbol c && isNestedIn(c, cls));
            return scan.result();
        });
    }

    private Captures captures(Object key, java.util.function.Supplier<Captures> compute) {
        Captures cached = cache.get(key);
        if (cached != null) return cached;
        // A recursive local function captures nothing more through itself.
        if (!inProgress.add(key)) return Captures.NONE;
        try {
            Captures result = compute.get();
            cache.put(key, result);
            return result;
        } finally {
            inProgress.remove(key);
        }
    }

    /**
     * The variables of a body that closures change or capture while they
     * may change: kept in Refs. Variables declared {@code var}, or
     * {@code val} without an initializer, and used by a closure.
     */
    /** The vals declared without an initializer, which a closure called in place may assign. */
    private final Set<LocalVariable> deferredVals = Collections.newSetFromMap(new IdentityHashMap<>());

    Set<LocalVariable> boxedVariables(TExpr body) {
        Set<LocalVariable> result = Collections.newSetFromMap(new IdentityHashMap<>());
        registerLocalFunctions(body);
        collectBoxed(body, result);
        return result;
    }

    /** Local functions can be called before they are declared, by each other. */
    private void registerLocalFunctions(TExpr e) {
        if (e == null) return;
        switch (e) {
            case TExpr.Lambda l -> registerLocalFunctions(l.function().body());
            case TExpr.LocalFunctionDecl d -> {
                registerLocalFunction(d.function(), d.body());
                registerLocalFunctions(d.body().body());
            }
            default -> children(e, this::registerLocalFunctions);
        }
    }

    private void collectBoxed(TExpr e, Set<LocalVariable> result) {
        if (e == null) return;
        switch (e) {
            case TExpr.Lambda l -> {
                for (LocalVariable v : of(l.function()).variables()) if (isMutable(v)) result.add(v);
                collectBoxed(l.function().body(), result);
            }
            case TExpr.LocalFunctionDecl d -> {
                registerLocalFunction(d.function(), d.body());
                for (LocalVariable v : of(d.body()).variables()) if (isMutable(v)) result.add(v);
                collectBoxed(d.body().body(), result);
                for (TExpr dv : d.body().defaultValues()) collectBoxed(dv, result);
            }
            case TExpr.LocalClassDecl d -> {
                for (LocalVariable v : of(d.cls()).variables()) if (isMutable(v)) result.add(v);
                collectBoxed(classBodies.bodies(d.cls()), result);
            }
            case TExpr.ObjectLiteral o -> {
                for (LocalVariable v : of(o.cls()).variables()) if (isMutable(v)) result.add(v);
                collectBoxed(classBodies.bodies(o.cls()), result);
            }
            case TExpr.LocalVarDecl d -> {
                if (d.initializer() == null && !d.variable().isVar()) deferredVals.add(d.variable());
                children(e, c -> collectBoxed(c, result));
            }
            default -> children(e, c -> collectBoxed(c, result));
        }
    }

    /** Whether a variable may be assigned once captured: a var, or a val declared without its value. */
    private boolean isMutable(LocalVariable v) {
        return v.isVar() || deferredVals.contains(v);
    }

    private void collectBoxed(Bodies bodies, Set<LocalVariable> result) {
        for (CheckedFunction f : bodies.functions()) {
            collectBoxed(f.body(), result);
            for (TExpr dv : f.defaultValues()) collectBoxed(dv, result);
        }
        for (TExpr e : bodies.expressions()) collectBoxed(e, result);
    }

    /** A walk over a closure's body, collecting the variables and receivers it uses but does not declare. */
    private final class Scan {
        final Set<LocalVariable> declared = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> declaredReceivers = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<LocalVariable> used = new LinkedHashSet<>();
        final Set<Object> usedReceivers = new LinkedHashSet<>();

        Captures result() {
            List<LocalVariable> variables = new ArrayList<>();
            for (LocalVariable v : used) if (!declared.contains(v)) variables.add(v);
            List<Object> receivers = new ArrayList<>();
            for (Object r : usedReceivers) if (!declaredReceivers.contains(r)) receivers.add(r);
            return new Captures(variables, receivers);
        }

        void declareFunction(CheckedFunction f) {
            declared.addAll(f.params());
            declared.addAll(f.contextParams());
            declaredReceivers.add(f);
            if (f.symbol() != null) declaredReceivers.add(f.symbol());
        }

        void function(CheckedFunction f) {
            for (TExpr dv : f.defaultValues()) expr(dv);
            expr(f.body());
        }

        void expr(TExpr e) {
            if (e == null) return;
            switch (e) {
                case TExpr.LocalGet g -> used.add(g.variable());
                case TExpr.LocalSet s -> used.add(s.variable());
                case TExpr.LocalVarDecl d -> declared.add(d.variable());
                case TExpr.LocalDelegatedVarDecl d -> declared.add(d.variable());
                case TExpr.SafeCall s -> declared.add(s.subject());
                case TExpr.Try t -> t.catches().forEach(c -> declared.add(c.param()));
                case TExpr.This t -> usedReceivers.add(t.owner());
                case TExpr.Lambda l -> {
                    declareFunction(l.function());
                    function(l.function());
                }
                case TExpr.LocalFunctionDecl d -> {
                    registerLocalFunction(d.function(), d.body());
                    declareFunction(d.body());
                    function(d.body());
                }
                case TExpr.Call c when c.function().owner() == null && localFunctions.containsKey(c.function()) -> {
                    Captures inner = of(localFunctions.get(c.function()));
                    used.addAll(inner.variables());
                    usedReceivers.addAll(inner.receivers());
                }
                case TExpr.LocalClassDecl d -> classCaptures(d.cls());
                case TExpr.ObjectLiteral o -> classCaptures(o.cls());
                default -> {
                }
            }
            switch (e) {
                case TExpr.Lambda l -> {
                }
                case TExpr.LocalFunctionDecl d -> {
                }
                default -> children(e, this::expr);
            }
        }

        private void classCaptures(ClassSymbol cls) {
            Captures inner = of(cls);
            used.addAll(inner.variables());
            usedReceivers.addAll(inner.receivers());
        }
    }
}
