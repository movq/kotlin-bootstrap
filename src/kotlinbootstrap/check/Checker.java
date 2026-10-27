package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.ast.Node;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.resolve.ResolveException;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeSystem;

/**
 * Checks the bodies of the declarations of the Kotlin sources: function
 * bodies, property initializers and accessors. Declarations without a
 * declared type get theirs from their bodies, on demand, through
 * {@link Resolver.BodyTyper}. Each body is checked once; the first error
 * stops checking it.
 */
public final class Checker {
    private final Resolver resolver;
    private final TypeSystem ts;
    private final SyntheticCalls synthetic;
    /** Checked bodies by AST node: a {@link CheckedFunction} or, for an initializer, a {@link TExpr}. */
    private final Map<Object, Object> results = new HashMap<>();
    private final Map<Object, RuntimeException> failures = new HashMap<>();

    public Checker(Resolver resolver) {
        this.resolver = resolver;
        this.ts = new TypeSystem(resolver.table());
        this.synthetic = new SyntheticCalls(ts, new ArgumentChecker(ts));
        resolver.setBodyTyper(this::inferredType);
    }

    TypeSystem ts() {
        return ts;
    }

    Resolver resolver() {
        return resolver;
    }

    SyntheticCalls synthetic() {
        return synthetic;
    }

    // ==== All declarations ====

    /** @param failures the first error of each declaration that could not be checked */
    public record Report(int declarations, List<RuntimeException> failures) {
    }

    public Report checkAll() {
        List<CallableSymbol> callables = new ArrayList<>();
        Set<String> packages = new LinkedHashSet<>();
        for (FileContext file : resolver.files()) packages.add(file.packageName());
        for (String pkg : packages) collect(resolver.table().packageScope(pkg), callables);
        for (ClassifierSymbol c : resolver.table().kotlinClassifiers().values()) {
            if (c instanceof KotlinClassSymbol cls && !isExpect(cls)) collect(cls.members(), callables);
        }
        int count = 0;
        List<RuntimeException> errors = new ArrayList<>();
        for (CallableSymbol c : callables) {
            if (resolver.context(c) == null) continue;
            count++;
            try {
                check(c);
            } catch (RuntimeException e) {
                errors.add(e);
            }
        }
        return new Report(count, errors);
    }

    private static void collect(MemberScope scope, List<CallableSymbol> out) {
        scope.functions().values().forEach(out::addAll);
        scope.properties().values().forEach(out::addAll);
    }

    private static boolean isExpect(ClassSymbol cls) {
        for (ClassSymbol c = cls; c != null; c = c.outer()) {
            if (c.has(Modifier.EXPECT)) return true;
        }
        return false;
    }

    /** Checks the bodies of a declaration. */
    public void check(CallableSymbol symbol) {
        switch (symbol) {
            case FunctionSymbol f when f.source() instanceof Decl.Function d && d.body() != null -> function(f, d);
            case PropertySymbol p when p.source() instanceof Decl.Property d -> {
                if (d.initializer() != null) initializer(p, d);
                if (d.delegate() != null) throw error(p, d.delegate().pos(), "Delegated properties are not supported yet");
                if (d.getter() != null && d.getter().body() != null) accessor(p, d, d.getter());
                if (d.setter() != null && d.setter().body() != null) accessor(p, d, d.setter());
            }
            default -> {
            }
        }
    }

    // ==== Inferred types ====

    private KType inferredType(CallableSymbol symbol) {
        KType type = switch (symbol) {
            case FunctionSymbol f when f.source() instanceof Decl.Function d && d.body() != null ->
                    returnedValue(function(f, d)).type();
            case PropertySymbol p when p.source() instanceof Decl.Property d && d.initializer() != null ->
                    initializer(p, d).type();
            case PropertySymbol p when p.source() instanceof Decl.Property d && d.getter() != null
                    && d.getter().body() != null && !(d.getter().body() instanceof Expr.Block) ->
                    returnedValue(accessor(p, d, d.getter())).type();
            default -> throw error(symbol, symbol.source() instanceof Node n ? n.pos() : 0,
                    "Cannot infer the type of " + symbol);
        };
        return approximateDeclarationType(type, symbol);
    }

    /** The value of a function with an expression body. */
    private static TExpr returnedValue(CheckedFunction function) {
        return ((TExpr.Return) function.body()).value();
    }

    /**
     * The type of a declaration as inferred from its body, approximated as
     * kotlinc's approximateDeclarationType: local and anonymous types are
     * not exposed by declarations visible outside their file.
     */
    private KType approximateDeclarationType(KType type, CallableSymbol symbol) {
        Resolver.DeclarationContext dc = resolver.context(symbol);
        boolean containerIsLocal = dc.cls() != null && dc.cls().isLocal();
        boolean approximateLocalTypes = !containerIsLocal && switch (symbol.visibility()) {
            case PUBLIC, PROTECTED, INTERNAL -> true;
            case PRIVATE -> symbol.has(Modifier.INLINE);
            case PACKAGE -> false;
        };
        TypeApproximator.Config config = approximateLocalTypes
                ? TypeApproximator.Config.PUBLIC_DECLARATION_APPROXIMATE_LOCAL_AND_ANONYMOUS
                : TypeApproximator.Config.PUBLIC_DECLARATION_SAVE_ANONYMOUS;
        return TypeApproximator.approximateToSuperTypeOrSelf(ts, type, config);
    }

    // ==== Bodies ====

