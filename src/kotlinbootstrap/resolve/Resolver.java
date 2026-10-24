package kotlinbootstrap.resolve;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import kotlinbootstrap.ast.Annotation;
import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.KtFile;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.ast.Modifiers;
import kotlinbootstrap.ast.NamedType;
import kotlinbootstrap.ast.Node;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.PackageSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.Symbol;
import kotlinbootstrap.symbols.SymbolTable;
import kotlinbootstrap.symbols.TypeAliasSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Variance;

/**
 * Turns Kotlin declarations into symbols. {@link #enter} registers every
 * class and top-level declaration in the symbol table; their types are
 * resolved lazily, when first asked for.
 *
 * <p>Expected and actual declarations are compiled together, as in a JVM
 * compilation of a multiplatform module: an actual class or type alias
 * replaces the expected class, and expected top-level callables are left
 * out. They are kept in {@link #expectedDeclarations} for what actual
 * declarations inherit from them, like default argument values.
 */
public final class Resolver {
    private final SymbolTable table;
    private final TypeResolver types;
    private final List<FileContext> files = new ArrayList<>();
    private final Map<Object, Symbol> expectedDeclarations = new HashMap<>();
    private final Map<String, ClassifierSymbol> builtinClassifiers = new HashMap<>();

    public Resolver(SymbolTable table) {
        this.table = table;
        this.types = new TypeResolver(this);
        table.addFinder(new FunctionClasses(this));
    }

    public SymbolTable table() {
        return table;
    }

    public TypeResolver types() {
        return types;
    }

    public List<FileContext> files() {
        return files;
    }

    public Map<Object, Symbol> expectedDeclarations() {
        return expectedDeclarations;
    }

    // ==== Entering declarations ====

    /** Registers the declarations of the files. Call once, with all files. */
    public void enter(List<KtFile> ktFiles) {
        List<ClassifierSymbol> expected = new ArrayList<>();
        List<FileContext> builtinFiles = new ArrayList<>();
        for (KtFile ktFile : ktFiles) {
            FileContext file = new FileContext(ktFile, this);
            files.add(file);
            if (isBuiltinFile(file)) builtinFiles.add(file);
            PackageSymbol pkg = new PackageSymbol(file.packageName());
            MemberScope scope = table.packageScope(file.packageName());
            for (Decl decl : ktFile.declarations()) {
                switch (decl) {
                    case Decl.Class c -> {
                        ClassSymbol cls = enterClass(c, file, null);
                        if (c.modifiers().has(Modifier.EXPECT)) expected.add(cls);
                    }
                    case Decl.TypeAlias a -> {
                        ClassId id = new ClassId(file.packageName(), a.name());
                        TypeAliasSymbol alias = typeAlias(a, id, file.scope(), file);
                        if (a.modifiers().has(Modifier.EXPECT)) {
                            expected.add(alias);
                        } else {
                            addClassifier(id, alias, file, a);
                        }
                    }
                    case Decl.Function f -> {
                        FunctionSymbol fn = function(f, pkg, file.scope(), file, null, false);
                        if (f.modifiers().has(Modifier.EXPECT)) {
                            expectedDeclarations.put(f, fn);
                        } else {
                            scope.add(fn);
                        }
                    }
                    case Decl.Property p -> {
                        PropertySymbol prop = property(p, pkg, file.scope(), file, null, false);
                        if (p.modifiers().has(Modifier.EXPECT)) {
                            expectedDeclarations.put(p, prop);
                        } else {
                            scope.add(prop);
                        }
                    }
                    default -> throw new ResolveException(ktFile.source(), decl.pos(),
                            "Unexpected top-level declaration");
                }
            }
        }
        // Expected classes without an actual one (@OptionalExpectation annotations) stay.
        for (ClassifierSymbol c : expected) {
            ClassId id = c instanceof ClassSymbol cls ? cls.id() : ((TypeAliasSymbol) c).id();
            ClassifierSymbol actual = table.kotlinClassifiers().get(id);
            if (actual == c) continue;
            Object source = c instanceof KotlinClassSymbol k ? k.decl() : ((TypeAliasSymbol) c).source();
            expectedDeclarations.put(source, c);
            if (actual == null) table.replaceKotlinClassifier(id, c);
        }
        for (FileContext file : builtinFiles) {
            for (Decl decl : file.file().declarations()) {
                String name = switch (decl) {
                    case Decl.Class c -> c.name();
                    case Decl.TypeAlias a -> a.name();
                    default -> null;
                };
                if (name == null) continue;
                ClassifierSymbol c = table.kotlinClassifiers().get(new ClassId(file.packageName(), name));
                if (c != null) builtinClassifiers.put(name, c);
            }
        }
    }

    /**
     * Builtin classes are those that kotlinc serializes as builtins: the
     * ones in files marked {@code @file:JvmBuiltin}, in the packages that
     * are imported by default.
     */
    private static boolean isBuiltinFile(FileContext file) {
        if (!BUILTIN_PACKAGES.contains(file.packageName())) return false;
        for (Annotation a : file.file().annotations()) {
            if (a.type().segments().getLast().name().equals("JvmBuiltin")) return true;
        }
        return false;
    }

