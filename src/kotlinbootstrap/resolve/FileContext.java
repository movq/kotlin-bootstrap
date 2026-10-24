package kotlinbootstrap.resolve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.ast.KtFile;
import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.SymbolTable;

/** A Kotlin source file being compiled, with its import scope. */
public final class FileContext {
    /**
     * Default star imports on the JVM; java.lang comes after them, as a
     * separate level. The builtin classes are also imported by default, by
     * name, which takes priority over explicit star imports: so {@code Array}
     * is {@code kotlin.Array} even with {@code import java.lang.reflect.*}.
     * See kotlinc's JvmDefaultImportsProvider and computeImportingScopes.
     */
    private static final List<String> DEFAULT_IMPORTS = List.of(
            "kotlin", "kotlin.annotation", "kotlin.collections", "kotlin.comparisons", "kotlin.io",
            "kotlin.ranges", "kotlin.sequences", "kotlin.text", "kotlin.jvm");

    private final KtFile file;
    private final String packageName;
    private final Scope scope;

    FileContext(KtFile file, Resolver resolver) {
        this.file = file;
        this.packageName = String.join(".", file.packageName());
        this.scope = buildScope(resolver);
    }

    public KtFile file() {
        return file;
    }

    public String packageName() {
        return packageName;
    }

    /** The scope for type names at the top level of the file. */
    public Scope scope() {
        return scope;
    }

    private Scope buildScope(Resolver resolver) {
        SymbolTable table = resolver.table();
        Map<String, List<String>> explicit = new HashMap<>();
        List<List<String>> stars = new ArrayList<>();
        for (KtFile.Import imp : file.imports()) {
            if (imp.allUnder()) {
                stars.add(imp.path());
            } else {
                explicit.putIfAbsent(imp.alias() != null ? imp.alias() : imp.path().getLast(), imp.path());
            }
        }
        // Cached, including misses, since most lookups fall through several levels.
        Map<String, ClassifierSymbol> cache = new HashMap<>();
        Scope javaLang = Scope.of(name -> table.findClassifier(new ClassId("java.lang", name)), null);
        Scope defaults = Scope.of(name -> unique(name, DEFAULT_IMPORTS.stream()
                .map(p -> table.findClassifier(new ClassId(p, name))).toList()), javaLang);
        Scope starLevel = Scope.of(name -> unique(name, stars.stream()
                .map(path -> resolver.findInPackageOrClass(path, name)).toList()), defaults);
        Scope builtinLevel = Scope.of(resolver::builtinClassifier, starLevel);
        Scope packageLevel = Scope.of(name -> table.findClassifier(new ClassId(packageName, name)), builtinLevel);
        Scope explicitLevel = Scope.of(name -> {
            List<String> path = explicit.get(name);
            return path == null ? null : resolver.findQualifiedClassifier(path);
        }, packageLevel);
        return Scope.of(name -> {
            if (!cache.containsKey(name)) cache.put(name, explicitLevel.findClassifier(name));
            return cache.get(name);
        }, null);
    }

    /** The one classifier among candidates (ignoring nulls), or null; several is an ambiguity. */
    private ClassifierSymbol unique(String name, List<ClassifierSymbol> candidates) {
        Set<ClassifierSymbol> found = new LinkedHashSet<>();
        for (ClassifierSymbol c : candidates) {
            if (c != null) found.add(c);
        }
        if (found.size() > 1) {
            throw new ResolveException(file.source(), 0, "Ambiguous classifier " + name + ": " + found);
        }
        return found.isEmpty() ? null : found.iterator().next();
    }

    @Override
    public String toString() {
        return file.source().path();
    }
}
