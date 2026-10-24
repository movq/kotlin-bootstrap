package kotlinbootstrap.oracle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import kotlin.Metadata;
import kotlin.Unit;

import kotlin.jvm.JvmClassMappingKt;
import kotlin.jvm.internal.Reflection;
import kotlin.reflect.KCallable;
import kotlin.reflect.KClass;
import kotlin.reflect.KMutableProperty;
import kotlin.reflect.KParameter;
import kotlin.reflect.KProperty;
import kotlin.reflect.KTypeParameter;
import kotlin.reflect.KVisibility;
import kotlin.reflect.full.KClasses;

import kotlinbootstrap.Frontend;
import kotlinbootstrap.Stdlib;
import kotlinbootstrap.ast.Annotation;
import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.TypeRenderer;

/**
 * Compares the class signatures we resolve from the stdlib sources with
 * those of the reference kotlin-stdlib.jar, read through kotlin-reflect:
 * type parameters, supertypes, and the signatures of declared members.
 * Types are compared as rendered by kotlinc.
 *
 * <p>Top-level declarations are not covered: kotlin-reflect cannot list
 * the contents of packages.
 */
public final class StdlibSignatureOracle {
    /** Builtin classes have no JVM class of their own; kotlin-reflect finds them by the mapped Java class. */
    private static final Map<String, String> MAPPED = new HashMap<>();

    static {
        for (String name : List.of("Any:java.lang.Object", "String:java.lang.String",
                "CharSequence:java.lang.CharSequence", "Throwable:java.lang.Throwable", "Number:java.lang.Number",
                "Comparable:java.lang.Comparable", "Enum:java.lang.Enum", "Annotation:java.lang.annotation.Annotation",
                "Cloneable:java.lang.Cloneable", "Boolean:java.lang.Boolean", "Char:java.lang.Character",
                "Byte:java.lang.Byte", "Short:java.lang.Short", "Int:java.lang.Integer", "Long:java.lang.Long",
                "Float:java.lang.Float", "Double:java.lang.Double",
                "collections.Iterable:java.lang.Iterable", "collections.Iterator:java.util.Iterator",
                "collections.Collection:java.util.Collection", "collections.List:java.util.List",
                "collections.Set:java.util.Set", "collections.ListIterator:java.util.ListIterator",
                "collections.Map:java.util.Map", "collections.Map.Entry:java.util.Map$Entry")) {
            String[] parts = name.split(":");
            MAPPED.put("kotlin." + parts[0], parts[1]);
        }
    }

    private final Resolver resolver;
    private int classes;
    private int skippedClasses;
    private int members;
    private int mappedJavaMembers;
    private final List<String> mismatches = new ArrayList<>();

    private StdlibSignatureOracle(Resolver resolver) {
        this.resolver = resolver;
    }

    public static void main(String[] args) throws Exception {
        Resolver resolver = Frontend.load(Stdlib.sourceRoots(Path.of(args[0])), "8");
        StdlibSignatureOracle oracle = new StdlibSignatureOracle(resolver);
        Map<String, ClassifierSymbol> sorted = new TreeMap<>();
        resolver.table().kotlinClassifiers().forEach((id, c) -> sorted.put(id.fqName(), c));
        for (ClassifierSymbol c : sorted.values()) {
            if (c instanceof KotlinClassSymbol cls && !cls.has(Modifier.EXPECT)) oracle.check(cls);
        }
        oracle.checkTopLevel();
        oracle.mismatches.forEach(System.out::println);
        System.out.printf("%d classes (%d not in the reference jar), %d members and top-level declarations "
                        + "compared, %d Java members of mapped classes skipped, %d mismatches%n",
                oracle.classes, oracle.skippedClasses, oracle.members, oracle.mappedJavaMembers,
                oracle.mismatches.size());
        System.exit(oracle.mismatches.isEmpty() ? 0 : 1);
    }

