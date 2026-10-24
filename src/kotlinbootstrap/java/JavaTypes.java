package kotlinbootstrap.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.SymbolTable;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.Variance;

/** Builds the Kotlin view of Java types, for both compiled and source Java classes. */
final class JavaTypes {
    /** What is known about the nullability of a Java type, from annotations. */
    enum Nullability { UNKNOWN, NOT_NULL, NULLABLE }

    private static final Set<String> NOT_NULL_ANNOTATIONS = Set.of("NotNull", "NonNull", "Nonnull");
    private static final Set<String> NULLABLE_ANNOTATIONS = Set.of("Nullable", "CheckForNull");

    private final SymbolTable table;

    JavaTypes(SymbolTable table) {
        this.table = table;
    }

    /** Nullability from annotations, by simple name, as kotlinc recognizes several libraries' annotations. */
    static Nullability nullability(List<String> annotationNames) {
        for (String name : annotationNames) {
            if (NOT_NULL_ANNOTATIONS.contains(name)) return Nullability.NOT_NULL;
            if (NULLABLE_ANNOTATIONS.contains(name)) return Nullability.NULLABLE;
        }
        return Nullability.UNKNOWN;
    }

    static KType withNullability(KType t, Nullability n) {
        return switch (n) {
            case UNKNOWN -> t;
            case NOT_NULL -> t instanceof KType.Flexible f ? flexibleOrNot(f.lower(), f.upper().withNullable(false))
                    : t.withNullable(false);
            case NULLABLE -> t instanceof KType.Flexible f ? flexibleOrNot(f.lower().withNullable(true), f.upper())
                    : t.withNullable(true);
        };
    }

    private static KType flexibleOrNot(KType lower, KType upper) {
        return lower.equals(upper) ? lower : new KType.Flexible(lower, upper);
    }

    /** A primitive type, or Unit for void. */
    KType primitive(String kotlinName) {
        return new KType.ClassType(table.requireClass("kotlin." + kotlinName), List.of(), false);
    }

    /**
     * A Java class type, with arguments for the class (and outer classes of
     * an inner class). Mapped classes become Kotlin ones; collections range
     * over the mutable and read-only interfaces. No arguments for a generic
     * class means a raw type.
     */
    KType classType(ClassSymbol cls, List<TypeArg> args) {
        ClassSymbol readOnly = cls;
        ClassSymbol mutable = cls;
        JavaTypeMapping.Mapped mapped = JavaTypeMapping.get(cls.id().fqName());
        if (mapped != null) {
            readOnly = table.findClass(mapped.readOnly());
            mutable = mapped.mutable() == null ? readOnly : table.findClass(mapped.mutable());
        } else if (cls.id().packageName().equals("kotlin.jvm.functions") && cls.name().matches("Function\\d+")) {
            readOnly = mutable = table.requireClass("kotlin." + cls.name());
        }
        List<TypeArg> upperArgs = args;
        if (args.isEmpty() && !readOnly.typeParams().isEmpty()) {
            // A raw type: (C<Any?>..C<*>?), roughly as kotlinc does it.
            args = new ArrayList<>();
            upperArgs = new ArrayList<>();
            for (int i = 0; i < readOnly.typeParams().size(); i++) {
                args.add(TypeArg.of(primitive("Any").withNullable(true)));
                upperArgs.add(TypeArg.STAR);
            }
        }
        return new KType.Flexible(new KType.ClassType(mutable, args, false),
                new KType.ClassType(readOnly, upperArgs, true));
    }

    /** @param primitiveElement the Kotlin name of a primitive element type, or null */
    KType arrayType(String primitiveElement, KType element) {
        if (primitiveElement != null) {
            ClassSymbol cls = table.requireClass("kotlin." + primitiveElement + "Array");
            return new KType.Flexible(new KType.ClassType(cls, List.of(), false),
                    new KType.ClassType(cls, List.of(), true));
        }
        ClassSymbol array = table.requireClass("kotlin.Array");
        return new KType.Flexible(new KType.ClassType(array, List.of(TypeArg.of(element)), false),
                new KType.ClassType(array, List.of(new TypeArg(Variance.OUT, element)), true));
    }

    static KType typeParamType(TypeParamSymbol tp) {
        KType.TypeParamType t = new KType.TypeParamType(tp, false);
        return new KType.Flexible(t, t.withNullable(true));
    }

    /** The Kotlin view of a Java supertype: not flexible. */
    static KType supertype(KType t) {
        return t instanceof KType.Flexible f ? f.lower() : t;
    }

    KType any() {
        return primitive("Any");
    }

    ClassSymbol requireClass(ClassId id) {
        ClassSymbol cls = table.findClass(id);
        if (cls == null) throw new IllegalStateException("Missing class " + id);
        return cls;
    }
}
