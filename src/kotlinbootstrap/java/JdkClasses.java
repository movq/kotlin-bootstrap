package kotlinbootstrap.java;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.sun.source.util.JavacTask;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassFinder;
import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
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
 * Classes of the JDK, read through javac's symbol tables. With a release
 * option, javac reads the API of that Java release (from ct.sym) rather
 * than of the running JDK.
 */
public final class JdkClasses implements ClassFinder {
    private final SymbolTable table;
    private final JavaTypes types;
    private final Elements elements;
    private final Map<TypeParameterElement, TypeParamSymbol> typeParams = new HashMap<>();

    /** @param release the Java release whose API to use, e.g. "8" */
    public JdkClasses(SymbolTable table, String release) {
        this.table = table;
        this.types = new JavaTypes(table);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavacTask task = (JavacTask) compiler.getTask(null, null, null,
                List.of("--release", release, "-proc:none", "-Xlint:-options"), null, List.of());
        this.elements = task.getElements();
    }

    @Override
    public ClassSymbol findClass(ClassId id) {
        TypeElement te = elements.getTypeElement(id.fqName());
        if (te == null || !elements.getPackageOf(te).getQualifiedName().contentEquals(id.packageName())) return null;
        return new JdkClassSymbol(te, id);
    }

    @Override
    public boolean packageExists(String packageName) {
        return elements.getPackageElement(packageName) != null;
    }

    static ClassId classId(TypeElement te) {
        String pkg = enclosingPackage(te).getQualifiedName().toString();
        String fq = te.getQualifiedName().toString();
        return new ClassId(pkg, pkg.isEmpty() ? fq : fq.substring(pkg.length() + 1));
    }

    private static PackageElement enclosingPackage(Element e) {
        while (!(e instanceof PackageElement p)) e = e.getEnclosingElement();
        return p;
    }

    private ClassSymbol symbol(TypeElement te) {
        ClassSymbol cls = table.findClass(classId(te));
        if (cls == null) throw new IllegalStateException("Cannot load " + te);
        return cls;
    }

    // ==== Types ====

    /** The Kotlin view of a Java type in a signature: a flexible type, except for primitives. */
    KType type(TypeMirror t) {
        return switch (t.getKind()) {
            case BOOLEAN -> primitive("Boolean");
            case BYTE -> primitive("Byte");
            case SHORT -> primitive("Short");
            case INT -> primitive("Int");
            case LONG -> primitive("Long");
            case CHAR -> primitive("Char");
            case FLOAT -> primitive("Float");
            case DOUBLE -> primitive("Double");
            case VOID -> primitive("Unit");
            case DECLARED -> declaredType((DeclaredType) t);
            case ARRAY -> arrayType((ArrayType) t);
            case TYPEVAR -> JavaTypes.typeParamType(typeParam((TypeParameterElement) ((TypeVariable) t).asElement()));
            default -> throw new IllegalStateException("Unexpected Java type " + t + " (" + t.getKind() + ")");
        };
    }

    private KType primitive(String name) {
        return types.primitive(name);
    }

    private KType declaredType(DeclaredType t) {
        List<TypeArg> args = new ArrayList<>();
        // Arguments of outer classes of inner classes come after the class's own.
        for (TypeMirror m = t; m instanceof DeclaredType d; m = d.getEnclosingType()) {
            for (TypeMirror a : d.getTypeArguments()) args.add(typeArg(a));
            if (!((TypeElement) d.asElement()).getNestingKind().isNested() || isStatic(d.asElement())) break;
        }
        return types.classType(symbol((TypeElement) t.asElement()), args);
    }

    private KType arrayType(ArrayType t) {
        TypeMirror c = t.getComponentType();
        String primitive = switch (c.getKind()) {
            case BOOLEAN -> "Boolean";
            case BYTE -> "Byte";
            case SHORT -> "Short";
            case INT -> "Int";
            case LONG -> "Long";
            case CHAR -> "Char";
            case FLOAT -> "Float";
            case DOUBLE -> "Double";
            default -> null;
        };
        return types.arrayType(primitive, primitive == null ? type(c) : null);
    }