    private static final Set<String> BUILTIN_PACKAGES = Set.of(
            "kotlin", "kotlin.collections", "kotlin.ranges", "kotlin.annotation");

    /** A builtin class by short name, or null. */
    ClassifierSymbol builtinClassifier(String name) {
        return builtinClassifiers.get(name);
    }

    /** Creates and registers the symbols for a class and its nested classes. */
    private ClassSymbol enterClass(Decl.Class c, FileContext file, KotlinClassSymbol outer) {
        ClassId id = outer == null ? new ClassId(file.packageName(), c.name()) : outer.id().nested(className(c));
        KotlinClassSymbol cls = new KotlinClassSymbol(this, c, id, outer, file);
        boolean isExpect = c.modifiers().has(Modifier.EXPECT) || outer != null && outer.has(Modifier.EXPECT);
        if (outer == null) {
            if (isExpect) {
                // Registered now, so that it can be found if there is no actual class.
                table.addKotlinClassifier(id, cls);
            } else {
                addClassifier(id, cls, file, c);
            }
        } else {
            table.addKotlinClassifier(id, cls);
        }
        for (Decl member : c.members()) {
            if (member instanceof Decl.Class nested) cls.addNested(enterClass(nested, file, cls));
        }
        return cls;
    }

    /** Companion objects without a name are called Companion. */
    static String className(Decl.Class c) {
        return c.name() != null ? c.name() : "Companion";
    }

    /** Registers a top-level non-expect classifier, replacing an expected one. */
    private void addClassifier(ClassId id, ClassifierSymbol c, FileContext file, Node decl) {
        ClassifierSymbol existing = table.addKotlinClassifier(id, c);
        if (existing != null) {
            boolean existingIsExpected = existing instanceof KotlinClassSymbol k && k.has(Modifier.EXPECT);
            if (!existingIsExpected) {
                throw new ResolveException(file.file().source(), decl.pos(), "Redeclaration of " + id);
            }
        }
        table.replaceKotlinClassifier(id, c);
    }

    // ==== Qualified names ====

    public ClassifierSymbol findQualifiedClassifier(List<String> path) {
        return table.findQualifiedClassifier(path);
    }

    /** For a star import {@code path.*}: the classifier called name in the package or class. */
    ClassifierSymbol findInPackageOrClass(List<String> path, String name) {
        String pkg = String.join(".", path);
        ClassifierSymbol c = table.packageExists(pkg) ? table.findClassifier(new ClassId(pkg, name)) : null;
        if (c != null) return c;
        ClassifierSymbol container = findQualifiedClassifier(path);
        return container == null ? null : nestedClassifier(container, name);
    }

    public ClassifierSymbol nestedClassifier(ClassifierSymbol c, String name) {
        return SymbolTable.nestedClassifier(c, name);
    }

    // ==== Declarations ====

    record TypeParams(List<TypeParamSymbol> symbols, Scope scope) {
    }

    /**
     * Creates the type parameter symbols of a declaration, and the scope in
     * which they are visible.
     */
    TypeParams typeParams(List<Decl.TypeParam> params, List<Decl.TypeConstraint> constraints, Scope outer,
                          FileContext file) {
        List<TypeParamSymbol> symbols = new ArrayList<>();
        Scope[] scope = new Scope[1];
        for (Decl.TypeParam p : params) {
            Variance variance = p.modifiers().has(Modifier.IN) ? Variance.IN
                    : p.modifiers().has(Modifier.OUT) ? Variance.OUT : Variance.INVARIANT;
            symbols.add(new TypeParamSymbol(p.name(), Origin.KOTLIN_SOURCE, variance,
                    p.modifiers().has(Modifier.REIFIED), () -> {
                List<KType> bounds = new ArrayList<>();
                if (p.bound() != null) bounds.add(types.resolve(p.bound(), scope[0], file));
                for (Decl.TypeConstraint c : constraints) {
                    if (c.name().equals(p.name())) bounds.add(types.resolve(c.bound(), scope[0], file));
                }
                if (bounds.isEmpty()) bounds.add(types.nullableAny());
                return bounds;
            }));
        }
        scope[0] = Scope.typeParams(symbols, outer);
        return new TypeParams(symbols, scope[0]);
    }

    TypeAliasSymbol typeAlias(Decl.TypeAlias a, ClassId id, Scope outer, FileContext file) {
        TypeParams tps = typeParams(a.typeParams(), List.of(), outer, file);
        return new TypeAliasSymbol(id, visibility(a.modifiers(), Visibility.PUBLIC), tps.symbols(),
                () -> types.resolve(a.type(), tps.scope(), file), a);
    }