    private void check(KotlinClassSymbol cls) {
        KClass<?> ref = referenceClass(cls);
        if (ref == null) {
            skippedClasses++;
            return;
        }
        classes++;
        String name = cls.id().fqName();

        String ours = cls.visibility() + " " + cls.modality();
        String theirs = refVisibility(ref.getVisibility()) + " " + refModality(ref.isFinal(), ref.isOpen(), ref.isAbstract(),
                ref.isSealed());
        if (!ours.equals(theirs)) mismatch(name, "visibility and modality", ours, theirs);

        ours = typeParams(cls.typeParams(), true);
        theirs = refTypeParams(ref.getTypeParameters(), true);
        if (!ours.equals(theirs)) mismatch(name, "type parameters", ours, theirs);

        ours = sortedJoin(cls.supertypes().stream().map(TypeRenderer::render).filter(t -> !t.equals("kotlin.Any")).toList());
        theirs = sortedJoin(ref.getSupertypes().stream().map(t -> normalize(t.toString()))
                .filter(t -> !t.equals("kotlin.Any")).toList());
        if (!ours.equals(theirs)) mismatch(name, "supertypes", ours, theirs);

        Map<String, List<String>> ourMembers = new TreeMap<>();
        cls.members().functions().values().forEach(fs -> fs.forEach(f -> put(ourMembers, f)));
        cls.members().properties().values().forEach(ps -> ps.forEach(p -> {
            if (!(p.source() instanceof Decl.EnumEntry)) put(ourMembers, p);
        }));
        cls.members().constructors().forEach(f -> put(ourMembers, f));

        Map<String, List<String>> refMembers = new TreeMap<>();
        for (KCallable<?> c : KClasses.getDeclaredMembers(ref)) putRef(refMembers, c);
        if (cls.kind() != ClassKind.INTERFACE && cls.kind() != ClassKind.OBJECT && !cls.has(Modifier.COMPANION)) {
            for (KCallable<?> c : ref.getConstructors()) putRef(refMembers, c);
        }
        // Members of the Java class that kotlinc adds to a mapped builtin (JvmMappedScope) are not modelled yet.
        compare(name, ourMembers, refMembers, !MAPPED.containsKey(name));
    }

