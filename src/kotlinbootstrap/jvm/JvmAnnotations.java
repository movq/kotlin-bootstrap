package kotlinbootstrap.jvm;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attribute;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import kotlinbootstrap.check.ConstantValue;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.Types;

/**
 * Annotations in class files, as kotlinc's AnnotationCodegen: those of
 * RUNTIME retention visible, of BINARY (or Java's CLASS) invisible, and
 * of SOURCE none; an annotation class gets the Java Retention and Target
 * its Kotlin ones say.
 */
final class JvmAnnotations {
    /** Where an annotation can go, as kotlin.annotation.AnnotationTarget's entries. */
    static final Set<String> DEFAULT_TARGETS = Set.of("CLASS", "PROPERTY", "FIELD", "LOCAL_VARIABLE", "VALUE_PARAMETER",
            "CONSTRUCTOR", "FUNCTION", "PROPERTY_GETTER", "PROPERTY_SETTER");
    /** Kotlin's annotation targets as Java's ElementTypes, as kotlinc's. */
    private static final Map<String, String> ELEMENT_TYPES = Map.of("CLASS", "TYPE",
            "ANNOTATION_CLASS", "ANNOTATION_TYPE", "FIELD", "FIELD", "LOCAL_VARIABLE", "LOCAL_VARIABLE",
            "VALUE_PARAMETER", "PARAMETER", "CONSTRUCTOR", "CONSTRUCTOR", "FUNCTION", "METHOD",
            "PROPERTY_GETTER", "METHOD", "PROPERTY_SETTER", "METHOD", "TYPE_PARAMETER", "TYPE_PARAMETER");
    private static final ClassDesc KCLASS = ClassDesc.of("kotlin.reflect.KClass");

    private final JvmBackend backend;

    JvmAnnotations(JvmBackend backend) {
        this.backend = backend;
    }

    // ==== Values ====

    /** The JVM type of an annotation class's member: KClass as Class, unsigned types as signed ones. */
    ClassDesc memberType(KType t) {
        ClassSymbol v = TypeMapper.valueClass(t);
        if (v != null) return backend.types.underlyingType(v);
        if (t instanceof KType.ClassType c && TypeMapper.isClass(c.symbol(), "kotlin.Array")) {
            TypeArg element = c.args().isEmpty() ? TypeArg.STAR : c.args().getFirst();
            return element.isStar() ? TypeMapper.OBJECT.arrayType() : memberType(element.type()).arrayType();
        }
        ClassDesc d = backend.types.mapType(t);
        return d.equals(KCLASS) ? ConstantDescs.CD_Class : d;
    }

    /** The type of the members of an annotation class's parameter, a vararg's array. */
    ClassDesc memberType(ValueParamSymbol p) {
        if (!p.isVararg()) return memberType(p.type());
        ClassDesc element = memberType(p.type());
        return element.arrayType();
    }

    AnnotationValue value(ConstantValue v, ClassDesc type) {
        return switch (v) {
            case ConstantValue.Primitive p -> primitive(p.value(), type);
            case ConstantValue.EnumEntry e -> AnnotationValue.ofEnum(
                    backend.types.classDesc((ClassSymbol) e.entry().owner()), e.entry().name());
            case ConstantValue.ClassLiteral c -> AnnotationValue.ofClass(backend.types.mapType(c.classType()));
            case ConstantValue.Array a -> {
                ClassDesc element = type.isArray() ? type.componentType() : type;
                List<AnnotationValue> values = new ArrayList<>();
                for (ConstantValue e : a.elements()) values.add(value(e, element));
                yield AnnotationValue.ofArray(values);
            }
            case ConstantValue.Annotation a -> AnnotationValue.ofAnnotation(annotation(a));
        };
    }

