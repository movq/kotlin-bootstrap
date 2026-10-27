package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.ast.KtFile;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.SymbolTable;
import kotlinbootstrap.symbols.TypeAliasSymbol;
import kotlinbootstrap.types.KType;

/**
 * The scopes of a file's imports and package, as FIR's
 * computeImportingScopes, outermost first: the default star imports (with
 * java.lang only looked at if the others have nothing), the explicit star
 * imports, the builtin classes imported by default by name, the file's
 * package and the explicit imports by name. Names imported with an alias
 * are not visible under their own names through star imports or the package.
 */
final class ImportingScopes {
    private ImportingScopes() {
    }

    /**
     * An import resolved to what it imports from: a package, or a class
     * whose statics (or, for an object, all members) it imports.
     *
     * @param name the imported name, for an import by name; null for a star import
     */
    private record ResolvedImport(String packageName, ClassSymbol parent, String name) {
    }

    static List<TowerScope> of(FileContext file, Resolver resolver, MemberScopes scopes) {
        Set<String> excluded = new HashSet<>();
        List<ResolvedImport> stars = new ArrayList<>();
        Map<String, List<ResolvedImport>> simple = new HashMap<>();
        for (KtFile.Import imp : file.file().imports()) {
            if (imp.allUnder()) {
                stars.add(resolve(resolver.table(), imp.path(), null));
            } else {
                String fqName = String.join(".", imp.path());
                if (imp.alias() != null) excluded.add(fqName);
                String name = imp.alias() != null ? imp.alias() : imp.path().getLast();
                List<String> parentPath = imp.path().subList(0, imp.path().size() - 1);
                simple.computeIfAbsent(name, k -> new ArrayList<>())
                        .add(resolve(resolver.table(), parentPath, imp.path().getLast()));
            }
        }
        Set<String> excludedInPackage = new HashSet<>();
        for (String fq : excluded) {
            int dot = fq.lastIndexOf('.');
            if ((dot < 0 ? "" : fq.substring(0, dot)).equals(file.packageName())) {
                excludedInPackage.add(fq.substring(dot + 1));
            }
        }
        List<ResolvedImport> defaultHigh = FileContext.DEFAULT_IMPORTS.stream()
                .map(p -> new ResolvedImport(p, null, null)).toList();
        List<ResolvedImport> defaultLow = List.of(new ResolvedImport("java.lang", null, null));
        return List.of(
                new DefaultStar(new Star(resolver, scopes, defaultHigh, excluded),
                        new Star(resolver, scopes, defaultLow, excluded)),
                new Star(resolver, scopes, stars, excluded),
                new DefaultSimple(resolver, excluded),
                new Package(resolver.table(), file.packageName(), excludedInPackage),
                new Simple(resolver, scopes, simple));
    }

    /**
     * Splits a path into the longest prefix that is a package and the class
     * the rest names in it, if any.
     */
    private static ResolvedImport resolve(SymbolTable table, List<String> path, String name) {
        for (int k = path.size(); k >= 0; k--) {
            String pkg = String.join(".", path.subList(0, k));
            if (k > 0 && !table.packageExists(pkg)) continue;
            if (k == path.size()) return new ResolvedImport(pkg, null, name);
            ClassifierSymbol c = table.findClassifier(new ClassId(pkg, path.get(k)));
            for (String nested : path.subList(k + 1, path.size())) {
                if (c == null) break;
                c = SymbolTable.nestedClassifier(c, nested);
            }
            ClassSymbol cls = expandedClass(c);
            if (cls != null) return new ResolvedImport(pkg, cls, name);
        }
        return new ResolvedImport(String.join(".", path), null, name);
    }

    private static ClassSymbol expandedClass(ClassifierSymbol c) {
        if (c instanceof TypeAliasSymbol alias && alias.expansion() instanceof KType.ClassType ct) return ct.symbol();
        return c instanceof ClassSymbol cls ? cls : null;
    }

    // ==== Importing callables ====

    private static List<TowerScope.ScopeMember> callables(Resolver resolver, MemberScopes scopes, ResolvedImport imp,
                                                          String name, boolean functions) {
        if (imp.parent() != null) {
            ClassSymbol parent = imp.parent();
            if (parent.isObject()) {
                KType type = resolver.types().defaultType(parent);
                List<TowerScope.ScopeMember> result = new ArrayList<>();
                List<MemberScopes.Member> members = functions
                        ? scopes.functions(type, name) : scopes.properties(type, name);
                for (MemberScopes.Member m : members) {
                    result.add(new TowerScope.ScopeMember(m.symbol(), parent, m.substitution()));
                }
                result.addAll(TowerScope.ScopeMember.of(scopes.statics(parent, name, functions)));
                return result;
            }
            return TowerScope.ScopeMember.of(scopes.statics(parent, name, functions));
        }
        MemberScope pkg = resolver.table().existingPackageScope(imp.packageName());
        if (pkg == null) return List.of();
        return TowerScope.ScopeMember.of(functions ? pkg.functions(name) : pkg.properties(name));
    }

    /** The top-level declarations of a package, as a qualifier: {@code kotlin.collections.listOf()}. */
    static TowerScope packageScope(SymbolTable table, String packageName) {
        return new Package(table, packageName, Set.of());
    }

