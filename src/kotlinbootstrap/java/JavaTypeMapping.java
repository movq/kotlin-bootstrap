package kotlinbootstrap.java;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import kotlinbootstrap.symbols.ClassId;

/**
 * The Java classes that Kotlin sees as Kotlin classes, as in kotlinc's
 * JavaToKotlinClassMap. Collection interfaces map to a read-only and a
 * mutable Kotlin interface; a Java collection type is seen as the flexible
 * type ranging over both.
 */
final class JavaTypeMapping {
    /** @param mutable the mutable variant, or null if the same as readOnly */
    record Mapped(ClassId readOnly, ClassId mutable) {
    }

    private static final Map<String, Mapped> MAP = new HashMap<>();

    static {
        add("java.lang.Object", "kotlin.Any");
        add("java.lang.String", "kotlin.String");
        add("java.lang.CharSequence", "kotlin.CharSequence");
        add("java.lang.Throwable", "kotlin.Throwable");
        add("java.lang.Cloneable", "kotlin.Cloneable");
        add("java.lang.Number", "kotlin.Number");
        add("java.lang.Comparable", "kotlin.Comparable");
        add("java.lang.Enum", "kotlin.Enum");
        add("java.lang.annotation.Annotation", "kotlin.Annotation");
        add("java.lang.Boolean", "kotlin.Boolean");
        add("java.lang.Character", "kotlin.Char");
        add("java.lang.Byte", "kotlin.Byte");
        add("java.lang.Short", "kotlin.Short");
        add("java.lang.Integer", "kotlin.Int");
        add("java.lang.Long", "kotlin.Long");
        add("java.lang.Float", "kotlin.Float");
        add("java.lang.Double", "kotlin.Double");
        collection("java.lang.Iterable", "kotlin.collections.Iterable", "kotlin.collections.MutableIterable");
        collection("java.util.Iterator", "kotlin.collections.Iterator", "kotlin.collections.MutableIterator");
        collection("java.util.Collection", "kotlin.collections.Collection", "kotlin.collections.MutableCollection");
        collection("java.util.List", "kotlin.collections.List", "kotlin.collections.MutableList");
        collection("java.util.Set", "kotlin.collections.Set", "kotlin.collections.MutableSet");
        collection("java.util.ListIterator", "kotlin.collections.ListIterator",
                "kotlin.collections.MutableListIterator");
        collection("java.util.Map", "kotlin.collections.Map", "kotlin.collections.MutableMap");
        collection("java.util.Map.Entry", "kotlin.collections.Map.Entry", "kotlin.collections.MutableMap.MutableEntry");
    }

    private static void add(String java, String kotlin) {
        MAP.put(java, new Mapped(id(kotlin), null));
    }

    private static void collection(String java, String readOnly, String mutable) {
        MAP.put(java, new Mapped(id(readOnly), id(mutable)));
    }

    /** A class id from a name in which packages are lower case and classes capitalized. */
    private static ClassId id(String fqName) {
        String[] parts = fqName.split("\\.");
        int k = 0;
        while (Character.isLowerCase(parts[k].charAt(0))) k++;
        return new ClassId(String.join(".", Arrays.copyOfRange(parts, 0, k)),
                String.join(".", Arrays.copyOfRange(parts, k, parts.length)));
    }

    /** The Kotlin view of a Java class, by canonical name, or null if it is not mapped. */
    static Mapped get(String javaCanonicalName) {
        return MAP.get(javaCanonicalName);
    }

    private JavaTypeMapping() {
    }
}