    private static AnnotationValue primitive(Object value, ClassDesc type) {
        if (value instanceof String s) return AnnotationValue.ofString(s);
        if (value instanceof Boolean b) return AnnotationValue.ofBoolean(b);
        if (value instanceof Character c) return AnnotationValue.ofChar(c);
        Number n = (Number) value;
        return switch (type.descriptorString()) {
            case "B" -> AnnotationValue.ofByte(n.byteValue());
            case "S" -> AnnotationValue.ofShort(n.shortValue());
            case "J" -> AnnotationValue.ofLong(n.longValue());
            case "F" -> AnnotationValue.ofFloat(n.floatValue());
            case "D" -> AnnotationValue.ofDouble(n.doubleValue());
            case "C" -> AnnotationValue.ofChar((char) n.intValue());
            default -> AnnotationValue.ofInt(n.intValue());
        };
    }

    Annotation annotation(ConstantValue.Annotation a) {
        FunctionSymbol k = a.constructor();
        List<AnnotationElement> elements = new ArrayList<>();
        a.args().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            ValueParamSymbol p = k.signature().params().get(e.getKey());
            elements.add(AnnotationElement.of(p.name(), value(e.getValue(), memberType(p))));
        });
        return Annotation.of(backend.types.mapType(a.type()), elements);
    }

    // ==== Retention and targets ====

    /** RUNTIME, BINARY or SOURCE: an annotation class's retention, as Kotlin's for Java's. */
    String retention(ClassSymbol cls) {
        String java = cls.javaRetention();
        if (java != null) return java.equals("CLASS") ? "BINARY" : java;
        ConstantValue.Annotation r = metaAnnotation(cls, "kotlin.annotation.Retention");
        if (r != null && r.args().get(0) instanceof ConstantValue.EnumEntry e) return e.entry().name();
        return "RUNTIME";
    }

    /** The targets of an annotation class, as AnnotationTarget's entries; null for a Java one's, unknown. */
    Set<String> targets(ClassSymbol cls) {
        if (!(cls instanceof KotlinClassSymbol)) return null;
        ConstantValue.Annotation t = metaAnnotation(cls, "kotlin.annotation.Target");
        if (t == null) return DEFAULT_TARGETS;
        Set<String> result = new LinkedHashSet<>();
        if (t.args().get(0) instanceof ConstantValue.Array a) {
            for (ConstantValue e : a.elements()) if (e instanceof ConstantValue.EnumEntry entry) result.add(entry.entry().name());
        }
        return result;
    }

    /** An annotation of an annotation class from the sources, checked; null if none. */
    private ConstantValue.Annotation metaAnnotation(ClassSymbol cls, String fqName) {
        if (!(cls instanceof KotlinClassSymbol k)) return null;
        for (kotlinbootstrap.ast.Annotation a : k.decl().modifiers().annotations()) {
            ConstantValue.Annotation c = checked(a);
            if (c != null && TypeMapper.isClass(c.type(), fqName)) return c;
        }
        return null;
    }

    /** The checked annotation; null where it was not checked, such as in code that is never generated. */
    ConstantValue.Annotation checked(kotlinbootstrap.ast.Annotation a) {
        try {
            return backend.checker.checkedAnnotation(a);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * The Java meta-annotations of an annotation class: Retention for its
     * Kotlin one, and Target for its Kotlin one, as kotlinc's.
     */
    List<Annotation> javaMetaAnnotations(KotlinClassSymbol cls) {
        List<Annotation> result = new ArrayList<>();
        String policy = switch (retention(cls)) {
            case "SOURCE" -> "SOURCE";
            case "BINARY" -> "CLASS";
            default -> "RUNTIME";
        };
        result.add(Annotation.of(ClassDesc.of("java.lang.annotation.Retention"), AnnotationElement.of("value",
                AnnotationValue.ofEnum(ClassDesc.of("java.lang.annotation.RetentionPolicy"), policy))));
        if (metaAnnotation(cls, "kotlin.annotation.Target") != null) {
            Set<String> types = new LinkedHashSet<>();
            for (String t : targets(cls)) if (ELEMENT_TYPES.containsKey(t)) types.add(ELEMENT_TYPES.get(t));
            List<AnnotationValue> values = new ArrayList<>();
            for (String t : types) values.add(AnnotationValue.ofEnum(ClassDesc.of("java.lang.annotation.ElementType"), t));
            result.add(Annotation.of(ClassDesc.of("java.lang.annotation.Target"),
                    AnnotationElement.of("value", AnnotationValue.ofArray(values))));
        }
        return result;
    }

    // ==== Placement ====

    /**
     * The checked annotations of a declaration that go on one element of
     * it: those with its use-site target, and those without one whose first
     * applicable target among the declaration's, in order, is one of the
     * element's, as kotlinc's (a constructor's property parameter's go on
     * the parameter, else the property, else the field).
     *
     * @param useSite    the use-site target of the element, such as "field"
     * @param candidates the targets the declaration's annotations may have, in order
     * @param element    those of the candidates that are the element's
     */
    List<ConstantValue.Annotation> select(List<kotlinbootstrap.ast.Annotation> annotations, String useSite,
                                          List<String> candidates, Set<String> element) {
        List<ConstantValue.Annotation> result = new ArrayList<>();
        for (kotlinbootstrap.ast.Annotation a : annotations) {
            if (a.target() != null && !a.target().equals(useSite)) continue;
            ConstantValue.Annotation c = checked(a);
            if (c == null || !(Types.lowerBound(c.type()) instanceof KType.ClassType t)) continue;
            if (a.target() == null) {
                Set<String> targets = targets(t.symbol());
                String first = null;
                for (String candidate : candidates) {
                    if (targets == null ? !candidate.equals("PROPERTY") : targets.contains(candidate)) {
                        first = candidate;
                        break;
                    }
                }
                if (first == null || !element.contains(first)) continue;
            }
            result.add(c);
        }
        return result;
    }

    /** The annotations of a declaration with one element, without use-site targets. */
    List<ConstantValue.Annotation> select(List<kotlinbootstrap.ast.Annotation> annotations, String... targets) {
        return select(annotations, null, List.of(targets), Set.of(targets));
    }

    // ==== Attributes ====

    /** Adds the attributes of annotations: the visible ones, then the invisible ones. */
    void attributes(List<ConstantValue.Annotation> annotations, List<Annotation> extra, Consumer<Attribute<?>> sink) {
        List<Annotation> visible = new ArrayList<>(extra);
        List<Annotation> invisible = new ArrayList<>();
        for (ConstantValue.Annotation a : annotations) {
            if (!(Types.lowerBound(a.type()) instanceof KType.ClassType c)) continue;
            switch (retention(c.symbol())) {
                case "RUNTIME" -> visible.add(annotation(a));
                case "BINARY" -> invisible.add(annotation(a));
                default -> {
                }
            }
        }
        if (!visible.isEmpty()) sink.accept(RuntimeVisibleAnnotationsAttribute.of(visible));
        if (!invisible.isEmpty()) sink.accept(RuntimeInvisibleAnnotationsAttribute.of(invisible));
    }

    /** Adds the attributes of the annotations of a method's parameters. */
    void parameterAttributes(List<List<ConstantValue.Annotation>> params, Consumer<Attribute<?>> sink) {
        if (params.stream().allMatch(List::isEmpty)) return;
        List<List<Annotation>> visible = new ArrayList<>();
        List<List<Annotation>> invisible = new ArrayList<>();
        boolean anyVisible = false;
        boolean anyInvisible = false;
        for (List<ConstantValue.Annotation> as : params) {
            List<Annotation> v = new ArrayList<>();
            List<Annotation> i = new ArrayList<>();
            for (ConstantValue.Annotation a : as) {
                if (!(Types.lowerBound(a.type()) instanceof KType.ClassType c)) continue;
                switch (retention(c.symbol())) {
                    case "RUNTIME" -> v.add(annotation(a));
                    case "BINARY" -> i.add(annotation(a));
                    default -> {
                    }
                }
            }
            visible.add(v);
            invisible.add(i);
            anyVisible |= !v.isEmpty();
            anyInvisible |= !i.isEmpty();
        }
        if (anyVisible) sink.accept(RuntimeVisibleParameterAnnotationsAttribute.of(visible));
        if (anyInvisible) sink.accept(RuntimeInvisibleParameterAnnotationsAttribute.of(invisible));
    }
}