    private static ClassifierSymbol classifier(Resolver resolver, ResolvedImport imp, String name) {
        if (imp.parent() != null) return imp.parent().members().classifier(name);
        return resolver.table().findClassifier(new ClassId(imp.packageName(), name));
    }

    // ==== Scopes ====

    /** Explicit imports by name, possibly aliased. */
    private record Simple(Resolver resolver, MemberScopes scopes, Map<String, List<ResolvedImport>> imports)
            implements TowerScope {
        @Override
        public List<ScopeMember> functions(String name) {
            return callables(name, true);
        }

        @Override
        public List<ScopeMember> properties(String name) {
            return callables(name, false);
        }

        private List<ScopeMember> callables(String name, boolean functions) {
            List<ResolvedImport> found = imports.get(name);
            if (found == null) return List.of();
            List<ScopeMember> result = new ArrayList<>();
            for (ResolvedImport imp : found) {
                result.addAll(ImportingScopes.callables(resolver, scopes, imp, imp.name(), functions));
            }
            return result;
        }

        @Override
        public List<ClassifierSymbol> classifiers(String name) {
            List<ResolvedImport> found = imports.get(name);
            if (found == null) return List.of();
            Set<ClassifierSymbol> result = new LinkedHashSet<>();
            for (ResolvedImport imp : found) {
                ClassifierSymbol c = classifier(resolver, imp, imp.name());
                if (c != null) result.add(c);
            }
            return List.copyOf(result);
        }
    }

    /** Star imports, from packages or classes. */
    private record Star(Resolver resolver, MemberScopes scopes, List<ResolvedImport> imports, Set<String> excluded)
            implements TowerScope {
        private boolean isExcluded(ResolvedImport imp, String name) {
            if (excluded.isEmpty()) return false;
            String prefix = imp.parent() != null ? imp.parent().id().fqName() : imp.packageName();
            return excluded.contains(prefix.isEmpty() ? name : prefix + "." + name);
        }

        @Override
        public List<ScopeMember> functions(String name) {
            return callables(name, true);
        }

        @Override
        public List<ScopeMember> properties(String name) {
            return callables(name, false);
        }

        private List<ScopeMember> callables(String name, boolean functions) {
            List<ScopeMember> result = new ArrayList<>();
            for (ResolvedImport imp : imports) {
                if (!isExcluded(imp, name)) {
                    result.addAll(ImportingScopes.callables(resolver, scopes, imp, name, functions));
                }
            }
            return result;
        }

        @Override
        public List<ClassifierSymbol> classifiers(String name) {
            Set<ClassifierSymbol> result = new LinkedHashSet<>();
            for (ResolvedImport imp : imports) {
                if (isExcluded(imp, name)) continue;
                ClassifierSymbol c = classifier(resolver, imp, name);
                if (c != null) result.add(c);
            }
            return List.copyOf(result);
        }
    }

    /** The default star imports: java.lang is only looked at if the others have nothing. */
    private record DefaultStar(Star high, Star low) implements TowerScope {
        @Override
        public List<ScopeMember> functions(String name) {
            List<ScopeMember> found = high.functions(name);
            return found.isEmpty() ? low.functions(name) : found;
        }

        @Override
        public List<ScopeMember> properties(String name) {
            List<ScopeMember> found = high.properties(name);
            return found.isEmpty() ? low.properties(name) : found;
        }

        @Override
        public List<ClassifierSymbol> classifiers(String name) {
            List<ClassifierSymbol> found = high.classifiers(name);
            return found.isEmpty() ? low.classifiers(name) : found;
        }
    }

    /** The builtin classes, imported by default by name on the JVM. */
    private record DefaultSimple(Resolver resolver, Set<String> excluded) implements TowerScope {
        @Override
        public List<ScopeMember> functions(String name) {
            return List.of();
        }

        @Override
        public List<ScopeMember> properties(String name) {
            return List.of();
        }

        @Override
        public List<ClassifierSymbol> classifiers(String name) {
            ClassifierSymbol c = resolver.builtinClassifier(name);
            if (c == null || c instanceof ClassSymbol cls && excluded.contains(cls.id().fqName())) return List.of();
            return List.of(c);
        }
    }

    /** The declarations of the file's package. */
    private record Package(SymbolTable table, String packageName, Set<String> excluded) implements TowerScope {
        @Override
        public List<ScopeMember> functions(String name) {
            MemberScope scope = table.existingPackageScope(packageName);
            return scope == null || excluded.contains(name) ? List.of() : ScopeMember.of(scope.functions(name));
        }

        @Override
        public List<ScopeMember> properties(String name) {
            MemberScope scope = table.existingPackageScope(packageName);
            return scope == null || excluded.contains(name) ? List.of() : ScopeMember.of(scope.properties(name));
        }

        @Override
        public List<ClassifierSymbol> classifiers(String name) {
            if (excluded.contains(name)) return List.of();
            ClassifierSymbol c = table.findClassifier(new ClassId(packageName, name));
            return c == null ? List.of() : List.of(c);
        }
    }
}
