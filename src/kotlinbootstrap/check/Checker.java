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
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;

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
    private final ArgumentChecker arguments;
    private final SyntheticCalls synthetic;
    /** Checked bodies by AST node: a {@link CheckedFunction} or, for an initializer, a {@link TExpr}. */
    private final Map<Object, Object> results = new HashMap<>();
    private final Map<Object, RuntimeException> failures = new HashMap<>();
    private final MemberScopes memberScopes;
    private final ResolutionStages stages;
    private final OverloadResolver overloads;
    private final Map<FileContext, List<Context.TowerElement>> importingScopes = new HashMap<>();
    private final Map<ClassSymbol, ClassContexts> classContexts = new HashMap<>();
    /** The direct subclasses of the sealed classes of the sources; computed on first use. */
    private Map<ClassSymbol, List<ClassSymbol>> sealedInheritors;
    private final Map<FunctionSymbol, Contract> contracts = new HashMap<>();

    public Checker(Resolver resolver) {
        this.resolver = resolver;
        this.ts = new TypeSystem(resolver.table());
        this.arguments = new ArgumentChecker(ts);
        this.synthetic = new SyntheticCalls(ts, arguments);
        this.memberScopes = new MemberScopes(ts, resolver.types());
        this.stages = new ResolutionStages(resolver.types(), arguments, new VisibilityChecker(resolver));
        this.overloads = new OverloadResolver(ts, memberScopes, stages);
        resolver.setBodyTyper(this::inferredType);
    }

    TypeSystem ts() {
        return ts;
    }

    Resolver resolver() {
        return resolver;
    }

    ArgumentChecker arguments() {
        return arguments;
    }

    SyntheticCalls synthetic() {
        return synthetic;
    }

    MemberScopes memberScopes() {
        return memberScopes;
    }

    ResolutionStages stages() {
        return stages;
    }

    OverloadResolver overloads() {
        return overloads;
    }

    // ==== All declarations ====

    /** @param failures the first error of each declaration that could not be checked */
    public record Report(int declarations, List<Failure> failures) {
    }

    public record Failure(CallableSymbol declaration, RuntimeException error) {
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
        List<Failure> errors = new ArrayList<>();
        for (CallableSymbol c : callables) {
            if (resolver.context(c) == null) continue;
            count++;
            try {
                check(c);
            } catch (RuntimeException e) {
                errors.add(new Failure(c, e));
            }
        }
        return new Report(count, errors);
    }

    /** The direct subclasses of a sealed class, which must all be in the sources. */
    List<ClassSymbol> sealedInheritors(ClassSymbol sealed) {
        if (sealedInheritors == null) {
            sealedInheritors = new HashMap<>();
            for (ClassifierSymbol c : resolver.table().kotlinClassifiers().values()) {
                if (!(c instanceof ClassSymbol cls)) continue;
                for (KType st : cls.supertypes()) {
                    if (st instanceof KType.ClassType t && t.symbol().modality() == Modality.SEALED) {
                        sealedInheritors.computeIfAbsent(t.symbol(), k -> new ArrayList<>()).add(cls);
                    }
                }
            }
        }
        return sealedInheritors.getOrDefault(sealed, List.of());
    }

    /** The contract a function declares; {@link Contract#NONE} if none. */
    Contract contract(FunctionSymbol f) {
        if (!(f.source() instanceof Decl.Function d)) return Contract.NONE;
        return contracts.computeIfAbsent(f, k -> {
            Resolver.DeclarationContext dc = resolver.context(f);
            if (dc == null) return Contract.NONE;
            // Types with the function's type parameters would need the call's type arguments.
            return Contract.of(d, ref -> {
                KType t = resolver.types().resolve(ref, dc.scope(), dc.file());
                return Types.contains(t, c -> c instanceof KType.TypeParamType p && f.typeParams().contains(p.symbol()))
                        ? null : t;
            });
        });
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
            LocalScope scope = dc.cls() != null && p.owner() == dc.cls() ? primaryConstructorScope(dc.cls())
                    : new LocalScope();
            return checker(dc).expr(d.initializer(), context(dc).withLocalScope(scope), mode);
        });
    }

    /** The parameters of a class's primary constructor, which the initializers of its properties see. */
    private LocalScope primaryConstructorScope(ClassSymbol cls) {
        LocalScope scope = new LocalScope();
        if (!(cls instanceof KotlinClassSymbol k) || k.decl().primaryConstructor() == null) return scope;
        for (FunctionSymbol c : cls.members().constructors()) {
            if (c.source() != k.decl().primaryConstructor()) continue;
            for (ValueParamSymbol p : c.signature().params()) {
                KType type = p.isVararg() ? resolver.types().varargArrayType(p.type()) : p.type();
                scope.add(new LocalVariable(p.name(), type, LocalVariable.Kind.PARAMETER, false, p));
            }
        }
        return scope;
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
            if (propertyType != null) scope.add(LocalVariable.backingField(p, propertyType));
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

    /** The context of a declaration's body: the tower of the file and the classes it is in. */
    private Context context(Resolver.DeclarationContext dc) {
        List<Context.TowerElement> elements = dc.cls() != null ? classContexts(dc.cls()).regular() : importing(dc.file());
        return new Context(dc.file(), dc.scope(), List.of(), elements, null, null, dc.cls());
    }

    // ==== Towers ====

    /** The importing scopes of a file, outermost first. */
    List<Context.TowerElement> importing(FileContext file) {
        return importingScopes.computeIfAbsent(file, f -> ImportingScopes.of(f, resolver, memberScopes).stream()
                .map(s -> (Context.TowerElement) new Context.ScopeElement(s, null)).toList());
    }

    /**
     * The towers in a class body, outermost first, as FIR's
     * FirRegularTowerDataContexts: for members; for nested classes, which
     * have no instance of the class, unless it is an object; and for the
     * companion object, which is not its own receiver as the companion.
     */
    private record ClassContexts(List<Context.TowerElement> regular, List<Context.TowerElement> forNestedClasses,
                                 List<Context.TowerElement> forCompanionObject) {
    }

    /** As FIR's withScopesForClass and collectTowerDataElementsForClass. */
    private ClassContexts classContexts(ClassSymbol cls) {
        ClassContexts cached = classContexts.get(cls);
        if (cached != null) return cached;
        List<Context.TowerElement> base;
        if (cls.outer() == null) {
            if (cls.isLocal()) throw new IllegalStateException("Local classes are not supported yet: " + cls);
            base = importing(((KotlinClassSymbol) cls).file());
        } else {
            ClassContexts outer = classContexts(cls.outer());
            base = cls.isInner() ? outer.regular()
                    : cls.has(Modifier.COMPANION) ? outer.forCompanionObject() : outer.forNestedClasses();
        }
        // Superclasses' statics and companions, the nearest innermost.
        List<Context.TowerElement> supers = new ArrayList<>();
        for (ClassSymbol sup : superclasses(cls)) {
            supers.add(new Context.ScopeElement(new TowerScope.ClassStatics(sup, memberScopes), sup));
            ClassSymbol companion = sup.companion();
            if (companion != null) supers.add(receiver(companion, null));
        }
        List<Context.TowerElement> withSupers = new ArrayList<>(base);
        withSupers.addAll(supers.reversed());

        ClassSymbol companion = cls.companion();
        List<Context.TowerElement> staticScopes = new ArrayList<>();
        if (companion != null) {
            staticScopes.add(new Context.ScopeElement(new TowerScope.ClassStatics(companion, memberScopes), null));
        }
        staticScopes.add(new Context.ScopeElement(new TowerScope.ClassStatics(cls, memberScopes), cls));

        List<Context.TowerElement> statics = new ArrayList<>(withSupers);
        statics.addAll(staticScopes);
        List<Context.TowerElement> staticsAndCompanion = new ArrayList<>(withSupers);
        if (companion != null) staticsAndCompanion.add(receiver(companion, null));
        staticsAndCompanion.addAll(staticScopes);
        List<Context.TowerElement> regular = new ArrayList<>(staticsAndCompanion);
        regular.add(receiver(cls, cls.name()));

        boolean singleton = cls.isObject() || cls.kind() == ClassKind.ENUM_ENTRY;
        ClassContexts result = new ClassContexts(List.copyOf(regular),
                singleton ? List.copyOf(regular) : List.copyOf(staticsAndCompanion), List.copyOf(statics));
        classContexts.put(cls, result);
        return result;
    }

    private Context.ImplicitReceiver receiver(ClassSymbol cls, String label) {
        return new Context.ImplicitReceiver(resolver.types().defaultType(cls), cls, label);
    }

    /** The superclasses, nearest first, not including interfaces (FIR's lookupSuperTypes without interfaces). */
    private static List<ClassSymbol> superclasses(ClassSymbol cls) {
        List<ClassSymbol> result = new ArrayList<>();
        for (ClassSymbol c = cls; c != null; ) {
            ClassSymbol next = null;
            for (KType st : c.supertypes()) {
                if (st instanceof KType.ClassType t && !t.symbol().isInterface() && !result.contains(t.symbol())) {
                    next = t.symbol();
                    break;
                }
            }
            if (next != null) result.add(next);
            c = next;
        }
        return result;
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
