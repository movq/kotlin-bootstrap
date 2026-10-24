package kotlinbootstrap.symbols;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.types.KType;

/**
 * All declarations visible to a compilation: the top-level declarations of
 * the Kotlin sources being compiled, by package, plus classes from other
 * finders (synthetic classes, Java sources, the JDK), consulted in order.
 */
public final class SymbolTable {
    private final Map<String, MemberScope> packages = new HashMap<>();
    private final Set<String> packageNames = new HashSet<>();
    private final Map<ClassId, ClassifierSymbol> kotlinClassifiers = new HashMap<>();
    private final List<ClassFinder> finders = new ArrayList<>();
    private final Map<ClassId, ClassSymbol> found = new HashMap<>();

    public void addFinder(ClassFinder finder) {
        finders.add(finder);
    }

    /** The top-level Kotlin declarations in a package; created on first use. */
    public MemberScope packageScope(String packageName) {
        return packages.computeIfAbsent(packageName, p -> {
            for (String q = p; packageNames.add(q) && !q.isEmpty(); ) {
                int dot = q.lastIndexOf('.');
                q = dot < 0 ? "" : q.substring(0, dot);
            }
            return new MemberScope();
        });
    }

    /** Top-level Kotlin declarations in a package, or null if there are none. */
    public MemberScope existingPackageScope(String packageName) {
        return packages.get(packageName);
    }

    /**
     * Registers a Kotlin class or type alias, top-level or nested. Returns
     * the existing classifier if the id is taken.
     */
    public ClassifierSymbol addKotlinClassifier(ClassId id, ClassifierSymbol c) {
        return kotlinClassifiers.putIfAbsent(id, c);
    }

    /** Replaces a Kotlin classifier: an actual declaration supersedes an expected one. */
    public void replaceKotlinClassifier(ClassId id, ClassifierSymbol c) {
        kotlinClassifiers.put(id, c);
        if (id.outer() == null) packageScope(id.packageName()).classifiers().put(id.shortName(), c);
    }

    /** A class or type alias by id, from any source, or null. */
    public ClassifierSymbol findClassifier(ClassId id) {
        ClassifierSymbol c = kotlinClassifiers.get(id);
        if (c != null) return c;
        if (found.containsKey(id)) return found.get(id);
        ClassSymbol cls = null;
        for (ClassFinder finder : finders) {
            cls = finder.findClass(id);
            if (cls != null) break;
        }
        found.put(id, cls);
        return cls;
    }

    /** Like {@link #findClassifier}, but the id must name a class. */
    public ClassSymbol findClass(ClassId id) {
        return findClassifier(id) instanceof ClassSymbol cls ? cls : null;
    }

    /** A class that must exist, like {@code kotlin.Any}. */
    public ClassSymbol requireClass(String fqName) {
        ClassSymbol cls = findClass(ClassId.topLevel(fqName));
        if (cls == null) throw new IllegalStateException("Missing class " + fqName);
        return cls;
    }

    /**
     * The classifier with a fully qualified name such as {@code a.b.C.D},
     * or null. The longest prefix that is a package wins.
     */
    public ClassifierSymbol findQualifiedClassifier(List<String> path) {
        for (int k = path.size() - 1; k >= 0; k--) {
            String pkg = String.join(".", path.subList(0, k));
            if (k > 0 && !packageExists(pkg)) continue;
            ClassifierSymbol c = findClassifier(new ClassId(pkg, path.get(k)));
            if (c == null) continue;
            for (String name : path.subList(k + 1, path.size())) {
                c = nestedClassifier(c, name);
                if (c == null) break;
            }
            if (c != null) return c;
        }
        return null;
    }

    /** A nested class (or type alias) of a class, or of the class a type alias expands to, or null. */
    public static ClassifierSymbol nestedClassifier(ClassifierSymbol c, String name) {
        if (c instanceof TypeAliasSymbol alias && alias.expansion() instanceof KType.ClassType ct) c = ct.symbol();
        return c instanceof ClassSymbol cls ? cls.members().classifier(name) : null;
    }

    /**
     * A nested class of a class or one of its supertypes. While a class's
     * supertypes are being resolved, lookups do not see the classes they
     * contribute: like kotlinc, which resolves all supertypes in a phase of
     * their own, before such lookups.
     */
    public static ClassifierSymbol findInheritedClassifier(ClassSymbol cls, String name) {
        return findInheritedClassifier(cls, name, new HashSet<>());
    }

    private static ClassifierSymbol findInheritedClassifier(ClassSymbol cls, String name, Set<ClassSymbol> seen) {
        if (!seen.add(cls)) return null;
        ClassifierSymbol c = cls.members().classifier(name);
        if (c != null || cls.supertypesInProgress()) return c;
        for (KType t : cls.supertypes()) {
            if (t instanceof KType.ClassType ct) {
                c = findInheritedClassifier(ct.symbol(), name, seen);
                if (c != null) return c;
            }
        }
        return null;
    }

    public boolean packageExists(String packageName) {
        if (packageNames.contains(packageName)) return true;
        for (ClassFinder finder : finders) {
            if (finder.packageExists(packageName)) return true;
        }
        return false;
    }

    public Map<ClassId, ClassifierSymbol> kotlinClassifiers() {
        return kotlinClassifiers;
    }
}