    /**
     * Compares the top-level declarations of each package with the members
     * of the file and multi-file facade classes of the reference jar.
     */
    private void checkTopLevel() throws Exception {
        Map<String, Map<String, List<String>>> refPackages = new TreeMap<>();
        Path jar = Path.of(Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                String entry = e.getName();
                if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) continue;
                Class<?> c = Class.forName(entry.substring(0, entry.length() - 6).replace('/', '.'), false,
                        StdlibSignatureOracle.class.getClassLoader());
                Metadata metadata = c.getAnnotation(Metadata.class);
                if (metadata == null || metadata.k() != FILE_FACADE && metadata.k() != MULTIFILE_FACADE) continue;
                // The Kotlin package, if the file has a @JvmPackageName.
                String pkg = metadata.pn().isEmpty() ? c.getPackageName() : metadata.pn();
                Map<String, List<String>> members = refPackages.computeIfAbsent(pkg, k -> new TreeMap<>());
                for (KCallable<?> m : Reflection.getOrCreateKotlinPackage(c).getMembers()) putRef(members, m);
            }
        }
        Set<String> packages = new TreeSet<>(refPackages.keySet());
        resolver.files().forEach(f -> packages.add(f.packageName()));
        // Files of builtins without bytecode have no facade class; their declarations are in the builtins metadata.
        Set<Object> builtinDecls = new HashSet<>();
        for (FileContext f : resolver.files()) {
            for (Annotation a : f.file().annotations()) {
                if (a.type().segments().getLast().name().equals("SuppressBytecodeGeneration")) {
                    builtinDecls.addAll(f.file().declarations());
                }
            }
        }
        for (String pkg : packages) {
            Map<String, List<String>> ourMembers = new TreeMap<>();
            MemberScope scope = resolver.table().existingPackageScope(pkg);
            if (scope != null) {
                scope.functions().values().forEach(fs -> fs.forEach(f -> {
                    if (!builtinDecls.contains(f.source())) put(ourMembers, f);
                }));
                scope.properties().values().forEach(ps -> ps.forEach(p -> {
                    if (!builtinDecls.contains(p.source())) put(ourMembers, p);
                }));
            }
            compare("package " + pkg, ourMembers, refPackages.getOrDefault(pkg, new TreeMap<>()), true);
        }
    }

    private static final int FILE_FACADE = 2;
    private static final int MULTIFILE_FACADE = 4;

    /**
     * Pairs up our members with the reference ones by key; members with the
     * same key (overloads that differ in return type) by value. Our value
     * for a type we would have to infer matches any type.
     */
    private void compare(String owner, Map<String, List<String>> ours, Map<String, List<String>> theirs,
                         boolean reportMissing) {
        for (Map.Entry<String, List<String>> e : ours.entrySet()) {
            String key = e.getKey();
            List<String> refValues = new ArrayList<>(theirs.getOrDefault(key, List.of()));
            List<String> unmatched = new ArrayList<>();
            for (String value : e.getValue()) {
                String match = refValues.stream().filter(v -> sameValue(value, v)).findFirst().orElse(null);
                if (match != null) {
                    refValues.remove(match);
                    members++;
                } else {
                    unmatched.add(value);
                }
            }
            if (unmatched.size() == 1 && refValues.size() == 1) {
                mismatch(owner, "header or return type of " + key, unmatched.getFirst(), refValues.getFirst());
                members++;
            } else {
                for (String value : unmatched) mismatch(owner, "extra member", key + " " + value, null);
                if (reportMissing) for (String value : refValues) mismatch(owner, "missing member", null, key + " " + value);
            }
            theirs.remove(key);
        }
        for (Map.Entry<String, List<String>> e : theirs.entrySet()) {
            if (!reportMissing) {
                mappedJavaMembers += e.getValue().size();
                continue;
            }
            for (String value : e.getValue()) mismatch(owner, "missing member", null, e.getKey() + " " + value);
        }
    }

    private static boolean sameValue(String ours, String theirs) {
        if (!ours.endsWith(": ?")) return ours.equals(theirs);
        return theirs.startsWith(ours.substring(0, ours.length() - 1));
    }

    private static KClass<?> referenceClass(ClassSymbol cls) {
        String fq = cls.id().fqName();
        // kotlin-reflect shows these as the builtin companions, e.g. kotlin.Int.Companion.
        if (fq.matches("kotlin\\.jvm\\.internal\\.\\w+CompanionObject")) return null;
        String binary = MAPPED.getOrDefault(fq, cls.id().packageName() + "." + cls.id().relativeName().replace('.', '$'));
        try {
            return JvmClassMappingKt.getKotlinClass(Class.forName(binary, false, StdlibSignatureOracle.class.getClassLoader()));
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return null;
        }
    }

    // ==== Our signatures ====

    private void put(Map<String, List<String>> members, CallableSymbol c) {
        StringBuilder key = new StringBuilder(kind(c)).append(' ').append(c.name());
        key.append(typeParams(c instanceof FunctionSymbol f && f.isConstructor() ? List.of() : c.typeParams(), false));
        CallableSymbol.Signature sig = c.signature();
        StringJoiner params = new StringJoiner(", ", "(", ")");
        for (ValueParamSymbol p : sig.params()) params.add(paramType(p));
        key.append(sig.receiver() == null ? "" : "[" + TypeRenderer.render(sig.receiver()) + "]");
        if (c instanceof FunctionSymbol) key.append(params);
        String header = c.visibility() + " " + c.modality() + ": ";
        members.computeIfAbsent(asReflectShows(key.toString()), k -> new ArrayList<>())
                .add(asReflectShows(header + (Resolver.hasDeclaredType(c) ? TypeRenderer.render(c.returnType()) : "?")));
    }

    /**
     * Rewrites our rendering the way kotlin-reflect shows the same types: it
     * shows context parameters of function types as ordinary ones, Java
     * classes that are mapped to builtins as the builtins, and the builtin
     * atomics as their Java classes.
     */
    private static String asReflectShows(String s) {
        int context;
        while ((context = s.indexOf("context(")) >= 0) {
            int depth = 0;
            int end = context + "context".length();
            do {
                if (s.charAt(end) == '(') depth++;
                if (s.charAt(end) == ')') depth--;
                end++;
            } while (depth > 0);
            // A function type with context parameters shows them as leading parameters.
            String contextParams = s.substring(context + "context(".length(), end - 1);
            String rest = s.substring(end + 1);
            s = s.substring(0, context) + "(" + contextParams + (rest.startsWith("()") ? "" : ", ") + rest.substring(1);
        }
        for (Map.Entry<String, String> e : MAPPED.entrySet()) {
            s = s.replaceAll("\\b" + Pattern.quote(e.getValue()) + "\\b(?![.$])", e.getKey());
        }
        for (String atomic : List.of("AtomicInt:AtomicInteger", "AtomicLong:AtomicLong", "AtomicBoolean:AtomicBoolean",
                "AtomicReference:AtomicReference", "AtomicIntArray:AtomicIntegerArray",
                "AtomicLongArray:AtomicLongArray", "AtomicArray:AtomicReferenceArray")) {
            String[] parts = atomic.split(":");
            s = s.replaceAll("\\bkotlin\\.concurrent\\.atomics\\." + parts[0] + "\\b",
                    "java.util.concurrent.atomic." + parts[1]);
        }
        return s;
    }

    private static String kind(CallableSymbol c) {
        if (c instanceof PropertySymbol p) return p.isVar() ? "var" : "val";
        return "fun";
    }

    /** A vararg parameter's type is the array type, as kotlin-reflect shows it. */
    private String paramType(ValueParamSymbol p) {
        return TypeRenderer.render(p.isVararg() ? resolver.types().varargArrayType(p.type()) : p.type());
    }

    private static String typeParams(List<TypeParamSymbol> tps, boolean withVariance) {
        if (tps.isEmpty()) return "";
        StringJoiner j = new StringJoiner(", ", "<", ">");
        for (TypeParamSymbol tp : tps) {
            String bounds = String.join(" & ", tp.bounds().stream().map(TypeRenderer::render).toList());
            j.add((withVariance ? tp.variance().prefix() : "") + tp.name() + " : " + bounds);
        }
        return j.toString();
    }

    // ==== Reference signatures ====

    private static String refKey(KCallable<?> c) {
        String kind = c instanceof KMutableProperty ? "var" : c instanceof KProperty ? "val" : "fun";
        boolean isConstructor = c.getName().equals("<init>");
        StringBuilder key = new StringBuilder(kind).append(' ').append(c.getName());
        key.append(refTypeParams(isConstructor ? List.of() : c.getTypeParameters(), false));
        StringJoiner params = new StringJoiner(", ", "(", ")");
        String receiver = "";
        for (KParameter p : c.getParameters()) {
            switch (p.getKind()) {
                case EXTENSION_RECEIVER -> receiver = "[" + normalize(p.getType().toString()) + "]";
                case VALUE -> params.add(normalize(p.getType().toString()));
                default -> {
                }
            }
        }
        key.append(receiver);
        if (kind.equals("fun")) key.append(params);
        return key.toString();
    }

    private static void putRef(Map<String, List<String>> members, KCallable<?> c) {
        members.computeIfAbsent(refKey(c), k -> new ArrayList<>()).add(refValue(c));
    }

    private static String refValue(KCallable<?> c) {
        return refVisibility(c.getVisibility()) + " " + refModality(c.isFinal(), c.isOpen(), c.isAbstract(), false)
                + ": " + normalize(c.getReturnType().toString());
    }

    private static String refVisibility(KVisibility v) {
        return v == null ? "LOCAL" : v.name();
    }

    private static String refModality(boolean isFinal, boolean isOpen, boolean isAbstract, boolean isSealed) {
        return isSealed ? "SEALED" : isAbstract ? "ABSTRACT" : isOpen ? "OPEN" : isFinal ? "FINAL" : "?";
    }

    private static String refTypeParams(List<KTypeParameter> tps, boolean withVariance) {
        if (tps.isEmpty()) return "";
        StringJoiner j = new StringJoiner(", ", "<", ">");
        for (KTypeParameter tp : tps) {
            String variance = switch (tp.getVariance()) {
                case IN -> "in ";
                case OUT -> "out ";
                case INVARIANT -> "";
            };
            String bounds = String.join(" & ", tp.getUpperBounds().stream().map(t -> normalize(t.toString())).toList());
            j.add((withVariance ? variance : "") + tp.getName() + " : " + bounds);
        }
        return j.toString();
    }

    /** Expands type alias abbreviations: {@code a.B /* = c.D *}{@code /} becomes {@code c.D}. */
    static String normalize(String type) {
        int comment;
        while ((comment = type.indexOf(" /* = ")) >= 0) {
            // The abbreviation is the type expression just before the comment.
            int start = comment;
            int depth = 0;
            while (start > 0) {
                char ch = type.charAt(start - 1);
                if (ch == '>') depth++;
                if (ch == '<') {
                    if (depth == 0) break;
                    depth--;
                }
                if (depth == 0 && (ch == ' ' || ch == '(' || ch == ',')) break;
                start--;
            }
            int end = type.indexOf(" */", comment);
            type = type.substring(0, start) + type.substring(comment + 6, end) + type.substring(end + 3);
        }
        return type;
    }

    private static String sortedJoin(Collection<String> items) {
        return String.join(", ", items.stream().sorted().toList());
    }

    private void mismatch(String cls, String what, String ours, String theirs) {
        mismatches.add(cls + ": " + what + (ours != null ? "\n    ours:   " + ours : "")
                + (theirs != null ? "\n    theirs: " + theirs : ""));
    }
}
