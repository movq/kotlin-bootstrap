package kotlinbootstrap.java;

import java.util.Map;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;

/**
 * JVM method descriptors of functions, as kotlinc's computeJvmDescriptor:
 * {@code name(params)return} with erased types, such as
 * {@code initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;}. Those of
 * JDK methods come from their elements; those of Kotlin functions from
 * their types, mapped as on the JVM.
 */
public final class JvmDescriptors {
    private static final Map<String, String> PRIMITIVES = Map.of("kotlin.Boolean", "Z", "kotlin.Char", "C",
            "kotlin.Byte", "B", "kotlin.Short", "S", "kotlin.Int", "I", "kotlin.Long", "J", "kotlin.Float", "F",
            "kotlin.Double", "D");

    private JvmDescriptors() {
    }

    public static String descriptor(FunctionSymbol f) {
        StringBuilder b = new StringBuilder(f.name()).append('(');
        if (f.source() instanceof ExecutableElement e) {
            for (var p : e.getParameters()) b.append(descriptor(p.asType()));
            return b.append(')').append(descriptor(e.getReturnType())).toString();
        }
        if (f.signature().receiver() != null) b.append(descriptor(f.signature().receiver()));
        for (ValueParamSymbol p : f.signature().params()) {
            b.append(p.isVararg() ? "[" + descriptor(p.type(), true) : descriptor(p.type()));
        }
        b.append(')');
        boolean isUnit = f.returnType() instanceof KType.ClassType c && !c.nullable()
                && c.symbol().id().fqName().equals("kotlin.Unit");
        return b.append(f.isConstructor() || isUnit ? "V" : descriptor(f.returnType())).toString();
    }

    /** The internal name of a class, as {@code java/util/Map$Entry}. */
    public static String internalName(ClassId id) {
        String pkg = id.packageName().replace('.', '/');
        String name = id.relativeName().replace('.', '$');
        return pkg.isEmpty() ? name : pkg + "/" + name;
    }

    // ==== Kotlin types ====

    private static String descriptor(KType type) {
        return descriptor(type, false);
    }

    /** @param boxed whether primitives are boxed, as array elements are */
    private static String descriptor(KType type, boolean boxed) {
        return switch (type) {
            case KType.Flexible f -> descriptor(f.lower(), boxed);
            case KType.DefinitelyNotNull d -> descriptor(d.inner(), boxed);
            case KType.TypeParamType p -> descriptor(p.symbol().bounds().getFirst(), true);
            case KType.ClassType c -> classDescriptor(c, boxed);
            default -> "Ljava/lang/Object;";
        };
    }

    private static String classDescriptor(KType.ClassType c, boolean boxed) {
        ClassId id = c.symbol().id();
        String fqName = id.fqName();
        String primitive = PRIMITIVES.get(fqName);
        if (primitive != null && !c.nullable() && !boxed) return primitive;
        if (fqName.equals("kotlin.Array")) {
            TypeArg element = c.args().getFirst();
            return "[" + (element.isStar() ? "Ljava/lang/Object;" : descriptor(element.type(), true));
        }
        if (id.packageName().equals("kotlin") && id.relativeName().endsWith("Array")) {
            String elementPrimitive = PRIMITIVES.get(fqName.substring(0, fqName.length() - "Array".length()));
            if (elementPrimitive != null) return "[" + elementPrimitive;
        }
        if (fqName.equals("kotlin.Nothing")) return "Ljava/lang/Void;";
        if (FunctionTypes.kind(c.symbol()) == FunctionTypes.Kind.FUNCTION) {
            return "Lkotlin/jvm/functions/Function" + (c.args().size() - 1) + ";";
        }
        ClassId java = JavaTypeMapping.javaClass(id);
        return "L" + internalName(java != null ? java : id) + ";";
    }

    // ==== Java types ====

    private static String descriptor(TypeMirror t) {
        return switch (t.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case CHAR -> "C";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> "[" + descriptor(((ArrayType) t).getComponentType());
            case DECLARED -> "L" + binaryName((TypeElement) ((DeclaredType) t).asElement()) + ";";
            case TYPEVAR -> descriptor(((TypeVariable) t).getUpperBound());
            case INTERSECTION -> descriptor(((IntersectionType) t).getBounds().getFirst());
            default -> "Ljava/lang/Object;";
        };
    }

    private static String binaryName(TypeElement e) {
        Element enclosing = e.getEnclosingElement();
        return switch (enclosing) {
            case TypeElement outer -> binaryName(outer) + "$" + e.getSimpleName();
            case PackageElement p when !p.isUnnamed() -> p.getQualifiedName().toString().replace('.', '/') + "/"
                    + e.getSimpleName();
            default -> e.getSimpleName().toString();
        };
    }
}
