package kotlinbootstrap.java;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import kotlinbootstrap.resolve.Scope;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassFinder;
import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Lazy;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.SymbolTable;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.Variance;

/**
 * Classes declared in the Java sources being compiled along with Kotlin.
 * javac parses them, but cannot resolve them, since they may refer to
 * Kotlin classes; so we resolve the names in their signatures ourselves,
 * following Java's scoping rules. Method bodies are not looked at.
 */
public final class JavaSourceClasses implements ClassFinder {
    private final SymbolTable table;
    private final JavaTypes types;
    private final SourcePositions positions;
    private final Map<ClassId, SourceClass> classes = new HashMap<>();
    private final Set<String> packages = new HashSet<>();

    public JavaSourceClasses(SymbolTable table, List<Path> files) {
        this.table = table;
        this.types = new JavaTypes(table);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        JavacTask task = (JavacTask) compiler.getTask(null, fm, null, List.of("-proc:none"), null,
                fm.getJavaFileObjectsFromPaths(files));
        this.positions = Trees.instance(task).getSourcePositions();
        try {
            for (CompilationUnitTree unit : task.parse()) enterUnit(unit);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public ClassSymbol findClass(ClassId id) {
        return classes.get(id);
    }

    @Override
    public boolean packageExists(String packageName) {
        return packages.contains(packageName);
    }

    private void enterUnit(CompilationUnitTree tree) throws IOException {
        String pkg = tree.getPackageName() == null ? "" : tree.getPackageName().toString();
        for (String p = pkg; packages.add(p) && !p.isEmpty(); ) {
            p = p.contains(".") ? p.substring(0, p.lastIndexOf('.')) : "";
        }
        Unit unit = new Unit(tree, pkg, tree.getSourceFile().getCharContent(true));
        for (Tree t : tree.getTypeDecls()) {
            if (t instanceof ClassTree c) enterClass(c, new ClassId(pkg, c.getSimpleName().toString()), null, unit);
        }
    }

    private void enterClass(ClassTree tree, ClassId id, SourceClass outer, Unit unit) {
        SourceClass cls = new SourceClass(tree, id, outer, unit);
        classes.put(id, cls);
        for (Tree member : tree.getMembers()) {
            if (member instanceof ClassTree c) enterClass(c, id.nested(c.getSimpleName().toString()), cls, unit);
        }
    }

    /** A compilation unit, with its import scope. */
    private final class Unit {
        final CompilationUnitTree tree;
        final String packageName;
        final CharSequence text;
        final Scope scope;

        Unit(CompilationUnitTree tree, String packageName, CharSequence text) {
            this.tree = tree;
            this.packageName = packageName;
            this.text = text;
            Map<String, List<String>> single = new HashMap<>();
            List<List<String>> onDemand = new ArrayList<>();
            for (ImportTree imp : tree.getImports()) {
                List<String> path = List.of(imp.getQualifiedIdentifier().toString().split("\\."));
                if (path.getLast().equals("*")) {
                    onDemand.add(path.subList(0, path.size() - 1));
                } else {
                    single.put(path.getLast(), path);
                }
            }
            onDemand.add(List.of("java", "lang"));
            Scope onDemandLevel = Scope.of(name -> {
                for (List<String> path : onDemand) {
                    String pkg = String.join(".", path);
                    ClassifierSymbol c = table.packageExists(pkg) ? table.findClassifier(new ClassId(pkg, name)) : null;
                    if (c == null) {
                        ClassifierSymbol container = table.findQualifiedClassifier(path);
                        if (container != null) c = SymbolTable.nestedClassifier(container, name);
                    }
                    if (c != null) return c;
                }
                return null;
            }, null);
            Scope packageLevel = Scope.of(name -> table.findClassifier(new ClassId(packageName, name)), onDemandLevel);
            this.scope = Scope.of(name -> {
                List<String> path = single.get(name);
                return path == null ? null : table.findQualifiedClassifier(path);
            }, packageLevel);
        }

        String source(Tree t) {
            long start = positions.getStartPosition(tree, t);
            long end = positions.getEndPosition(tree, t);
            return start < 0 || end < 0 ? "" : text.subSequence((int) start, (int) end).toString();
        }
    }

    private final class SourceClass extends ClassSymbol {
        private final ClassTree tree;
        private final Unit unit;
        private final List<TypeParamSymbol> typeParams = new ArrayList<>();
        private final Scope headerScope;
        private final Scope bodyScope;
        private final Lazy<List<KType>> supertypes;
        private final Lazy<MemberScope> members;

        SourceClass(ClassTree tree, ClassId id, SourceClass outer, Unit unit) {
            super(id, outer, javaKind(tree), Origin.JAVA_SOURCE, javaVisibility(tree.getModifiers(), outer),
                    classModality(tree), isInnerClass(tree, outer)
                            ? Set.of(kotlinbootstrap.ast.Modifier.INNER) : Set.of());
            this.tree = tree;
            this.unit = unit;
            Scope outerScope = outer == null ? unit.scope : outer.bodyScope;
            this.headerScope = typeParams(tree.getTypeParameters(), typeParams, outerScope);
            this.bodyScope = Scope.of(name -> SymbolTable.findInheritedClassifier(this, name), headerScope);
            this.supertypes = new Lazy<>(this::resolveSupertypes);
            this.members = new Lazy<>(this::buildMembers);
        }

        @Override
        public List<TypeParamSymbol> typeParams() {
            return typeParams;
        }

        @Override
        public List<KType> supertypes() {
            return supertypes.get();
        }

        @Override
        public boolean supertypesInProgress() {
            return supertypes.isComputing();
        }

        @Override
        public MemberScope members() {
            return members.get();
        }

        private List<KType> resolveSupertypes() {
            List<KType> result = new ArrayList<>();
            if (tree.getExtendsClause() != null) {
                result.add(JavaTypes.supertype(type(tree.getExtendsClause(), headerScope)));
            } else if (kind() == ClassKind.ENUM_CLASS) {
                KType self = new KType.ClassType(this, List.of(), false);
                result.add(new KType.ClassType(table.requireClass("kotlin.Enum"), List.of(TypeArg.of(self)), false));
            }
            for (Tree t : tree.getImplementsClause()) result.add(JavaTypes.supertype(type(t, headerScope)));
            if (result.isEmpty()) result.add(types.any());
            return result;
        }

        private MemberScope buildMembers() {
            MemberScope scope = new MemberScope();
            boolean hasConstructor = false;
            for (Tree member : tree.getMembers()) {
                switch (member) {
                    case ClassTree c -> scope.add(classes.get(id().nested(c.getSimpleName().toString())));
                    case MethodTree m -> {
                        if (javaVisibility(m.getModifiers(), this) == Visibility.PRIVATE) continue;
                        boolean isConstructor = m.getName().contentEquals("<init>");
                        hasConstructor |= isConstructor;
                        scope.add(method(m, isConstructor));
                    }
                    case VariableTree v -> {
                        if (javaVisibility(v.getModifiers(), this) != Visibility.PRIVATE) scope.add(field(v));
                    }
                    default -> {
                    }
                }
            }
            if (!hasConstructor && kind() == ClassKind.CLASS) {
                scope.add(new FunctionSymbol(FunctionSymbol.CONSTRUCTOR_NAME, Origin.JAVA_SOURCE, this,
                        javaVisibility(tree.getModifiers(), outer()), Modality.FINAL, Set.of(), false, null,
                        this::typeParams, () -> CallableSymbol.Signature.NONE, this::defaultType));
            }
            return scope;
        }

        private KType defaultType() {
            List<TypeArg> args = new ArrayList<>();
            for (TypeParamSymbol tp : typeParams) args.add(TypeArg.of(new KType.TypeParamType(tp, false)));
            return new KType.ClassType(this, args, false);
        }

        private FunctionSymbol method(MethodTree m, boolean isConstructor) {
            List<TypeParamSymbol> methodTypeParams = new ArrayList<>();
            Scope scope = typeParams(m.getTypeParameters(), methodTypeParams, bodyScope);
            boolean isStatic = m.getModifiers().getFlags().contains(Modifier.STATIC);
            Modality modality;
            if (isConstructor || isStatic || m.getModifiers().getFlags().contains(Modifier.FINAL)) {
                modality = Modality.FINAL;
            } else if (m.getModifiers().getFlags().contains(Modifier.ABSTRACT)
                    || isInterface() && m.getBody() == null) {
                modality = Modality.ABSTRACT;
            } else {
                modality = Modality.OPEN;
            }
            return new FunctionSymbol(isConstructor ? FunctionSymbol.CONSTRUCTOR_NAME : m.getName().toString(),
                    Origin.JAVA_SOURCE, this, javaVisibility(m.getModifiers(), this), modality, Set.of(), isStatic, m,
                    () -> {
                        List<TypeParamSymbol> all = new ArrayList<>();
                        if (isConstructor) all.addAll(typeParams);
                        all.addAll(methodTypeParams);
                        return all;
                    },
                    () -> {
                        List<ValueParamSymbol> params = new ArrayList<>();
                        for (VariableTree p : m.getParameters()) {
                            boolean vararg = unit.source(p.getType()).endsWith("...");
                            Tree typeTree = vararg ? ((ArrayTypeTree) p.getType()).getType() : p.getType();
                            KType type = JavaTypes.withNullability(type(typeTree, scope),
                                    nullability(p.getModifiers()));
                            params.add(new ValueParamSymbol(p.getName().toString(), type, false,
                                    vararg ? EnumSet.of(kotlinbootstrap.ast.Modifier.VARARG) : Set.of(), p));
                        }
                        return new CallableSymbol.Signature(null, List.of(), params);
                    },
                    () -> isConstructor ? defaultType()
                            : JavaTypes.withNullability(type(m.getReturnType(), scope), nullability(m.getModifiers())));
        }

        private PropertySymbol field(VariableTree v) {
            Set<Modifier> flags = v.getModifiers().getFlags();
            boolean isStatic = isInterface() || flags.contains(Modifier.STATIC);
            boolean isFinal = isInterface() || flags.contains(Modifier.FINAL);
            Visibility visibility = javaVisibility(v.getModifiers(), this);
            return new PropertySymbol(v.getName().toString(), Origin.JAVA_SOURCE, this, visibility, Modality.FINAL,
                    Set.of(), isStatic, v, !isFinal, true, isFinal ? null : visibility, List::of,
                    () -> CallableSymbol.Signature.NONE,
                    () -> isEnumConstant(v) ? new KType.ClassType(this, List.of(), false)
                            : JavaTypes.withNullability(type(v.getType(), bodyScope), nullability(v.getModifiers())));
        }

        /**
         * The parser gives enum constants a type and modifiers, but positions
         * the type at the name, where no explicit modifiers could be.
         */
        private boolean isEnumConstant(VariableTree v) {
            return kind() == ClassKind.ENUM_CLASS && v.getInitializer() instanceof NewClassTree
                    && v.getModifiers().getFlags().containsAll(EnumSet.of(Modifier.PUBLIC, Modifier.STATIC))
                    && positions.getStartPosition(unit.tree, v) == positions.getStartPosition(unit.tree, v.getType());
        }

        // ==== Types ====

        private Scope typeParams(List<? extends TypeParameterTree> trees, List<TypeParamSymbol> into, Scope outer) {
            Scope[] scope = new Scope[1];
            for (TypeParameterTree tp : trees) {
                into.add(new TypeParamSymbol(tp.getName().toString(), Origin.JAVA_SOURCE, Variance.INVARIANT, false,
                        () -> tp.getBounds().isEmpty()
                                ? List.of(new KType.Flexible(types.any(), types.any().withNullable(true)))
                                : tp.getBounds().stream().map(b -> type(b, scope[0])).toList()));
            }
            scope[0] = Scope.typeParams(into, outer);
            return scope[0];
        }

        private KType type(Tree t, Scope scope) {
            return switch (t) {
                case PrimitiveTypeTree p -> types.primitive(primitiveName(p.getPrimitiveTypeKind()));
                case ArrayTypeTree a -> {
                    String primitive = a.getType() instanceof PrimitiveTypeTree p
                            ? primitiveName(p.getPrimitiveTypeKind()) : null;
                    yield types.arrayType(primitive, primitive == null ? type(a.getType(), scope) : null);
                }
                case ParameterizedTypeTree p -> {
                    ClassifierSymbol c = classifier(p.getType(), scope);
                    if (!(c instanceof ClassSymbol cls)) throw error(t, "Type arguments on a type parameter");
                    List<TypeArg> args = new ArrayList<>();
                    for (Tree a : p.getTypeArguments()) args.add(typeArg(a, scope));
                    if (cls.isInner()) {
                        for (TypeParamSymbol tp : cls.outer().typeParams()) {
                            args.add(TypeArg.of(new KType.TypeParamType(tp, false)));
                        }
                    }
                    yield types.classType(cls, args);
                }
                case IdentifierTree i -> named(classifier(i, scope));
                case MemberSelectTree m -> named(classifier(m, scope));
                case AnnotatedTypeTree a -> JavaTypes.withNullability(type(a.getUnderlyingType(), scope),
                        JavaTypes.nullability(annotationNames(a.getAnnotations())));
                default -> throw error(t, "Unexpected Java type " + t);
            };
        }

        private KType named(ClassifierSymbol c) {
            return c instanceof TypeParamSymbol tp ? JavaTypes.typeParamType(tp) : types.classType((ClassSymbol) c, List.of());
        }

        private TypeArg typeArg(Tree t, Scope scope) {
            if (!(t instanceof WildcardTree w)) return TypeArg.of(type(t, scope));
            return switch (w.getKind()) {
                case EXTENDS_WILDCARD -> new TypeArg(Variance.OUT, type(w.getBound(), scope));
                case SUPER_WILDCARD -> new TypeArg(Variance.IN, type(w.getBound(), scope));
                default -> TypeArg.STAR;
            };
        }

        private ClassifierSymbol classifier(Tree t, Scope scope) {
            ClassifierSymbol c = switch (t) {
                case IdentifierTree i -> scope.findClassifier(i.getName().toString());
                case MemberSelectTree m -> {
                    List<String> path = List.of(m.toString().split("\\."));
                    ClassifierSymbol first = scope.findClassifier(path.getFirst());
                    if (first == null) yield table.findQualifiedClassifier(path);
                    for (String name : path.subList(1, path.size())) {
                        first = first == null ? null : SymbolTable.nestedClassifier(first, name);
                    }
                    yield first;
                }
                default -> throw error(t, "Unexpected Java type name " + t);
            };
            if (c == null) throw error(t, "Unresolved Java type " + t);
            return c;
        }

        private RuntimeException error(Tree t, String message) {
            long pos = positions.getStartPosition(unit.tree, t);
            return new IllegalStateException(unit.tree.getSourceFile().getName() + " at " + pos + ": " + message);
        }
    }

    private static Visibility javaVisibility(ModifiersTree m, ClassSymbol owner) {
        Set<Modifier> flags = m.getFlags();
        if (flags.contains(Modifier.PUBLIC)) return Visibility.PUBLIC;
        if (flags.contains(Modifier.PROTECTED)) return Visibility.PROTECTED;
        if (flags.contains(Modifier.PRIVATE)) return Visibility.PRIVATE;
        return owner != null && owner.isInterface() ? Visibility.PUBLIC : Visibility.PACKAGE;
    }

    private static String primitiveName(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> "Boolean";
            case BYTE -> "Byte";
            case SHORT -> "Short";
            case INT -> "Int";
            case LONG -> "Long";
            case CHAR -> "Char";
            case FLOAT -> "Float";
            case DOUBLE -> "Double";
            case VOID -> "Unit";
            default -> throw new IllegalStateException("Unexpected primitive " + kind);
        };
    }

    private static List<String> annotationNames(List<? extends AnnotationTree> annotations) {
        List<String> names = new ArrayList<>();
        for (AnnotationTree a : annotations) {
            String name = a.getAnnotationType().toString();
            names.add(name.substring(name.lastIndexOf('.') + 1));
        }
        return names;
    }

    private static JavaTypes.Nullability nullability(ModifiersTree m) {
        return JavaTypes.nullability(annotationNames(m.getAnnotations()));
    }

    private static ClassKind javaKind(ClassTree tree) {
        return switch (tree.getKind()) {
            case INTERFACE -> ClassKind.INTERFACE;
            case ENUM -> ClassKind.ENUM_CLASS;
            case ANNOTATION_TYPE -> ClassKind.ANNOTATION_CLASS;
            default -> ClassKind.CLASS;
        };
    }

    private static Modality classModality(ClassTree tree) {
        Set<Modifier> flags = tree.getModifiers().getFlags();
        if (tree.getKind() == Tree.Kind.INTERFACE || tree.getKind() == Tree.Kind.ANNOTATION_TYPE
                || flags.contains(Modifier.ABSTRACT)) {
            return Modality.ABSTRACT;
        }
        if (flags.contains(Modifier.FINAL) || tree.getKind() == Tree.Kind.ENUM) return Modality.FINAL;
        return Modality.OPEN;
    }

    /** Non-static nested classes of classes are inner; nested types of interfaces are implicitly static. */
    private static boolean isInnerClass(ClassTree tree, SourceClass outer) {
        return outer != null && !outer.isInterface() && tree.getKind() == Tree.Kind.CLASS
                && !tree.getModifiers().getFlags().contains(Modifier.STATIC);
    }
}
