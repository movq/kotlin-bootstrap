package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.java.JavaTypeMapping;
import kotlinbootstrap.java.JvmDescriptors;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.SymbolTable;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;

/**
 * The members of the JDK class a Kotlin builtin is on the JVM that the
 * builtin also has, as FIR's JvmMappedScope: {@code kotlin.Throwable} has
 * {@code initCause} of {@code java.lang.Throwable}, {@code kotlin.collections.Collection}
 * has {@code stream}. Which are visible, hidden or dropped is decided by
 * JvmBuiltInsSignatures; those the builtin declares itself, under the
 * same or another name, are left out. The mapped functions are copies
 * with the builtin as owner. Mapped constructors are not modelled.
 */
final class JvmMappedScope {
    /** As FIR's JDKMemberStatus. */
    enum Status { HIDDEN, VISIBLE, DROP, HIDDEN_IN_DECLARING_CLASS_ONLY }

    private final SymbolTable table;
    private final Map<ClassSymbol, Map<String, List<FunctionSymbol>>> functions = new HashMap<>();
    /** The status of each mapped function. */
    private final Map<FunctionSymbol, Status> statuses = new HashMap<>();
    /** For a Java function, the status of the first mapped function it overrides that is not visible; null if none. */
    private final Map<FunctionSymbol, Status> overriddenStatuses = new HashMap<>();

    JvmMappedScope(SymbolTable table) {
        this.table = table;
    }

    /** The Java class a Kotlin class gets members from, or null. */
    private ClassSymbol javaClass(ClassSymbol kotlinClass) {
        if (kotlinClass.origin() != Origin.KOTLIN_SOURCE) return null;
        ClassId java = JavaTypeMapping.javaClass(kotlinClass.id());
        // Any and function types get none, as in JvmBuiltInsCustomizer.getJavaAnalogue.
        if (java == null || kotlinClass.id().fqName().equals("kotlin.Any")
                || FunctionTypes.kind(kotlinClass) != null) {
            return null;
        }
        return table.findClass(java);
    }

    /** The mapped functions named so of a Kotlin class. */
    List<FunctionSymbol> functions(ClassSymbol kotlinClass, String name) {
        return functions.computeIfAbsent(kotlinClass, k -> new HashMap<>())
                .computeIfAbsent(name, n -> computeFunctions(kotlinClass, n));
    }

    private List<FunctionSymbol> computeFunctions(ClassSymbol kotlinClass, String name) {
        ClassSymbol javaClass = javaClass(kotlinClass);
        if (javaClass == null) return List.of();
        boolean isFinal = kotlinClass.modality() == Modality.FINAL;
        boolean isMutable = JavaTypeMapping.isMutable(kotlinClass.id());
        String prefix = JvmDescriptors.internalName(javaClass.id());
        // Final classes only get the visible ones, as they cannot be overridden.
        if (isFinal && kotlinClass.members().functions(name).isEmpty()
                && VISIBLE_METHOD_SIGNATURES.stream().noneMatch(s -> s.startsWith(prefix + "." + name + "(")
                && MUTABLE_METHOD_SIGNATURES.contains(s) == isMutable)) {
            return List.of();
        }
        Set<String> declaredSignatures = new HashSet<>();
        for (FunctionSymbol f : kotlinClass.members().functions(name)) declaredSignatures.add(JvmDescriptors.descriptor(f));
        ClassId mutableId = JavaTypeMapping.mutableClass(kotlinClass.id());
        ClassSymbol mutable = mutableId == null ? null : table.findClass(mutableId);
        if (mutable != null) {
            for (FunctionSymbol f : mutable.members().functions(name)) declaredSignatures.add(JvmDescriptors.descriptor(f));
        }
        List<FunctionSymbol> result = new ArrayList<>();
        for (FunctionSymbol f : javaClass.members().functions(name)) {
            if (f.isStatic() || f.visibility() != Visibility.PUBLIC && f.visibility() != Visibility.PROTECTED) continue;
            String descriptor = JvmDescriptors.descriptor(f);
            if (declaredSignatures.contains(descriptor)) continue;
            String signature = prefix + "." + descriptor;
            if (RENAMED_BUILTIN_SIGNATURES.contains(signature) || isOverrideOfBuiltinPropertyGetter(f, kotlinClass)) {
                continue;
            }
            List<CallableSymbol> overridden = allOverridden(f);
            if (overridden.stream().anyMatch(o -> !isJava(o))) continue;
            // Read-only collections do not get mutating methods; mutable ones get the rest from their read-only supertype.
            if (MUTABLE_METHOD_SIGNATURES.contains(signature) != isMutable) continue;
            if (overridden.stream().anyMatch(o -> o.owner() instanceof ClassSymbol c && JavaTypeMapping.isMutable(c.id()))) {
                continue;
            }
            Status status = status(javaClass, descriptor);
            if (status == Status.DROP || isFinal && status != Status.VISIBLE) continue;
            FunctionSymbol mapped = mappedFunction(f, javaClass, kotlinClass);
            statuses.put(mapped, status);
            result.add(mapped);
        }
        return result;
    }

