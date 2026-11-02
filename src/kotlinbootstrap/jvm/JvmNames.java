package kotlinbootstrap.jvm;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import kotlinbootstrap.ast.Annotation;
import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.KtFile;
import kotlinbootstrap.ast.Modifiers;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.PackageSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.syntax.SourceFile;

/**
 * The JVM names of declarations, as kotlinc's JvmAbi and
 * MethodSignatureMapper: file facades for top-level declarations,
 * accessors of properties, {@code @JvmName}, and internal members
 * mangled with their module's name.
 */
final class JvmNames {
    /** Members of builtins whose JVM name differs, as kotlinc's BuiltinSpecialProperties and ...WithDifferentJvmName. */
    private static final Map<String, String> SPECIAL_PROPERTIES = new HashMap<>(Map.of(
            "kotlin.collections.Collection.size", "size", "kotlin.collections.Map.size", "size",
            "kotlin.CharSequence.length", "length", "kotlin.collections.Map.keys", "keySet",
            "kotlin.collections.Map.values", "values", "kotlin.collections.Map.entries", "entrySet",
            "kotlin.Enum.name", "name", "kotlin.Enum.ordinal", "ordinal"));
    private static final Map<String, String> SPECIAL_FUNCTIONS = new HashMap<>(Map.of(
            "kotlin.Number.toByte", "byteValue", "kotlin.Number.toShort", "shortValue",
            "kotlin.Number.toInt", "intValue", "kotlin.Number.toLong", "longValue",
            "kotlin.Number.toFloat", "floatValue", "kotlin.Number.toDouble", "doubleValue",
            "kotlin.CharSequence.get", "charAt", "kotlin.collections.MutableList.removeAt", "remove"));

    static {
        // The atomics of kotlin.concurrent.atomics are java.util.concurrent.atomic's, as kotlinc's JvmBuiltin ones.
        String atomics = "kotlin.concurrent.atomics.";
        for (String c : List.of("AtomicInt", "AtomicLong", "AtomicBoolean", "AtomicReference")) {
            SPECIAL_FUNCTIONS.putAll(Map.of(atomics + c + ".load", "get", atomics + c + ".store", "set",
                    atomics + c + ".exchange", "getAndSet", atomics + c + ".fetchAndAdd", "getAndAdd",
                    atomics + c + ".addAndFetch", "addAndGet"));
        }
        for (String c : List.of("AtomicIntArray", "AtomicLongArray", "AtomicArray")) {
            SPECIAL_FUNCTIONS.putAll(Map.of(atomics + c + ".loadAt", "get", atomics + c + ".storeAt", "set",
                    atomics + c + ".exchangeAt", "getAndSet", atomics + c + ".compareAndSetAt", "compareAndSet",
                    atomics + c + ".compareAndExchangeAt", "compareAndExchange",
                    atomics + c + ".fetchAndAddAt", "getAndAdd", atomics + c + ".addAndFetchAt", "addAndGet"));
            SPECIAL_PROPERTIES.put(atomics + c + ".size", "length");
        }
    }

    private final Resolver resolver;
    private final Map<CallableSymbol, Optional<String>> specialNames = new IdentityHashMap<>();
    private final Function<SourceFile, String> moduleName;

    /** @param moduleName the name of the module a source file is in */
    JvmNames(Resolver resolver, Function<SourceFile, String> moduleName) {
        this.resolver = resolver;
        this.moduleName = moduleName;
    }

    // ==== Annotations by name ====

    /** The annotation with a simple name and, if given, use-site target; null if none. */
    static Annotation annotation(Modifiers modifiers, String name, String target) {
        return annotation(modifiers.annotations(), name, target);
    }

    static Annotation annotation(List<Annotation> annotations, String name, String target) {
        for (Annotation a : annotations) {
            if (a.type().segments().getLast().name().equals(name)
                    && (target == null ? a.target() == null : target.equals(a.target()))) {
                return a;
            }
        }
        return null;
    }