    /**
     * A function symbol.
     *
     * @param owner    the class or package, null for a local function
     * @param outer    the scope the declaration is in
     * @param cls      the containing class, for member functions
     * @param isStatic for members of a companion block
     */
    FunctionSymbol function(Decl.Function f, Symbol owner, Scope outer, FileContext file, ClassSymbol cls,
                            boolean isStatic) {
        TypeParams tps = typeParams(f.typeParams(), f.constraints(), outer, file);
        Scope scope = tps.scope();
        Supplier<KType> returnType;
        if (f.returnType() != null) {
            returnType = () -> types.resolve(f.returnType(), scope, file);
        } else if (f.body() == null || f.body() instanceof Expr.Block) {
            returnType = types::unit;
        } else {
            returnType = () -> inferredType(file, f);
        }
        return new FunctionSymbol(f.name(), Origin.KOTLIN_SOURCE, owner,
                visibility(f.modifiers(), Visibility.PUBLIC),
                modality(f.modifiers(), cls, f.body() == null), keywords(f.modifiers()),
                isStatic, f, tps::symbols,
                () -> new CallableSymbol.Signature(
                        f.receiver() == null ? null : types.resolve(f.receiver(), scope, file),
                        contextParams(f.modifiers(), scope, file),
                        valueParams(f.params(), scope, file)),
                returnType);
    }

    PropertySymbol property(Decl.Property p, Symbol owner, Scope outer, FileContext file, ClassSymbol cls,
                            boolean isStatic) {
        TypeParams tps = typeParams(p.typeParams(), p.constraints(), outer, file);
        Scope scope = tps.scope();
        Supplier<KType> type;
        if (p.type() != null) {
            type = () -> types.resolve(p.type(), scope, file);
        } else if (p.getter() != null && p.getter().returnType() != null) {
            type = () -> types.resolve(p.getter().returnType(), scope, file);
        } else {
            type = () -> inferredType(file, p);
        }
        Visibility visibility = visibility(p.modifiers(), Visibility.PUBLIC);
        Visibility setterVisibility = null;
        if (p.isVar()) {
            setterVisibility = p.setter() != null ? visibility(p.setter().modifiers(), visibility) : visibility;
        }
        boolean hasBody = p.initializer() != null || p.delegate() != null
                || p.getter() != null && p.getter().body() != null;
        return new PropertySymbol(p.name(), Origin.KOTLIN_SOURCE, owner, visibility,
                modality(p.modifiers(), cls, !hasBody), keywords(p.modifiers()), isStatic, p,
                p.isVar(), false, setterVisibility, tps::symbols,
                () -> new CallableSymbol.Signature(
                        p.receiver() == null ? null : types.resolve(p.receiver(), scope, file),
                        contextParams(p.modifiers(), scope, file), List.of()),
                type);
    }

    List<ValueParamSymbol> valueParams(List<Decl.Param> params, Scope scope, FileContext file) {
        List<ValueParamSymbol> result = new ArrayList<>();
        for (Decl.Param p : params) {
            result.add(new ValueParamSymbol(p.name(), types.resolve(p.type(), scope, file),
                    p.defaultValue() != null, keywords(p.modifiers()), p));
        }
        return result;
    }

    private List<KType> contextParams(Modifiers modifiers, Scope scope, FileContext file) {
        List<KType> result = new ArrayList<>();
        for (NamedType t : modifiers.contextParams()) result.add(types.resolve(t.type(), scope, file));
        return result;
    }

    /** Placeholder until the expression checker can infer declaration types from their bodies. */
    private KType inferredType(FileContext file, Node decl) {
        throw new ResolveException(file.file().source(), decl.pos(), "Type inference is not implemented yet");
    }

    // ==== Modifiers ====

    /**
     * The declared visibility, or the default. Overrides really inherit the
     * overridden member's visibility by default; public is close enough
     * until we generate code, where widening is allowed anyway.
     */
    static Visibility visibility(Modifiers m, Visibility defaultVisibility) {
        if (m.has(Modifier.PRIVATE)) return Visibility.PRIVATE;
        if (m.has(Modifier.PROTECTED)) return Visibility.PROTECTED;
        if (m.has(Modifier.INTERNAL)) return Visibility.INTERNAL;
        if (m.has(Modifier.PUBLIC)) return Visibility.PUBLIC;
        return defaultVisibility;
    }

    /** Modality of a member (or top-level declaration if cls is null). */
    static Modality modality(Modifiers m, ClassSymbol cls, boolean noBody) {
        if (m.has(Modifier.FINAL)) return Modality.FINAL;
        if (m.has(Modifier.ABSTRACT)) return Modality.ABSTRACT;
        if (m.has(Modifier.OPEN)) return Modality.OPEN;
        if (m.has(Modifier.SEALED)) return Modality.SEALED;
        if (cls != null && cls.isInterface()) return noBody ? Modality.ABSTRACT : Modality.OPEN;
        if (m.has(Modifier.OVERRIDE) && cls != null && cls.modality() != Modality.FINAL) return Modality.OPEN;
        return Modality.FINAL;
    }

    /** The modifier keywords other than visibility and modality. */
    static Set<Modifier> keywords(Modifiers m) {
        Set<Modifier> result = EnumSet.noneOf(Modifier.class);
        result.addAll(m.keywords());
        result.removeAll(EnumSet.of(Modifier.PUBLIC, Modifier.PROTECTED, Modifier.PRIVATE, Modifier.INTERNAL,
                Modifier.FINAL, Modifier.OPEN, Modifier.ABSTRACT, Modifier.SEALED));
        return result;
    }
}