    /** {@code getMessage} of Throwable is the property {@code message}, {@code size} of Collection the property {@code size}. */
    private static boolean isOverrideOfBuiltinPropertyGetter(FunctionSymbol f, ClassSymbol kotlinClass) {
        if (!f.signature().params().isEmpty()) return false;
        String owner = ((ClassSymbol) f.owner()).id().fqName();
        if (BUILTIN_PROPERTY_GETTERS.contains(owner + "." + f.name())) return true;
        String property = MemberScopes.propertyNameByGetterName(f.name());
        return property != null && !kotlinClass.members().properties(property).isEmpty();
    }

    /** As FIR's getJdkMethodStatus: by the first of the class and its supertypes whose method it is. */
    private Status status(ClassSymbol javaClass, String descriptor) {
        Set<String> classes = new LinkedHashSet<>();
        collectJavaSupertypes(javaClass, classes, new HashSet<>());
        for (String c : classes) {
            String signature = c + "." + descriptor;
            if (HIDDEN_METHOD_SIGNATURES.contains(signature)) return Status.HIDDEN;
            if (VISIBLE_METHOD_SIGNATURES.contains(signature)) return Status.VISIBLE;
            if (DROP_METHOD_SIGNATURES.contains(signature)) return Status.DROP;
        }
        return Status.HIDDEN_IN_DECLARING_CLASS_ONLY;
    }

    /** The internal names of the class and its supertypes, the Kotlin ones as the Java classes they are. */
    private static void collectJavaSupertypes(ClassSymbol cls, Set<String> out, Set<ClassSymbol> seen) {
        if (!seen.add(cls)) return;
        ClassId java = JavaTypeMapping.javaClass(cls.id());
        out.add(JvmDescriptors.internalName(java != null ? java : cls.id()));
        for (KType st : cls.supertypes()) {
            if (st instanceof KType.ClassType c) collectJavaSupertypes(c.symbol(), out, seen);
        }
    }

    /** A copy of a Java function with the Kotlin class as owner and its type parameters for the Java class's. */
    private static FunctionSymbol mappedFunction(FunctionSymbol f, ClassSymbol javaClass, ClassSymbol kotlinClass) {
        Substitution substitution = Substitution.ofTypes(javaClass.typeParams(), kotlinClass.typeParams().stream()
                .map(tp -> (KType) new KType.TypeParamType(tp, false)).toList());
        return new FunctionSymbol(f.name(), f.origin(), kotlinClass, f.visibility(), f.modality(), f.modifiers(), false,
                f.source(), f::typeParams,
                () -> {
                    List<ValueParamSymbol> params = new ArrayList<>();
                    for (ValueParamSymbol p : f.signature().params()) {
                        params.add(new ValueParamSymbol(p.name(), substitution.apply(p.type()), p.hasDefault(),
                                p.modifiers(), p.source()));
                    }
                    return new CallableSymbol.Signature(null, List.of(), params);
                },
                () -> substitution.apply(f.returnType()));
    }

    // ==== Hidden members ====

    /**
     * Whether a call of a function is to a hidden JDK member, as FIR's
     * isHiddenForThisCallSite: a mapped function that is not visible, or a
     * Java function overriding a hidden one; either can be called on {@code super}.
     */
    boolean isHidden(CallableSymbol symbol, boolean isSuperCall) {
        if (!(symbol instanceof FunctionSymbol f) || f.isStatic() || !(f.owner() instanceof ClassSymbol owner)) {
            return false;
        }
        Status own = statuses.get(f);
        if (own != null) return own != Status.VISIBLE && !isSuperCall;
        if (!isJava(f)) return false;
        Status overridden = overriddenStatuses.computeIfAbsent(f, k -> overriddenStatus(f, owner));
        return overridden == Status.HIDDEN && !isSuperCall;
    }

