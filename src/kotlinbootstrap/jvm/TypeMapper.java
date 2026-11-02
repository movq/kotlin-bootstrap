package kotlinbootstrap.jvm;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.java.JavaTypeMapping;
import kotlinbootstrap.java.JvmDescriptors;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.Types;

/**
 * Kotlin types as JVM types, as kotlinc's KotlinTypeMapper: primitives
 * where not nullable, builtins as the Java classes they are, type
 * parameters as the erasure of their bound, and Unit and Nothing as void
 * where a function returns them.
 */
final class TypeMapper {
    static final ClassDesc OBJECT = ConstantDescs.CD_Object;
    static final ClassDesc STRING = ConstantDescs.CD_String;
    static final ClassDesc UNIT = ClassDesc.ofInternalName("kotlin/Unit");
    static final ClassDesc VOID = ConstantDescs.CD_void;

    private static final Map<String, ClassDesc> PRIMITIVES = Map.of(
            "Boolean", ConstantDescs.CD_boolean, "Char", ConstantDescs.CD_char,
            "Byte", ConstantDescs.CD_byte, "Short", ConstantDescs.CD_short,
            "Int", ConstantDescs.CD_int, "Long", ConstantDescs.CD_long,
            "Float", ConstantDescs.CD_float, "Double", ConstantDescs.CD_double);
    private static final Map<String, ClassDesc> PRIMITIVE_ARRAYS = Map.of(
            "BooleanArray", ConstantDescs.CD_boolean, "CharArray", ConstantDescs.CD_char,
            "ByteArray", ConstantDescs.CD_byte, "ShortArray", ConstantDescs.CD_short,
            "IntArray", ConstantDescs.CD_int, "LongArray", ConstantDescs.CD_long,
            "FloatArray", ConstantDescs.CD_float, "DoubleArray", ConstantDescs.CD_double);

    private final JvmNames names;
    private final Resolver resolver;
    private final Map<ClassSymbol, String> localNames = new IdentityHashMap<>();
    private Map<String, ClassSymbol> byInternalName;

    TypeMapper(JvmNames names, Resolver resolver) {
        this.names = names;
        this.resolver = resolver;
    }

    // ==== Classes ====

    /** Names a local or anonymous class, as the class that compiles it found it. */
    void setLocalName(ClassSymbol cls, String internalName) {
        localNames.put(cls, internalName);
        if (byInternalName != null) byInternalName.put(internalName, cls);
    }

    /** The source class of an internal name; null if not a Kotlin class of the sources. */
    ClassSymbol classByInternalName(String internalName) {
        if (byInternalName == null) {
            byInternalName = new HashMap<>();
            for (ClassifierSymbol c : resolver.table().kotlinClassifiers().values()) {
                if (c instanceof ClassSymbol cls && !cls.isLocal() && JavaTypeMapping.javaClass(cls.id()) == null) {
                    byInternalName.put(internalName(cls), cls);
                }
            }
            localNames.forEach((cls, name) -> byInternalName.put(name, cls));
        }
        return byInternalName.get(internalName);
    }

    /** The JVM name of a class: a builtin's Java class, a nested class as Outer$Nested. */
    String internalName(ClassSymbol cls) {
        String local = localNames.get(cls);
        if (local != null) return local;
        ClassId id = cls.id();
        String rel = id.relativeName();
        if (id.packageName().equals("kotlin")) {
            if (rel.equals("Nothing")) return "java/lang/Void";
            if (rel.startsWith("Function") && isNumber(rel.substring(8))) return "kotlin/jvm/functions/" + rel;
            if (rel.equals("Array")) return "[Ljava/lang/Object;";
            if (PRIMITIVE_ARRAYS.containsKey(rel)) return "[" + PRIMITIVE_ARRAYS.get(rel).descriptorString();
        }
        if (id.packageName().equals("kotlin.coroutines") && rel.startsWith("SuspendFunction")
                && isNumber(rel.substring(15))) {
            return "kotlin/jvm/functions/Function" + (Integer.parseInt(rel.substring(15)) + 1);
        }
        // Function references are function objects, not reflective ones, so not KFunctions.
        if (id.packageName().equals("kotlin.reflect") && rel.startsWith("KFunction") && isNumber(rel.substring(9))) {
            return "kotlin/jvm/functions/Function" + rel.substring(9);
        }
        if (id.packageName().equals("kotlin.reflect") && rel.startsWith("KSuspendFunction")
                && isNumber(rel.substring(16))) {
            return "kotlin/jvm/functions/Function" + (Integer.parseInt(rel.substring(16)) + 1);
        }
        ClassId java = JavaTypeMapping.javaClass(id);
        if (java != null) id = java;
        return JvmDescriptors.internalName(id);
    }