    private TypeArg typeArg(TypeMirror a) {
        if (a.getKind() != TypeKind.WILDCARD) return TypeArg.of(type(a));
        WildcardType w = (WildcardType) a;
        if (w.getExtendsBound() != null) return new TypeArg(Variance.OUT, type(w.getExtendsBound()));
        if (w.getSuperBound() != null) return new TypeArg(Variance.IN, type(w.getSuperBound()));
        return TypeArg.STAR;
    }

    private TypeParamSymbol typeParam(TypeParameterElement e) {
        TypeParamSymbol tp = typeParams.get(e);
        if (tp == null) {
            tp = new TypeParamSymbol(e.getSimpleName().toString(), Origin.JAVA_BINARY, Variance.INVARIANT, false,
                    () -> e.getBounds().stream().map(this::type).toList());
            typeParams.put(e, tp);
        }
        return tp;
    }

    // ==== Classes ====

    private static Visibility javaVisibility(Element e) {
        Set<javax.lang.model.element.Modifier> m = e.getModifiers();
        if (m.contains(javax.lang.model.element.Modifier.PUBLIC)) return Visibility.PUBLIC;
        if (m.contains(javax.lang.model.element.Modifier.PROTECTED)) return Visibility.PROTECTED;
        if (m.contains(javax.lang.model.element.Modifier.PRIVATE)) return Visibility.PRIVATE;
        return Visibility.PACKAGE;
    }

    private static Modality javaModality(Element e) {
        Set<javax.lang.model.element.Modifier> m = e.getModifiers();
        if (m.contains(javax.lang.model.element.Modifier.ABSTRACT)) return Modality.ABSTRACT;
        if (m.contains(javax.lang.model.element.Modifier.FINAL)) return Modality.FINAL;
        if (m.contains(javax.lang.model.element.Modifier.STATIC) && e.getKind() == ElementKind.METHOD) {
            return Modality.FINAL;
        }
        return Modality.OPEN;
    }

    private static boolean isStatic(Element e) {
        return e.getModifiers().contains(javax.lang.model.element.Modifier.STATIC);
    }

    /** Whether Kotlin code can see a member: private and package-private JDK members are left out. */
    private static boolean isAccessible(Element e) {
        Visibility v = javaVisibility(e);
        return v == Visibility.PUBLIC || v == Visibility.PROTECTED;
    }

    private final class JdkClassSymbol extends ClassSymbol {
        private final TypeElement element;
        private final Lazy<List<TypeParamSymbol>> classTypeParams;
        private final Lazy<List<KType>> supertypes;
        private final Lazy<MemberScope> members;

        JdkClassSymbol(TypeElement element, ClassId id) {
            super(id, outerOf(element), javaKind(element), Origin.JAVA_BINARY, javaVisibility(element),
                    element.getKind() == ElementKind.INTERFACE || element.getKind() == ElementKind.ANNOTATION_TYPE
                            ? Modality.ABSTRACT : javaModality(element),
                    element.getNestingKind().isNested() && !isStatic(element)
                            && element.getKind() == ElementKind.CLASS ? Set.of(Modifier.INNER) : Set.of());
            this.element = element;
            this.classTypeParams = new Lazy<>(() -> element.getTypeParameters().stream()
                    .map(JdkClasses.this::typeParam).toList());
            this.supertypes = new Lazy<>(this::loadSupertypes);
            this.members = new Lazy<>(this::loadMembers);
        }

        @Override
        public List<TypeParamSymbol> typeParams() {
            return classTypeParams.get();
        }

        @Override
        public List<KType> supertypes() {
            return supertypes.get();
        }

        @Override
        public MemberScope members() {
            return members.get();
        }