    static boolean has(Modifiers modifiers, String name) {
        return annotation(modifiers, name, null) != null;
    }

    /** The first argument of an annotation as a string literal. */
    static String stringArgument(Annotation a) {
        if (a == null || a.args().isEmpty() || !(a.args().getFirst().value() instanceof Expr.StringTemplate t)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Expr.StringPart p : t.parts()) {
            if (!(p instanceof Expr.TextPart text)) return null;
            sb.append(text.text());
        }
        return sb.toString();
    }

    /**
     * Whether a file declares builtins without class files, which are
     * compiled as intrinsics or mapped to Java classes: those of
     * jvm/builtins. Other {@code @JvmBuiltin} files, like ranges, are compiled.
     */
    static boolean isBuiltinFile(KtFile file) {
        return annotation(file.annotations(), "SuppressBytecodeGeneration", "file") != null;
    }

    /** The JVM name of a builtin member a member is or overrides, e.g. size() for Collection.size; else null. */
    String specialName(CallableSymbol s) {
        if (!(s.owner() instanceof ClassSymbol)) return null;
        Optional<String> cached = specialNames.get(s);
        if (cached != null) return cached.orElse(null);
        Map<String, String> table = s instanceof FunctionSymbol ? SPECIAL_FUNCTIONS : SPECIAL_PROPERTIES;
        String result = table.get(((ClassSymbol) s.owner()).id().fqName() + "." + s.name());
        if (result == null && hasSpecialName(s.name(), table)) {
            for (CallableSymbol o : Overrides.overridden(s)) {
                result = specialName(o);
                if (result != null) break;
            }
        }
        specialNames.put(s, Optional.ofNullable(result));
        return result;
    }

    private static boolean hasSpecialName(String name, Map<String, String> table) {
        for (String key : table.keySet()) if (key.endsWith("." + name)) return true;
        return false;
    }

    // ==== Files ====