    private Status overriddenStatus(FunctionSymbol f, ClassSymbol owner) {
        String descriptor = JvmDescriptors.descriptor(f);
        for (ClassSymbol k : kotlinSupertypes(owner)) {
            for (FunctionSymbol mapped : functions(k, f.name())) {
                Status s = statuses.get(mapped);
                if (s != Status.VISIBLE && JvmDescriptors.descriptor(mapped).equals(descriptor)) return s;
            }
        }
        return Status.VISIBLE;
    }

    private static Set<ClassSymbol> kotlinSupertypes(ClassSymbol cls) {
        Set<ClassSymbol> result = new LinkedHashSet<>();
        List<ClassSymbol> queue = new ArrayList<>(List.of(cls));
        Set<ClassSymbol> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            ClassSymbol c = queue.removeFirst();
            if (!seen.add(c)) continue;
            if (c != cls && c.origin() == Origin.KOTLIN_SOURCE) result.add(c);
            for (KType st : c.supertypes()) {
                if (st instanceof KType.ClassType t) queue.add(t.symbol());
            }
        }
        return result;
    }

    private static List<CallableSymbol> allOverridden(CallableSymbol f) {
        List<CallableSymbol> result = new ArrayList<>();
        for (CallableSymbol o : Overrides.overridden(f)) {
            result.add(o);
            result.addAll(allOverridden(o));
        }
        return result;
    }

    private static boolean isJava(CallableSymbol s) {
        return s.origin() == Origin.JAVA_SOURCE || s.origin() == Origin.JAVA_BINARY;
    }

    // ==== Signatures, as JvmBuiltInsSignatures ====

    private static final Set<String> DROP_METHOD_SIGNATURES = Set.of(
            "java/util/Collection.toArray()[Ljava/lang/Object;",
            "java/util/Collection.toArray([Ljava/lang/Object;)[Ljava/lang/Object;",
            "java/lang/annotation/Annotation.annotationType()Ljava/lang/Class;");

    private static final Set<String> HIDDEN_METHOD_SIGNATURES = signatures(
            "java/lang/Boolean", "booleanValue()Z",
            "java/lang/Character", "charValue()C",
            "java/util/List", "sort(Ljava/util/Comparator;)V", "reversed()Ljava/util/List;",
            "java/lang/String", "codePointAt(I)I", "codePointBefore(I)I", "codePointCount(II)I",
            "compareToIgnoreCase(Ljava/lang/String;)I", "concat(Ljava/lang/String;)Ljava/lang/String;",
            "contains(Ljava/lang/CharSequence;)Z", "contentEquals(Ljava/lang/CharSequence;)Z",
            "contentEquals(Ljava/lang/StringBuffer;)Z", "endsWith(Ljava/lang/String;)Z",
            "equalsIgnoreCase(Ljava/lang/String;)Z", "getBytes()[B", "getBytes(II[BI)V", "getBytes(Ljava/lang/String;)[B",
            "getBytes(Ljava/nio/charset/Charset;)[B", "getChars(II[CI)V", "indexOf(I)I", "indexOf(II)I",
            "indexOf(Ljava/lang/String;)I", "indexOf(Ljava/lang/String;I)I", "intern()Ljava/lang/String;", "isEmpty()Z",
            "lastIndexOf(I)I", "lastIndexOf(II)I", "lastIndexOf(Ljava/lang/String;)I", "lastIndexOf(Ljava/lang/String;I)I",
            "matches(Ljava/lang/String;)Z", "offsetByCodePoints(II)I", "regionMatches(ILjava/lang/String;II)Z",
            "regionMatches(ZILjava/lang/String;II)Z", "replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            "replace(CC)Ljava/lang/String;", "replaceFirst(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            "replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            "split(Ljava/lang/String;I)[Ljava/lang/String;", "split(Ljava/lang/String;)[Ljava/lang/String;",
            "startsWith(Ljava/lang/String;I)Z", "startsWith(Ljava/lang/String;)Z", "substring(II)Ljava/lang/String;",
            "substring(I)Ljava/lang/String;", "toCharArray()[C", "toLowerCase()Ljava/lang/String;",
            "toLowerCase(Ljava/util/Locale;)Ljava/lang/String;", "toUpperCase()Ljava/lang/String;",
            "toUpperCase(Ljava/util/Locale;)Ljava/lang/String;", "trim()Ljava/lang/String;", "isBlank()Z",
            "lines()Ljava/util/stream/Stream;", "repeat(I)Ljava/lang/String;",
            "java/lang/Double", "isInfinite()Z", "isNaN()Z",
            "java/lang/Float", "isInfinite()Z", "isNaN()Z",
            "java/lang/Enum", "getDeclaringClass()Ljava/lang/Class;", "finalize()V",
            "java/lang/CharSequence", "isEmpty()Z");

    private static final Set<String> VISIBLE_METHOD_SIGNATURES = signatures(
            "java/lang/CharSequence", "codePoints()Ljava/util/stream/IntStream;", "chars()Ljava/util/stream/IntStream;",
            "java/util/Iterator", "forEachRemaining(Ljava/util/function/Consumer;)V",
            "java/lang/Iterable", "forEach(Ljava/util/function/Consumer;)V", "spliterator()Ljava/util/Spliterator;",
            "java/lang/Throwable", "setStackTrace([Ljava/lang/StackTraceElement;)V",
            "fillInStackTrace()Ljava/lang/Throwable;", "getLocalizedMessage()Ljava/lang/String;", "printStackTrace()V",
            "printStackTrace(Ljava/io/PrintStream;)V", "printStackTrace(Ljava/io/PrintWriter;)V",
            "getStackTrace()[Ljava/lang/StackTraceElement;", "initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;",
            "getSuppressed()[Ljava/lang/Throwable;", "addSuppressed(Ljava/lang/Throwable;)V",
            "java/util/Collection", "spliterator()Ljava/util/Spliterator;", "parallelStream()Ljava/util/stream/Stream;",
            "stream()Ljava/util/stream/Stream;", "removeIf(Ljava/util/function/Predicate;)Z",
            "java/util/List", "replaceAll(Ljava/util/function/UnaryOperator;)V", "addFirst(Ljava/lang/Object;)V",
            "addLast(Ljava/lang/Object;)V", "removeFirst()Ljava/lang/Object;", "removeLast()Ljava/lang/Object;",
            "java/util/Map", "getOrDefault(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            "forEach(Ljava/util/function/BiConsumer;)V", "replaceAll(Ljava/util/function/BiFunction;)V",
            "merge(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
            "computeIfPresent(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
            "putIfAbsent(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            "replace(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z",
            "replace(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            "computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;",
            "compute(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;");

    private static final Set<String> MUTABLE_METHOD_SIGNATURES = signatures(
            "java/util/Collection", "removeIf(Ljava/util/function/Predicate;)Z",
            "java/util/List", "replaceAll(Ljava/util/function/UnaryOperator;)V", "sort(Ljava/util/Comparator;)V",
            "addFirst(Ljava/lang/Object;)V", "addLast(Ljava/lang/Object;)V", "removeFirst()Ljava/lang/Object;",
            "removeLast()Ljava/lang/Object;",
            "java/util/Map", "computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;",
            "computeIfPresent(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
            "compute(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
            "merge(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
            "putIfAbsent(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            "remove(Ljava/lang/Object;Ljava/lang/Object;)Z", "replaceAll(Ljava/util/function/BiFunction;)V",
            "replace(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            "replace(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z");

    /** The JDK names of the methods builtins have under other names, as SpecialGenericSignatures. */
    private static final Set<String> RENAMED_BUILTIN_SIGNATURES = signatures(
            "java/lang/Number", "byteValue()B", "shortValue()S", "intValue()I", "longValue()J", "floatValue()F",
            "doubleValue()D",
            "java/util/List", "remove(I)Ljava/lang/Object;",
            "java/lang/CharSequence", "charAt(I)C");

    /** The JDK getters of builtin properties, as BuiltinSpecialProperties.GETTER_FQ_NAMES. */
    private static final Set<String> BUILTIN_PROPERTY_GETTERS = Set.of("java.lang.Enum.name", "java.lang.Enum.ordinal",
            "java.util.Collection.size", "java.util.Map.size", "java.lang.CharSequence.length", "java.util.Map.keySet",
            "java.util.Map.values", "java.util.Map.entrySet");

    /** Class internal names, each followed by descriptors of its methods. */
    private static Set<String> signatures(String... parts) {
        Set<String> result = new HashSet<>();
        String cls = null;
        for (String p : parts) {
            if (p.contains("(")) {
                result.add(cls + "." + p);
            } else {
                cls = p;
            }
        }
        return result;
    }
}