    private static boolean isNumber(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    ClassDesc classDesc(ClassSymbol cls) {
        String name = internalName(cls);
        return name.startsWith("[") ? ClassDesc.ofDescriptor(name) : ClassDesc.ofInternalName(name);
    }

    /** The primitive type a builtin class is when not nullable, or null. */
    static ClassDesc primitive(ClassSymbol cls) {
        return cls.id().packageName().equals("kotlin") ? PRIMITIVES.get(cls.id().relativeName()) : null;
    }

    /** The element type of a primitive array class, or null. */
    static ClassDesc primitiveArrayElement(ClassSymbol cls) {
        return cls.id().packageName().equals("kotlin") ? PRIMITIVE_ARRAYS.get(cls.id().relativeName()) : null;
    }

    static boolean isClass(ClassSymbol cls, String fqName) {
        return cls.id().fqName().equals(fqName);
    }

    static boolean isClass(KType t, String fqName) {
        return kotlinbootstrap.types.Types.lowerBound(t) instanceof KType.ClassType c && isClass(c.symbol(), fqName);
    }

    // ==== Types ====

    /** The JVM type of values of a type. */
    ClassDesc mapType(KType t) {
        return switch (t) {
            case KType.ClassType c -> {
                ClassDesc primitive = primitive(c.symbol());
                if (primitive != null) yield c.nullable() ? box(primitive) : primitive;
                if (isClass(c.symbol(), "kotlin.Array")) {
                    yield arrayOf(c.args().isEmpty() ? null : c.args().getFirst());
                }
                if (VALUE_CLASSES && isValueClass(c.symbol())) {
                    ClassDesc unboxed = unboxedType(c);
                    yield unboxed != null ? unboxed : classDesc(c.symbol());
                }
                yield classDesc(c.symbol());
            }
            case KType.TypeParamType p -> erasure(p.symbol());
            // A Java type's nullability, Int! as Integer.
            case KType.Flexible f -> mapType(f.upper());
            case KType.DefinitelyNotNull d -> mapType(d.inner());
            case KType.IntegerLiteral l -> mapType(l.possibleTypes().getFirst().withNullable(l.nullable()));
            case KType.Intersection i -> mapType(i.components().getFirst());
            case KType.Captured c -> c.constructor().supertypes() == null || c.constructor().supertypes().isEmpty()
                    ? OBJECT : mapBoxedType(c.constructor().supertypes().getFirst());
            case KType.TypeVariableType v -> OBJECT;
        };
    }

    /** The JVM type of a value of a type in a generic position: primitives and value classes boxed. */
    ClassDesc mapBoxedType(KType t) {
        ClassSymbol v = VALUE_CLASSES ? valueClass(t) : null;
        if (v != null) return classDesc(v);
        ClassDesc d = mapType(t);
        return d.isPrimitive() ? box(d) : d;
    }

    // ==== Value classes ====

    static final boolean VALUE_CLASSES = false;

    /** Whether a class is a value class, kept as its underlying value where its type is known, as kotlinc's. */
    static boolean isValueClass(ClassSymbol cls) {
        return cls instanceof KotlinClassSymbol k && (k.has(Modifier.VALUE) || k.has(Modifier.INLINE))
                && !k.isInterface() && k.decl().primaryConstructor() != null
                && k.decl().primaryConstructor().params().size() == 1;
    }

    /** The value class a type is of, nullable or not; null if none. */
    static ClassSymbol valueClass(KType t) {
        KType lower = Types.lowerBound(t);
        if (lower instanceof KType.DefinitelyNotNull d) lower = d.inner();
        return lower instanceof KType.ClassType c && isValueClass(c.symbol()) ? c.symbol() : null;
    }

    /** The property a value class keeps its value in. */
    static PropertySymbol underlyingProperty(ClassSymbol v) {
        Decl.Param param = ((KotlinClassSymbol) v).decl().primaryConstructor().params().getFirst();
        for (PropertySymbol p : v.members().properties(param.name())) if (p.source() == param) return p;
        throw new IllegalStateException("No underlying property in " + v);
    }

    /**
     * The JVM type a value class's values are where its type is known, as
     * kotlinc's InlineClassAbi.unboxType; null where they are boxed: a
     * nullable type whose underlying type is nullable or primitive.
     */
    ClassDesc unboxedType(KType.ClassType t) {
        KType underlying = underlyingProperty(t.symbol()).returnType();
        ClassDesc d = mapType(underlying);
        if (!t.nullable()) return d;
        if (isNullable(underlying) || d.isPrimitive()) return null;
        if (valueClass(underlying) != null && underlying instanceof KType.ClassType u && unboxedType(u.withNullable(true)) == null) {
            return null;
        }
        return d;
    }

    /** Whether null is a value of a type: marked nullable, or a type parameter without a non-null bound. */
    private static boolean isNullable(KType t) {
        if (Types.isMarkedNullable(t)) return true;
        if (t instanceof KType.TypeParamType p) {
            for (KType b : p.symbol().bounds()) if (!isNullable(b)) return false;
            return true;
        }
        return false;
    }

    /** The underlying JVM type of a value class's values, as unboxed. */
    ClassDesc underlyingType(ClassSymbol v) {
        return mapType(underlyingProperty(v).returnType());
    }

    private ClassDesc arrayOf(TypeArg element) {
        if (element == null || element.isStar()) return OBJECT.arrayType();
        return mapBoxedType(element.type()).arrayType();
    }

    /** A type parameter as its first bound, erased. */
    ClassDesc erasure(TypeParamSymbol p) {
        if (p.bounds().isEmpty()) return OBJECT;
        return mapBoxedType(p.bounds().getFirst());
    }

    static boolean isUnitOrNothing(KType t) {
        return t instanceof KType.ClassType c && !c.nullable()
                && (isClass(c.symbol(), "kotlin.Unit") || isClass(c.symbol(), "kotlin.Nothing"));
    }

    static boolean isNothing(KType t) {
        return t instanceof KType.ClassType c && !c.nullable() && isClass(c.symbol(), "kotlin.Nothing");
    }

    static boolean isUnit(KType t) {
        return t instanceof KType.ClassType c && !c.nullable() && isClass(c.symbol(), "kotlin.Unit");
    }

    static ClassDesc box(ClassDesc primitive) {
        return switch (primitive.descriptorString()) {
            case "Z" -> ConstantDescs.CD_Boolean;
            case "C" -> ConstantDescs.CD_Character;
            case "B" -> ConstantDescs.CD_Byte;
            case "S" -> ConstantDescs.CD_Short;
            case "I" -> ConstantDescs.CD_Integer;
            case "J" -> ConstantDescs.CD_Long;
            case "F" -> ConstantDescs.CD_Float;
            case "D" -> ConstantDescs.CD_Double;
            default -> primitive;
        };
    }

    /** The primitive type a box class holds, or null. */
    static ClassDesc unbox(ClassDesc box) {
        return switch (box.descriptorString()) {
            case "Ljava/lang/Boolean;" -> ConstantDescs.CD_boolean;
            case "Ljava/lang/Character;" -> ConstantDescs.CD_char;
            case "Ljava/lang/Byte;" -> ConstantDescs.CD_byte;
            case "Ljava/lang/Short;" -> ConstantDescs.CD_short;
            case "Ljava/lang/Integer;" -> ConstantDescs.CD_int;
            case "Ljava/lang/Long;" -> ConstantDescs.CD_long;
            case "Ljava/lang/Float;" -> ConstantDescs.CD_float;
            case "Ljava/lang/Double;" -> ConstantDescs.CD_double;
            default -> null;
        };
    }

    // ==== Signatures ====

    /** The type a function returns: void for Unit and Nothing, unless it overrides a function returning a value. */
    ClassDesc mapReturnType(CallableSymbol f) {
        if (f instanceof FunctionSymbol fn && fn.isConstructor()) return VOID;
        if (f.source() instanceof ExecutableElement e) return MethodTypeDesc.ofDescriptor(javaDescriptor(fn(f))).returnType();
        KType t = f.returnType();
        if (f instanceof FunctionSymbol && isUnitOrNothing(t) && !overridesNonVoid(f)) return VOID;
        if (forceBoxedReturnType(f)) return mapBoxedType(t);
        return mapType(t);
    }

    private static FunctionSymbol fn(CallableSymbol f) {
        return (FunctionSymbol) f;
    }

    private static String javaDescriptor(FunctionSymbol f) {
        String d = JvmDescriptors.descriptor(f);
        return d.substring(d.indexOf('('));
    }

    private boolean overridesNonVoid(CallableSymbol f) {
        for (CallableSymbol o : Overrides.overridden(f)) {
            if (!mapReturnType(o).equals(VOID)) return true;
        }
        return false;
    }

    /** A primitive return type is boxed where the function overrides one returning a reference. */
    private boolean forceBoxedReturnType(CallableSymbol f) {
        if (!mapType(f.returnType()).isPrimitive()) return false;
        for (CallableSymbol o : Overrides.overridden(f)) {
            if (o.source() instanceof ExecutableElement || o.source() instanceof VariableElement) {
                if (!mapReturnType(o).isPrimitive()) return true;
                continue;
            }
            if (!mapType(o.returnType()).isPrimitive() || forceBoxedReturnType(o)) return true;
        }
        return false;
    }

    /** The parameters of a function on the JVM: context parameters, the extension receiver, then value parameters. */
    List<ClassDesc> parameterTypes(CallableSymbol f) {
        List<ClassDesc> result = new ArrayList<>();
        for (KType c : f.signature().contextParams()) result.add(mapType(c));
        if (f.signature().receiver() != null) result.add(mapType(f.signature().receiver()));
        if (f instanceof FunctionSymbol) {
            for (ValueParamSymbol p : f.signature().params()) {
                result.add(p.isVararg() ? varargArray(p.type()) : mapType(p.type()));
            }
        }
        return result;
    }

    /** The array a vararg parameter of the element type is. */
    ClassDesc varargArray(KType element) {
        if (element instanceof KType.ClassType c && !c.nullable() && primitive(c.symbol()) != null) {
            return primitive(c.symbol()).arrayType();
        }
        return mapBoxedType(element).arrayType();
    }

    /** The descriptor of a function, as declared: of the element for a Java class file's. */
    MethodTypeDesc methodType(FunctionSymbol f) {
        if (f.source() instanceof ExecutableElement) return MethodTypeDesc.ofDescriptor(javaDescriptor(f));
        return MethodTypeDesc.of(mapReturnType(f), parameterTypes(f));
    }

    ClassDesc fieldType(PropertySymbol p) {
        if (p.source() instanceof VariableElement e) return ClassDesc.ofDescriptor(JvmDescriptors.descriptor(e.asType()));
        return mapType(p.returnType());
    }

    MethodTypeDesc getterType(PropertySymbol p) {
        if (p.javaGetter() != null) return methodType(p.javaGetter());
        KType t = p.returnType();
        return MethodTypeDesc.of(forceBoxedReturnType(p) ? mapBoxedType(t) : mapType(t), parameterTypes(p));
    }

    MethodTypeDesc setterType(PropertySymbol p) {
        if (p.javaSetter() != null) return methodType(p.javaSetter());
        List<ClassDesc> params = parameterTypes(p);
        params.add(mapType(p.returnType()));
        return MethodTypeDesc.of(VOID, params);
    }

    /** Applies a substitution of reified type parameters, if any. */
    static KType substitute(Substitution s, KType t) {
        return s == null || s.isEmpty() ? t : s.apply(t);
    }
}