    /** The name of a file's class for top-level declarations, without package, e.g. FooKt for Foo.kt. */
    static String filePartShortName(KtFile file) {
        String name = file.source().path();
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.endsWith(".kt")) name = name.substring(0, name.length() - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean valid = i == 0 ? Character.isJavaIdentifierStart(c) : Character.isJavaIdentifierPart(c);
            sb.append(valid ? c : '_');
        }
        if (!sb.isEmpty() && sb.charAt(0) >= 'a' && sb.charAt(0) <= 'z') sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        return sb + "Kt";
    }

    private static String packagePrefix(FileContext file) {
        return file.packageName().isEmpty() ? "" : file.packageName().replace('.', '/') + "/";
    }

    static boolean isMultifile(KtFile file) {
        return annotation(file.annotations(), "JvmMultifileClass", "file") != null;
    }

    /** The class callers see a file's top-level declarations in: its own, or the multifile facade. */
    String facade(FileContext file) {
        String jvmName = stringArgument(annotation(file.file().annotations(), "JvmName", "file"));
        return packagePrefix(file) + (jvmName != null ? jvmName : filePartShortName(file.file()));
    }

    /** The class a file's top-level declarations are compiled into: the facade, or a multifile part of it. */
    String filePart(FileContext file) {
        if (!isMultifile(file.file())) return facade(file);
        String facade = facade(file);
        return facade + "__" + filePartShortName(file.file());
    }

    // ==== Owners ====

    FileContext file(CallableSymbol symbol) {
        Resolver.DeclarationContext dc = resolver.context(symbol);
        if (dc != null) return dc.file();
        // Members, also those without contexts of their own: in their class's file.
        return symbol.owner() instanceof KotlinClassSymbol cls ? cls.file() : null;
    }

    static boolean isTopLevel(CallableSymbol symbol) {
        return symbol.owner() instanceof PackageSymbol;
    }

    // ==== Functions ====

    /** The JVM name of a function. */
    String functionName(FunctionSymbol f) {
        if (f.isConstructor()) return "<init>";
        String special = specialName(f);
        if (special != null) return special;
        if (f.source() instanceof Decl.Function d) {
            String jvmName = stringArgument(annotation(d.modifiers(), "JvmName", null));
            if (jvmName != null) return jvmName;
            return mangled(f, f.name(), d.modifiers());
        }
        return f.name();
    }

    /**
     * Internal members are suffixed with their module's name; private
     * top-level declarations of a multifile part, with the part's.
     */
    private String mangled(CallableSymbol symbol, String name, Modifiers modifiers) {
        if (symbol.visibility() == Visibility.INTERNAL && symbol.owner() instanceof ClassSymbol
                && !has(modifiers, "PublishedApi")) {
            return name + "$" + sanitize(moduleName.apply(file(symbol).file().source()));
        }
        if (symbol.visibility() == Visibility.PRIVATE && isTopLevel(symbol)) {
            FileContext file = file(symbol);
            if (file != null && isMultifile(file.file())) {
                String part = filePart(file);
                return name + "$" + part.substring(part.lastIndexOf('/') + 1);
            }
        }
        return name;
    }

    private static String sanitize(String moduleName) {
        StringBuilder sb = new StringBuilder();
        for (char c : moduleName.toCharArray()) sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        return sb.toString();
    }

    // ==== Properties ====

    /** Whether a property name is of the form isX, whose getter keeps the name, as JvmAbi's startsWithIsPrefix. */
    private static boolean startsWithIs(String name) {
        return name.startsWith("is") && name.length() > 2 && !Character.isLowerCase(name.charAt(2));
    }

    static String getterName(String property) {
        return startsWithIs(property) ? property : "get" + capitalize(property);
    }

    static String setterName(String property) {
        return "set" + (startsWithIs(property) ? property.substring(2) : capitalize(property));
    }

    private static String capitalize(String s) {
        if (s.isEmpty() || !(s.charAt(0) >= 'a' && s.charAt(0) <= 'z')) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    String getterName(PropertySymbol p) {
        if (p.javaGetter() != null) return p.javaGetter().name();
        String special = specialName(p);
        if (special != null) return special;
        if (p.source() instanceof Decl.Property d) {
            String jvmName = stringArgument(annotation(d.modifiers(), "JvmName", "get"));
            if (jvmName == null && d.getter() != null) {
                jvmName = stringArgument(annotation(d.getter().modifiers(), "JvmName", null));
            }
            if (jvmName != null) return jvmName;
            return mangled(p, getterName(p.name()), d.modifiers());
        }
        if (p.source() instanceof Decl.Param param) return mangled(p, getterName(p.name()), param.modifiers());
        return getterName(p.name());
    }

    String setterName(PropertySymbol p) {
        if (p.javaSetter() != null) return p.javaSetter().name();
        if (p.source() instanceof Decl.Property d) {
            String jvmName = stringArgument(annotation(d.modifiers(), "JvmName", "set"));
            if (jvmName == null && d.setter() != null) {
                jvmName = stringArgument(annotation(d.setter().modifiers(), "JvmName", null));
            }
            if (jvmName != null) return jvmName;
            return mangled(p, setterName(p.name()), d.modifiers());
        }
        if (p.source() instanceof Decl.Param param) return mangled(p, setterName(p.name()), param.modifiers());
        return setterName(p.name());
    }

    /** The modifiers of a property's declaration, a property or a primary constructor parameter. */
    static Modifiers modifiers(PropertySymbol p) {
        return switch (p.source()) {
            case Decl.Property d -> d.modifiers();
            case Decl.Param d -> d.modifiers();
            case null, default -> Modifiers.EMPTY;
        };
    }

    static Modifiers modifiers(ClassSymbol cls) {
        return cls instanceof kotlinbootstrap.resolve.KotlinClassSymbol k ? k.decl().modifiers() : Modifiers.EMPTY;
    }
}