    private CheckedFunction function(FunctionSymbol f, Decl.Function d) {
        return memo(d, () -> {
            Resolver.DeclarationContext dc = resolver.context(f);
            BodyChecker checker = checker(dc);
            KType returnType = Resolver.hasDeclaredType(f) ? f.returnType() : null;
            List<LocalVariable> params = new ArrayList<>();
            LocalScope scope = new LocalScope();
            for (ValueParamSymbol p : f.signature().params()) {
                KType type = p.isVararg() ? resolver.types().varargArrayType(p.type()) : p.type();
                LocalVariable v = new LocalVariable(p.name(), type, LocalVariable.Kind.PARAMETER, false, p);
                params.add(v);
                scope.add(v);
            }
            // TODO: default values, context parameters
            KType receiver = f.signature().receiver();
            CheckedFunction function = new CheckedFunction(f, f.name(), receiver, List.of(), params, returnType);
            Context ctx = context(dc);
            if (receiver != null) ctx = ctx.withElement(new Context.ImplicitReceiver(receiver, f, f.name()));
            ctx = ctx.withLocalScope(scope).withFunction(new Context.FunctionFrame(function, f.name(), false, null));
            function.setBody(body(checker, function, d.body(), ctx));
            return function;
        });
    }

    private TExpr initializer(PropertySymbol p, Decl.Property d) {
        return memo(d.initializer(), () -> {
            Resolver.DeclarationContext dc = resolver.context(p);
            Mode mode = Resolver.hasDeclaredType(p) ? Mode.expecting(p.returnType()) : Mode.INDEPENDENT;
            return checker(dc).expr(d.initializer(), context(dc).withLocalScope(new LocalScope()), mode);
        });
    }

    private CheckedFunction accessor(PropertySymbol p, Decl.Property d, Decl.Accessor accessor) {
        return memo(accessor, () -> {
            Resolver.DeclarationContext dc = resolver.context(p);
            BodyChecker checker = checker(dc);
            // The getter infers the property's type if nothing else gives it.
            boolean typeKnown = Resolver.hasDeclaredType(p) || d.initializer() != null || d.delegate() != null;
            KType propertyType = typeKnown || !accessor.isGetter() ? p.returnType() : null;
            List<LocalVariable> params = new ArrayList<>();
            LocalScope scope = new LocalScope();
            if (!accessor.isGetter()) {
                Decl.Param param = accessor.param();
                KType type = param.type() != null ? checker.resolveType(param.type(), context(dc)) : propertyType;
                LocalVariable v = new LocalVariable(param.name(), type, LocalVariable.Kind.PARAMETER, false, null);
                params.add(v);
                scope.add(v);
            }
            KType receiver = p.signature().receiver();
            KType returnType = accessor.isGetter() ? propertyType : checker.unit();
            CheckedFunction function = new CheckedFunction(p, null, receiver, List.of(), params, returnType);
            Context ctx = context(dc);
            if (receiver != null) ctx = ctx.withElement(new Context.ImplicitReceiver(receiver, p, p.name()));
            ctx = ctx.withLocalScope(scope).withFunction(new Context.FunctionFrame(function, null, false, null));
            function.setBody(body(checker, function, accessor.body(), ctx));
            return function;
        });
    }

    /**
     * A function body: a block, or an expression, which is checked as if
     * returned, with the declared return type expected if any.
     */
    private TExpr body(BodyChecker checker, CheckedFunction function, Expr body, Context ctx) {
        if (body instanceof Expr.Block b) return checker.block(b.pos(), b.statements(), ctx, Mode.INDEPENDENT);
        KType returnType = function.returnType();
        TExpr value = checker.expr(body, ctx, returnType != null ? Mode.expecting(returnType) : Mode.INDEPENDENT);
        return new TExpr.Return(body.pos(), ts.nothing(), function, value);
    }

    private BodyChecker checker(Resolver.DeclarationContext dc) {
        return new BodyChecker(this, dc.file().file().source());
    }

    /**
     * The context of a declaration's body: the implicit receivers of the
     * classes it is in, outermost first. The companion object comes before
     * its class, which takes priority.
     */
    private Context context(Resolver.DeclarationContext dc) {
        List<Context.TowerElement> elements = new ArrayList<>();
        if (dc.cls() != null) addReceivers(dc.cls(), true, elements);
        return new Context(dc.file(), dc.scope(), List.of(), List.copyOf(elements), null, null);
    }

    // TODO: the full tower: static scopes of classes and their supertypes' companions
    private void addReceivers(ClassSymbol cls, boolean withInstance, List<Context.TowerElement> elements) {
        if (cls.outer() != null) addReceivers(cls.outer(), withInstance && cls.isInner(), elements);
        ClassSymbol companion = cls.companion();
        if (companion != null) {
            elements.add(new Context.ImplicitReceiver(resolver.types().defaultType(companion), companion,
                    companion.name()));
        }
        // A companion object is a receiver already as its outer class's companion.
        boolean added = !elements.isEmpty() && elements.getLast() instanceof Context.ImplicitReceiver r && r.owner() == cls;
        if ((withInstance || cls.isObject()) && !added) {
            elements.add(new Context.ImplicitReceiver(resolver.types().defaultType(cls), cls, cls.name()));
        }
    }

    // ==== Results ====

    /** The result for the node, computed once; a failure is remembered too. */
    @SuppressWarnings("unchecked")
    private <T> T memo(Object node, Supplier<T> compute) {
        RuntimeException failure = failures.get(node);
        if (failure != null) throw failure;
        Object result = results.get(node);
        if (result != null) return (T) result;
        try {
            T value = compute.get();
            results.put(node, value);
            return value;
        } catch (CheckException | ResolveException e) {
            failures.put(node, e);
            throw e;
        }
    }

    private CheckException error(CallableSymbol symbol, int pos, String message) {
        Resolver.DeclarationContext dc = resolver.context(symbol);
        return new CheckException(dc == null ? null : dc.file().file().source(), pos, message);
    }
}
