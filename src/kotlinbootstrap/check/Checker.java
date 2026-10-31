package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import kotlinbootstrap.ast.Annotation;
import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.ast.NamedType;
import kotlinbootstrap.ast.Node;
import kotlinbootstrap.ast.TypeRef;
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
import kotlinbootstrap.symbols.Symbol;
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
    /**
     * Checked bodies by AST node: a {@link CheckedFunction} or, for an
     * initializer, a {@link TExpr}. By identity, as equal nodes may be in
     * different files.
     */
    private final Map<Object, Object> results = new IdentityHashMap<>();
    private final Map<Object, RuntimeException> failures = new IdentityHashMap<>();
    private final MemberScopes memberScopes;
    private final SamResolver sam;
    private final ResolutionStages stages;
    private final OverloadResolver overloads;
    private final ConstantEvaluator constants;
    private final Map<FileContext, List<Context.TowerElement>> importingScopes = new HashMap<>();
    private final LanguageSettings settings;
    private final Map<ClassSymbol, ClassContexts> classContexts = new HashMap<>();
    /** The direct subclasses of the sealed classes of the sources; computed on first use. */
    private Map<ClassSymbol, List<ClassSymbol>> sealedInheritors;
    private final Map<FunctionSymbol, Contract> contracts = new HashMap<>();
    /** Where the local classes and functions were declared, by symbol. */
    private final Map<Symbol, LocalOrigin> localOrigins = new HashMap<>();

    /**
     * Where a local class or function was declared: its bodies are checked
     * by the checker of the body it is in, in the context there, from what
     * data flow knew there.
     */
    private record LocalOrigin(BodyChecker checker, Context context, DataFlowAnalyzer.State flow) {
    }

    public Checker(Resolver resolver) {
        this(resolver, LanguageSettings.DEFAULT);
    }

    public Checker(Resolver resolver, LanguageSettings settings) {
        this.resolver = resolver;
        this.settings = settings;
        this.ts = new TypeSystem(resolver.table());
        this.arguments = new ArgumentChecker(ts);
        this.synthetic = new SyntheticCalls(ts, arguments);
        JvmMappedScope mapped = new JvmMappedScope(resolver.table());
        this.memberScopes = new MemberScopes(ts, resolver.types(), mapped);
        this.sam = new SamResolver(ts, resolver.types(), memberScopes);
        this.stages = new ResolutionStages(ts, resolver.types(), arguments, sam, mapped,
                new VisibilityChecker(resolver));
        this.overloads = new OverloadResolver(ts, memberScopes, stages);
        this.constants = new ConstantEvaluator(p -> p.source() instanceof Decl.Property d && d.initializer() != null
                ? initializer(p, d) : null);
        resolver.setBodyTyper(this::inferredType);
    }

    TypeSystem ts() {
        return ts;
    }

    LanguageSettings settings() {
        return settings;
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

    SamResolver sam() {
        return sam;
    }

    ResolutionStages stages() {
        return stages;
    }

    OverloadResolver overloads() {
        return overloads;
    }

    ConstantEvaluator constants() {
        return constants;
    }

    // ==== All declarations ====

    /** @param failures the first error of each declaration that could not be checked */
    public record Report(int declarations, List<Failure> failures) {
    }

    /** @param declaration a function or property, or a class for the bodies of {@link #checkClass} */
    public record Failure(Symbol declaration, RuntimeException error) {
    }

    public Report checkAll() {
        List<CallableSymbol> callables = new ArrayList<>();
        List<KotlinClassSymbol> classes = new ArrayList<>();
        Set<String> packages = new LinkedHashSet<>();
        for (FileContext file : resolver.files()) packages.add(file.packageName());
        for (String pkg : packages) collect(resolver.table().packageScope(pkg), callables);
        // Collecting members may enter more classifiers, e.g. of local declarations.
        for (ClassifierSymbol c : List.copyOf(resolver.table().kotlinClassifiers().values())) {
            if (c instanceof KotlinClassSymbol cls && !isExpect(cls)) {
                collect(cls.members(), callables);
                classes.add(cls);
            }
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
        for (FileContext file : resolver.files()) {
            try {
                checkFileAnnotations(file);
            } catch (RuntimeException e) {
                errors.add(new Failure(null, e));
            }
        }
        for (KotlinClassSymbol cls : classes) {
            count++;
            try {
                checkClass(cls);
            } catch (RuntimeException e) {
                errors.add(new Failure(cls, e));
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

    /** Checks the bodies of a declaration, and its annotations. */
    public void check(CallableSymbol symbol) {
        checkAnnotations(symbol);
        switch (symbol) {
            case FunctionSymbol f when f.source() instanceof Decl.Function d && d.body() != null -> function(f, d);
            case PropertySymbol p when p.source() instanceof Decl.Property d -> {
                if (d.initializer() != null && p.has(Modifier.CONST)) {
                    constValue(p);
                } else if (d.initializer() != null) {
                    initializer(p, d);
                }
                if (d.delegate() != null) delegate(p, d);
                if (d.backingField() != null && d.backingField().initializer() != null) {
                    backingFieldInitializer(p, d.backingField());
                }
                if (d.getter() != null && d.getter().body() != null) accessor(p, d, d.getter());
                if (d.setter() != null && d.setter().body() != null) accessor(p, d, d.setter());
            }
            default -> {
            }
        }
    }

    /** The value of a const val, whose initializer must be constant. */
    public ConstantValue constValue(PropertySymbol p) {
        Decl.Property d = (Decl.Property) p.source();
        return memo(p, () -> {
            TExpr initializer = initializer(p, d);
            try {
                return constants.evaluate(initializer);
            } catch (ConstantEvaluator.NotConstant e) {
                throw error(p, e.expr.pos(), "A const val's initializer must be constant: " + e.failure.message);
            }
        });
    }

    // ==== Annotations ====

    /** An annotation, checked once, in the context of what it annotates. */
    ConstantValue.Annotation checkedAnnotation(Annotation a, BodyChecker checker, Context ctx) {
        return memo(a, () -> checker.annotation(a, ctx));
    }

    /** The checked annotation; it must have been checked already. */
    public ConstantValue.Annotation checkedAnnotation(Annotation a) {
        return memo(a, () -> {
            throw new IllegalStateException("Annotation not checked: " + a);
        });
    }

    /**
     * Checks the annotations of a function or property, and of its
     * parameters and accessors, in its context, as FIR's
     * FirAnnotationArgumentsTransformer.
     */
    private void checkAnnotations(CallableSymbol symbol) {
        Resolver.DeclarationContext dc = resolver.context(symbol);
        if (dc == null) return;
        List<Annotation> annotations = new ArrayList<>();
        switch (symbol.source()) {
            case Decl.Function d -> {
                annotations.addAll(d.modifiers().annotations());
                for (Decl.Param p : d.params()) annotations.addAll(p.modifiers().annotations());
            }
            case Decl.Property d -> {
                annotations.addAll(d.modifiers().annotations());
                for (Decl.Accessor a : Arrays.asList(d.getter(), d.setter())) {
                    if (a == null) continue;
                    annotations.addAll(a.modifiers().annotations());
                    if (a.param() != null) annotations.addAll(a.param().modifiers().annotations());
                }
                if (d.backingField() != null) annotations.addAll(d.backingField().modifiers().annotations());
            }
            case null, default -> {
            }
        }
        if (annotations.isEmpty()) return;
        BodyChecker checker = checker(symbol, dc);
        Context ctx = context(symbol, dc);
        inLocalFlow(symbol, () -> {
            for (Annotation a : annotations) checkedAnnotation(a, checker, ctx);
            return null;
        });
    }

    /**
     * Checks the annotations of a class, in the context it is declared in;
     * of its constructors and their parameters and of its enum entries, in
     * its header.
     */
    private void checkClassAnnotations(KotlinClassSymbol cls) {
        Decl.Class d = cls.decl();
        LocalOrigin origin = localOrigin(cls);
        BodyChecker checker = checker(origin, cls.file());
        inLocalFlow(origin, () -> {
            if (!d.modifiers().annotations().isEmpty()) {
                Context outer = new Context(cls.file(), cls.outer() != null ? cls.outer().bodyScope(cls.isInner())
                        : origin != null ? origin.context().typeScope() : cls.file().scope(),
                        origin != null ? origin.context().locals() : List.of(), outerElements(cls), null, null,
                        cls.containingClass());
                for (Annotation a : d.modifiers().annotations()) checkedAnnotation(a, checker, outer);
            }
            Context header = classContext(cls, true);
            List<Annotation> annotations = new ArrayList<>();
            if (d.primaryConstructor() != null) {
                annotations.addAll(d.primaryConstructor().modifiers().annotations());
                for (Decl.Param p : d.primaryConstructor().params()) annotations.addAll(p.modifiers().annotations());
            }
            for (Decl member : d.members()) {
                if (!(member instanceof Decl.SecondaryConstructor c)) continue;
                annotations.addAll(c.modifiers().annotations());
                for (Decl.Param p : c.params()) annotations.addAll(p.modifiers().annotations());
            }
            for (Decl.EnumEntry e : d.enumEntries()) annotations.addAll(e.modifiers().annotations());
            for (Annotation a : annotations) checkedAnnotation(a, checker, header);
            return null;
        });
    }

    /** Checks the annotations of a file, in its importing scopes. */
    private void checkFileAnnotations(FileContext file) {
        if (file.file().annotations().isEmpty()) return;
        BodyChecker checker = new BodyChecker(this, file.file().source());
        Context ctx = new Context(file, file.scope(), List.of(), importing(file), null, null, null);
        for (Annotation a : file.file().annotations()) checkedAnnotation(a, checker, ctx);
    }

    /** The checked body of a function with one. */
    public CheckedFunction checkedFunction(FunctionSymbol f) {
        return function(f, (Decl.Function) f.source());
    }

    /** The checked delegate and accessors of a delegated property. */
    public CheckedDelegate checkedDelegate(PropertySymbol p) {
        return delegate(p, (Decl.Property) p.source());
    }

    // ==== Inferred types ====

    private KType inferredType(CallableSymbol symbol) {
        KType type = switch (symbol) {
            case FunctionSymbol f when f.source() instanceof Decl.Function d && d.body() != null ->
                    returnedValue(function(f, d)).type();
            case PropertySymbol p when p.source() instanceof Decl.Property d && d.initializer() != null ->
                    initializer(p, d).type();
            case PropertySymbol p when p.source() instanceof Decl.Property d && d.delegate() != null ->
                    delegate(p, d).getter().returnType();
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
        return memo(d, () -> inLocalFlow(f, () -> {
            Resolver.DeclarationContext dc = resolver.context(f);
            BodyChecker checker = checker(f, dc);
            KType returnType = Resolver.hasDeclaredType(f) ? f.returnType() : null;
            LocalScope scope = new LocalScope();
            List<LocalVariable> params = params(f, scope);
            List<LocalVariable> contextParams = contextParams(f, d.modifiers().contextParams(), scope);
            KType receiver = f.signature().receiver();
            CheckedFunction function = new CheckedFunction(f, f.name(), receiver, contextParams, params, returnType);
            Context ctx = withContextValues(context(f, dc), f, d.modifiers().contextParams(), contextParams);
            if (receiver != null) ctx = ctx.withElement(new Context.ImplicitReceiver(receiver, f, f.name()));
            ctx = ctx.withLocalScope(scope);
            checker.dfa().enterFunction(d);
            // All the parameters are visible in the default values, as FIR's withParameters.
            function.setDefaultValues(defaultValues(checker, f, params, ctx));
            ctx = ctx.withFunction(new Context.FunctionFrame(function, f.name(), false, null));
            function.setBody(body(checker, function, d.body(), ctx));
            checker.dfa().exitFunction();
            return function;
        }));
    }

    /** The value parameters of a function or constructor, added to the scope of its body. */
    private List<LocalVariable> params(FunctionSymbol f, LocalScope scope) {
        List<LocalVariable> params = new ArrayList<>();
        for (ValueParamSymbol p : f.signature().params()) {
            KType type = p.isVararg() ? resolver.types().varargArrayType(p.type()) : p.type();
            LocalVariable v = new LocalVariable(p.name(), type, LocalVariable.Kind.PARAMETER, false, p);
            params.add(v);
            scope.add(v);
        }
        return params;
    }

    /**
     * The default values of the parameters, null where none: each with the
     * parameter's type expected, in a data flow of its own, as FIR's
     * transformValueParameter.
     */
    private List<TExpr> defaultValues(BodyChecker checker, FunctionSymbol f, List<LocalVariable> params, Context ctx) {
        List<TExpr> result = new ArrayList<>();
        List<ValueParamSymbol> symbols = f.signature().params();
        for (int i = 0; i < symbols.size(); i++) {
            Expr value = symbols.get(i).source() instanceof Decl.Param p ? p.defaultValue() : null;
            if (value == null) {
                result.add(null);
                continue;
            }
            DataFlowAnalyzer.State before = checker.dfa().save();
            result.add(checker.expr(value, ctx, Mode.expecting(params.get(i).type())));
            checker.dfa().restore(before);
        }
        return result;
    }

    private TExpr initializer(PropertySymbol p, Decl.Property d) {
        return memo(d.initializer(), () -> inLocalFlow(p, () -> {
            Resolver.DeclarationContext dc = resolver.context(p);
            Mode mode = Resolver.hasDeclaredType(p) ? Mode.expecting(p.returnType()) : Mode.INDEPENDENT;
            LocalScope scope = dc.cls() != null && p.owner() == dc.cls() ? primaryConstructorScope(dc.cls())
                    : new LocalScope();
            return checker(p, dc).expr(d.initializer(), context(p, dc).withLocalScope(scope), mode);
        }));
    }

    /**
     * The initializer of an explicit backing field, {@code field = ...},
     * as a property's, with the field's type expected, or else the property's.
     */
    private TExpr backingFieldInitializer(PropertySymbol p, Decl.BackingField f) {
        return memo(f.initializer(), () -> inLocalFlow(p, () -> {
            Resolver.DeclarationContext dc = resolver.context(p);
            BodyChecker checker = checker(p, dc);
            Context ctx = context(p, dc);
            Mode mode = Mode.expecting(f.type() != null ? checker.resolveType(f.type(), ctx) : p.returnType());
            LocalScope scope = dc.cls() != null && p.owner() == dc.cls() ? primaryConstructorScope(dc.cls())
                    : new LocalScope();
            return checker.expr(f.initializer(), ctx.withLocalScope(scope), mode);
        }));
    }

    /** The type of a property's explicit backing field: declared, or else its initializer's; null if none. */
    KType backingFieldType(PropertySymbol p) {
        if (!(p.source() instanceof Decl.Property d) || d.backingField() == null) return null;
        Decl.BackingField f = d.backingField();
        return memo(f, () -> {
            Resolver.DeclarationContext dc = resolver.context(p);
            if (f.type() != null) return checker(p, dc).resolveType(f.type(), context(p, dc));
            if (f.initializer() == null) throw error(p, f.pos(), "An explicit backing field needs a type or initializer");
            return approximateDeclarationType(backingFieldInitializer(p, f).type(), p);
        });
    }

    private CheckedDelegate delegate(PropertySymbol p, Decl.Property d) {
        return memo(d.delegate(), () -> inLocalFlow(p, () -> {
            Resolver.DeclarationContext dc = resolver.context(p);
            KType declared = Resolver.hasDeclaredType(p) ? p.returnType() : null;
            LocalScope scope = dc.cls() != null && p.owner() == dc.cls() ? primaryConstructorScope(dc.cls())
                    : new LocalScope();
            Context delegateCtx = context(p, dc).withLocalScope(scope);
            KType receiver = p.signature().receiver();
            Context accessorCtx = context(p, dc);
            if (receiver != null) accessorCtx = accessorCtx.withElement(new Context.ImplicitReceiver(receiver, p, p.name()));
            accessorCtx = accessorCtx.withLocalScope(new LocalScope());
            KType dispatchType = dc.cls() == null ? null : resolver.types().defaultType(dc.cls());
            return new DelegatedProperties(checker(p, dc), ts).check(p, d.delegate(), declared, dc.cls(), dispatchType,
                    delegateCtx, accessorCtx, t -> approximateDeclarationType(t, p));
        }));
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
        return memo(accessor, () -> inLocalFlow(p, () -> {
            Resolver.DeclarationContext dc = resolver.context(p);
            BodyChecker checker = checker(p, dc);
            // The getter infers the property's type if nothing else gives it.
            boolean typeKnown = Resolver.hasDeclaredType(p) || d.initializer() != null || d.delegate() != null;
            KType propertyType = typeKnown || !accessor.isGetter() ? p.returnType() : null;
            List<LocalVariable> params = new ArrayList<>();
            LocalScope scope = new LocalScope();
            KType fieldType = d.backingField() != null ? backingFieldType(p) : propertyType;
            if (fieldType != null) scope.add(LocalVariable.backingField(p, fieldType));
            if (!accessor.isGetter()) {
                Decl.Param param = accessor.param();
                KType type = param.type() != null ? checker.resolveType(param.type(), context(p, dc)) : propertyType;
                LocalVariable v = new LocalVariable(param.name(), type, LocalVariable.Kind.PARAMETER, false, null);
                params.add(v);
                scope.add(v);
            }
            List<LocalVariable> contextParams = contextParams(p, d.modifiers().contextParams(), scope);
            KType receiver = p.signature().receiver();
            KType returnType = accessor.isGetter() ? propertyType : checker.unit();
            CheckedFunction function = new CheckedFunction(p, null, receiver, contextParams, params, returnType);
            Context ctx = withContextValues(context(p, dc), p, d.modifiers().contextParams(), contextParams);
            if (receiver != null) ctx = ctx.withElement(new Context.ImplicitReceiver(receiver, p, p.name()));
            ctx = ctx.withLocalScope(scope).withFunction(new Context.FunctionFrame(function, null, false, null));
            checker.dfa().enterFunction(accessor);
            function.setBody(body(checker, function, accessor.body(), ctx));
            checker.dfa().exitFunction();
            return function;
        }));
    }

    /**
     * The context parameters of a declaration, as variables in its bodies;
     * {@code _} and those of the older {@code context(Foo)} form have no name.
     */
    private static List<LocalVariable> contextParams(CallableSymbol symbol, List<NamedType> decls, LocalScope scope) {
        List<LocalVariable> result = new ArrayList<>();
        List<KType> types = symbol.signature().contextParams();
        for (int i = 0; i < decls.size(); i++) {
            String name = decls.get(i).name();
            LocalVariable v = new LocalVariable(name != null ? name : "_", types.get(i), LocalVariable.Kind.PARAMETER,
                    false, null);
            result.add(v);
            if (name != null && !name.equals("_")) scope.add(v);
        }
        return result;
    }

    /**
     * With the context parameters as values for the context parameters of
     * calls, but those of the older form, as FIR's withLabelAndReceiverType.
     */
    private static Context withContextValues(Context ctx, CallableSymbol owner, List<NamedType> decls,
                                             List<LocalVariable> params) {
        List<LocalVariable> values = new ArrayList<>();
        for (int i = 0; i < decls.size(); i++) {
            if (decls.get(i).name() != null) values.add(params.get(i));
        }
        return values.isEmpty() ? ctx : ctx.withElement(new Context.ContextValues(owner, values));
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

    private BodyChecker checker(CallableSymbol symbol, Resolver.DeclarationContext dc) {
        return checker(localOrigin(symbol, dc), dc.file());
    }

    private BodyChecker checker(LocalOrigin origin, FileContext file) {
        return origin != null ? origin.checker() : new BodyChecker(this, file.file().source());
    }

    /**
     * The context of a declaration's body: the tower of the file and the
     * classes it is in; for a local declaration, also of the body it is in.
     */
    private Context context(CallableSymbol symbol, Resolver.DeclarationContext dc) {
        LocalOrigin origin = localOrigin(symbol, dc);
        if (origin != null && dc.cls() == null) {
            Context c = origin.context();
            return new Context(dc.file(), dc.scope(), c.locals(), c.elements(), null, null, c.containingClass());
        }
        List<Context.TowerElement> elements = dc.cls() != null ? classContexts(dc.cls()).regular() : importing(dc.file());
        List<LocalScope> locals = origin != null ? origin.context().locals() : List.of();
        return new Context(dc.file(), dc.scope(), locals, elements, null, null, dc.cls());
    }

    /** Where a local function, or the innermost local class a declaration is in, was declared; null if none. */
    private LocalOrigin localOrigin(CallableSymbol symbol, Resolver.DeclarationContext dc) {
        LocalOrigin origin = localOrigins.get(symbol);
        return origin != null ? origin : localOrigin(dc.cls());
    }

    /** Where the innermost local class a class is in, or itself, was declared; null if none. */
    private LocalOrigin localOrigin(ClassSymbol cls) {
        LocalOrigin origin = null;
        for (ClassSymbol c = cls; origin == null && c != null; c = c.outer()) origin = localOrigins.get(c);
        return origin;
    }

    private <T> T inLocalFlow(CallableSymbol symbol, Supplier<T> check) {
        return inLocalFlow(localOrigin(symbol, resolver.context(symbol)), check);
    }

    /**
     * Checks a body of a local declaration in the data flow of the body it
     * is in, from where it was declared, as a lambda not called in place.
     */
    private <T> T inLocalFlow(LocalOrigin origin, Supplier<T> check) {
        if (origin == null) return check.get();
        DataFlowAnalyzer dfa = origin.checker().dfa();
        DataFlowAnalyzer.LambdaFrame frame = dfa.enterLocalDeclaration(origin.flow());
        try {
            return check.get();
        } finally {
            dfa.exitLocalDeclaration(frame);
        }
    }

    // ==== Classes ====

    /**
     * Checks the bodies of a class besides those of its members: its
     * constructors, init blocks, supertype delegates and enum entries, and
     * for an object, which has no constructor, the call of its superclass's.
     */
    public void checkClass(KotlinClassSymbol cls) {
        checkClassAnnotations(cls);
        for (FunctionSymbol k : cls.members().constructors()) checkedConstructor(k);
        if (cls.isObject()) checkedSuperCall(cls);
        for (Decl member : cls.decl().members()) {
            if (member instanceof Decl.Initializer i) checkedInitBlock(cls, i);
        }
        for (Decl.SuperTypeEntry e : cls.decl().superTypes()) {
            if (e.delegate() != null) checkedSupertypeDelegate(cls, e);
        }
        for (Decl.EnumEntry e : cls.decl().enumEntries()) checkedEnumEntry(cls, e);
    }

    /**
     * A checked constructor, as FIR's transformConstructor: the default
     * values and the delegating call in the header of the class, which has
     * no instance yet, with all the parameters; the body of a secondary
     * constructor in the class. A primary or implicit constructor calls
     * the superclass's by the supertype initializer, if any.
     */
    public CheckedFunction checkedConstructor(FunctionSymbol k) {
        return memo(k, () -> inLocalFlow(k, () -> {
            Resolver.DeclarationContext dc = resolver.context(k);
            KotlinClassSymbol cls = dc.cls();
            BodyChecker checker = checker(k, dc);
            LocalScope scope = new LocalScope();
            List<LocalVariable> params = params(k, scope);
            CheckedFunction function = new CheckedFunction(k, null, null, List.of(), params, checker.unit());
            Context header = classContext(cls, true).withLocalScope(scope);
            Decl.Class d = cls.decl();
            Object node = k.source() instanceof Decl.SecondaryConstructor s ? s
                    : Arrays.asList(d.primaryConstructor(), d.superTypes());
            checker.dfa().enterFunction(node);
            function.setDefaultValues(defaultValues(checker, k, params, header));
            List<TExpr> statements = new ArrayList<>();
            if (k.source() instanceof Decl.SecondaryConstructor s) {
                KType.ClassType constructed = s.delegation() == Decl.DelegationKind.THIS
                        ? resolver.types().defaultType(cls) : superclassType(cls);
                if (constructed != null) {
                    statements.add(checker.delegatingConstructorCall(s.pos(), constructed, s.delegationArgs(), header,
                            classContext(cls, false)));
                }
                if (s.body() != null) {
                    Context body = classContext(cls, false).withLocalScope(scope)
                            .withFunction(new Context.FunctionFrame(function, null, false, null));
                    statements.add(checker.block(s.body().pos(), s.body().statements(), body, Mode.INDEPENDENT));
                }
            } else {
                TExpr call = superCall(checker, cls, header);
                if (call != null) statements.add(call);
            }
            function.setBody(new TExpr.Block(d.pos(), checker.unit(), statements));
            checker.dfa().exitFunction();
            return function;
        }));
    }

    /** The call of the superclass's constructor from an object, which has no constructor; null if none. */
    public TExpr checkedSuperCall(KotlinClassSymbol cls) {
        return inClass(cls, cls, checker -> {
            checker.dfa().enterFunction(cls.decl().superTypes());
            TExpr call = superCall(checker, cls, classContext(cls, true));
            checker.dfa().exitFunction();
            return call;
        });
    }

    /** An init block, in the class with the primary constructor's parameters. */
    public TExpr checkedInitBlock(KotlinClassSymbol cls, Decl.Initializer i) {
        return inClass(cls, i, checker -> {
            Context ctx = classContext(cls, false).withLocalScope(primaryConstructorScope(cls));
            checker.dfa().enterInitBlock(i);
            TExpr body = checker.block(i.body().pos(), i.body().statements(), ctx, Mode.INDEPENDENT);
            checker.dfa().exitInitBlock(i);
            return body;
        });
    }

    /**
     * The delegate of a supertype, {@code I by d}, with the supertype
     * expected, in the header of the class with the primary constructor's
     * parameters, as FIR's withField.
     */
    public TExpr checkedSupertypeDelegate(KotlinClassSymbol cls, Decl.SuperTypeEntry e) {
        return inClass(cls, e.delegate(), checker -> checker.expr(e.delegate(),
                classContext(cls, true).withLocalScope(primaryConstructorScope(cls)), Mode.expecting(cls.supertype(e))));
    }

    /**
     * The call of the enum class's constructor an entry makes, in the header
     * of the class, as FIR's withEnumEntry; for an entry with a body, the
     * object literal {@code object : E(args) { body }} it is in FIR.
     */
    public TExpr checkedEnumEntry(KotlinClassSymbol cls, Decl.EnumEntry e) {
        return inClass(cls, e, checker -> {
            Context ctx = classContext(cls, true);
            if (e.body() == null) {
                return checker.delegatingConstructorCall(e.pos(), resolver.types().defaultType(cls),
                        e.args(), ctx, ctx);
            }
            Decl.Class object = enumEntryClass(cls, e);
            ClassSymbol entryClass = localClass(object, checker, ctx, null);
            return new TExpr.ObjectLiteral(e.pos(), resolver.types().defaultType(entryClass), entryClass);
        });
    }

    /** The class of an enum entry with a body: an object literal with the enum class as supertype. */
    private static Decl.Class enumEntryClass(KotlinClassSymbol cls, Decl.EnumEntry e) {
        List<TypeRef.Segment> path = new ArrayList<>();
        for (String s : cls.id().packageName().split("\\.")) {
            if (!s.isEmpty()) path.add(new TypeRef.Segment(s, List.of()));
        }
        for (String s : cls.id().relativeName().split("\\.")) path.add(new TypeRef.Segment(s, List.of()));
        Decl.SuperTypeEntry supertype = new Decl.SuperTypeEntry(e.pos(), new TypeRef.Simple(e.pos(), path),
                e.args(), null);
        return new Decl.Class(e.pos(), e.modifiers(), Decl.ClassKind.OBJECT, null, List.of(), null,
                List.of(supertype), List.of(), List.of(), e.body());
    }

    /**
     * The call of the superclass's constructor by the supertype initializer,
     * {@code : B(...)}, or else without arguments; null for none.
     */
    private TExpr superCall(BodyChecker checker, KotlinClassSymbol cls, Context header) {
        for (Decl.SuperTypeEntry e : cls.decl().superTypes()) {
            if (e.args() != null) {
                return checker.delegatingConstructorCall(e.pos(), (KType.ClassType) cls.supertype(e), e.args(), header,
                        classContext(cls, false));
            }
        }
        KType.ClassType superclass = superclassType(cls);
        return superclass == null ? null
                : checker.delegatingConstructorCall(cls.decl().pos(), superclass, List.of(), header,
                classContext(cls, false));
    }

    /**
     * The superclass whose constructor is called if none is written, as
     * FIR's transformDelegatedConstructorCall: the first supertype that is
     * a class, else Any; null for Any, and for an enum class, whose
     * constructors call Enum's implicitly.
     */
    private KType.ClassType superclassType(KotlinClassSymbol cls) {
        if (cls == ts.anyClass() || cls.kind() == ClassKind.ENUM_CLASS) return null;
        for (KType st : cls.supertypes()) {
            if (st instanceof KType.ClassType t && !t.symbol().isInterface()) return t;
        }
        return ts.any();
    }

    /** A body of a class, checked once, in the data flow where the class was declared if local. */
    private <T> T inClass(KotlinClassSymbol cls, Object node, Function<BodyChecker, T> check) {
        return memo(node, () -> {
            LocalOrigin origin = localOrigin(cls);
            return inLocalFlow(origin, () -> check.apply(checker(origin, cls.file())));
        });
    }

    /** The context in the body of a class, or in its header, which has no instance of the class yet. */
    private Context classContext(KotlinClassSymbol cls, boolean header) {
        LocalOrigin origin = localOrigin(cls);
        List<LocalScope> locals = origin != null ? origin.context().locals() : List.of();
        ClassContexts contexts = classContexts(cls);
        return new Context(cls.file(), cls.bodyScope(true), locals,
                header ? contexts.forConstructorHeader() : contexts.regular(), null, null, cls);
    }

    // ==== Local declarations ====

    /** A class declared in a body, or the class of an object expression, with its members checked. */
    KotlinClassSymbol localClass(Decl.Class d, BodyChecker checker, Context ctx, LocalScope scope) {
        KotlinClassSymbol cls = resolver.localClass(d, ctx.file(), ctx.typeScope(), ctx.containingClass());
        // Visible in its own body.
        if (scope != null) scope.add(cls);
        localOrigins.put(cls, new LocalOrigin(checker, ctx, checker.dfa().save()));
        checker.dfa().enterClass(d, d.name() == null);
        checkMembers(cls);
        checker.dfa().exitClass();
        return cls;
    }

    private void checkMembers(KotlinClassSymbol cls) {
        checkClass(cls);
        List<CallableSymbol> callables = new ArrayList<>();
        collect(cls.members(), callables);
        for (CallableSymbol c : callables) {
            if (resolver.context(c) != null) check(c);
        }
        for (Decl member : cls.decl().members()) {
            if (member instanceof Decl.Class nested
                    && cls.members().classifier(nested.name()) instanceof KotlinClassSymbol n) {
                checkMembers(n);
            }
        }
    }

    /** A function declared in a body, added to the scope, then checked. */
    CheckedFunction localFunction(Decl.Function d, BodyChecker checker, Context ctx, LocalScope scope) {
        FunctionSymbol f = resolver.localFunction(d, ctx.file(), ctx.typeScope());
        // Visible in its own body.
        scope.add(f);
        localOrigins.put(f, new LocalOrigin(checker, ctx, checker.dfa().save()));
        if (d.body() == null) throw error(f, d.pos(), "A local function needs a body");
        return function(f, d);
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
     * have no instance of the class, unless it is an object; for the
     * companion object, which is not its own receiver as the companion; and
     * for constructor headers, supertype delegates and enum entries, which
     * have no instance yet.
     */
    private record ClassContexts(List<Context.TowerElement> regular, List<Context.TowerElement> forNestedClasses,
                                 List<Context.TowerElement> forCompanionObject,
                                 List<Context.TowerElement> forConstructorHeader) {
    }

    /** As FIR's withScopesForClass and collectTowerDataElementsForClass. */
    private ClassContexts classContexts(ClassSymbol cls) {
        ClassContexts cached = classContexts.get(cls);
        if (cached != null) return cached;
        List<Context.TowerElement> base = outerElements(cls);
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
                singleton ? List.copyOf(regular) : List.copyOf(staticsAndCompanion), List.copyOf(statics),
                List.copyOf(staticsAndCompanion));
        classContexts.put(cls, result);
        return result;
    }

    /** The tower a class is declared in: of the outer class, the body a local class is in, or the file. */
    private List<Context.TowerElement> outerElements(ClassSymbol cls) {
        if (cls.outer() == null) {
            LocalOrigin origin = localOrigins.get(cls);
            return origin != null ? origin.context().elements() : importing(((KotlinClassSymbol) cls).file());
        }
        ClassContexts outer = classContexts(cls.outer());
        return cls.isInner() ? outer.regular()
                : cls.has(Modifier.COMPANION) ? outer.forCompanionObject() : outer.forNestedClasses();
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