        /** Supertypes are not flexible: a Java class extends {@code List<T>}, not a platform type. */
        private List<KType> loadSupertypes() {
            List<KType> result = new ArrayList<>();
            TypeMirror superclass = element.getSuperclass();
            if (superclass.getKind() == TypeKind.DECLARED) result.add(supertype(superclass));
            for (TypeMirror i : element.getInterfaces()) result.add(supertype(i));
            if (result.isEmpty() && !element.getQualifiedName().contentEquals("java.lang.Object")) {
                result.add(types.any());
            }
            return result;
        }

        private KType supertype(TypeMirror t) {
            return JavaTypes.supertype(type(t));
        }

        private MemberScope loadMembers() {
            MemberScope scope = new MemberScope();
            for (Element e : element.getEnclosedElements()) {
                if (!isAccessible(e)) continue;
                switch (e.getKind()) {
                    case METHOD -> scope.add(function((ExecutableElement) e, e.getSimpleName().toString()));
                    case CONSTRUCTOR -> scope.add(function((ExecutableElement) e, FunctionSymbol.CONSTRUCTOR_NAME));
                    case FIELD, ENUM_CONSTANT -> scope.add(field((VariableElement) e));
                    case CLASS, INTERFACE, ENUM, ANNOTATION_TYPE, RECORD -> scope.add(symbol((TypeElement) e));
                    default -> {
                    }
                }
            }
            return scope;
        }

        private FunctionSymbol function(ExecutableElement e, String name) {
            boolean isConstructor = name.equals(FunctionSymbol.CONSTRUCTOR_NAME);
            return new FunctionSymbol(name, Origin.JAVA_BINARY, this, javaVisibility(e),
                    isConstructor ? Modality.FINAL : javaModality(e), Set.of(), isStatic(e), e,
                    () -> {
                        List<TypeParamSymbol> tps = new ArrayList<>();
                        if (isConstructor) tps.addAll(typeParams());
                        for (TypeParameterElement tp : e.getTypeParameters()) tps.add(typeParam(tp));
                        return tps;
                    },
                    () -> {
                        List<ValueParamSymbol> params = new ArrayList<>();
                        List<? extends VariableElement> ps = e.getParameters();
                        for (int i = 0; i < ps.size(); i++) {
                            VariableElement p = ps.get(i);
                            boolean vararg = e.isVarArgs() && i == ps.size() - 1;
                            TypeMirror t = vararg ? ((ArrayType) p.asType()).getComponentType() : p.asType();
                            params.add(new ValueParamSymbol(p.getSimpleName().toString(), type(t), false,
                                    vararg ? EnumSet.of(Modifier.VARARG) : Set.of(), p));
                        }
                        return new CallableSymbol.Signature(null, List.of(), params);
                    },
                    () -> isConstructor ? defaultType() : type(e.getReturnType()));
        }

        private KType defaultType() {
            List<TypeArg> args = new ArrayList<>();
            for (TypeParamSymbol tp : typeParams()) args.add(TypeArg.of(new KType.TypeParamType(tp, false)));
            return new KType.ClassType(this, args, false);
        }

        private PropertySymbol field(VariableElement e) {
            boolean isFinal = e.getModifiers().contains(javax.lang.model.element.Modifier.FINAL);
            return new PropertySymbol(e.getSimpleName().toString(), Origin.JAVA_BINARY, this, javaVisibility(e),
                    Modality.FINAL, Set.of(), isStatic(e), e, !isFinal, true, isFinal ? null : javaVisibility(e),
                    List::of, () -> CallableSymbol.Signature.NONE,
                    () -> e.getKind() == ElementKind.ENUM_CONSTANT
                            ? new KType.ClassType(this, List.of(), false) : type(e.asType()));
        }
    }

    private ClassSymbol outerOf(TypeElement te) {
        return te.getEnclosingElement() instanceof TypeElement outer ? symbol(outer) : null;
    }

    private static ClassKind javaKind(TypeElement te) {
        return switch (te.getKind()) {
            case INTERFACE -> ClassKind.INTERFACE;
            case ENUM -> ClassKind.ENUM_CLASS;
            case ANNOTATION_TYPE -> ClassKind.ANNOTATION_CLASS;
            default -> ClassKind.CLASS;
        };
    }
}
