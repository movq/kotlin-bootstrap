package kotlinbootstrap.symbols;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The declared members of a class or package, by name. Filled in once by
 * whoever creates it, then only read.
 */
public final class MemberScope {
    private final Map<String, List<FunctionSymbol>> functions = new LinkedHashMap<>();
    private final Map<String, List<PropertySymbol>> properties = new LinkedHashMap<>();
    private final Map<String, ClassifierSymbol> classifiers = new LinkedHashMap<>();
    private final List<FunctionSymbol> constructors = new ArrayList<>();

    public void add(FunctionSymbol f) {
        if (f.isConstructor()) {
            constructors.add(f);
        } else {
            functions.computeIfAbsent(f.name(), k -> new ArrayList<>()).add(f);
        }
    }

    public void add(PropertySymbol p) {
        properties.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p);
    }

    /** Adds a nested class or type alias. Returns the existing one if the name is taken. */
    public ClassifierSymbol add(ClassifierSymbol c) {
        return classifiers.putIfAbsent(c.name(), c);
    }

    public List<FunctionSymbol> functions(String name) {
        return functions.getOrDefault(name, List.of());
    }

    public List<PropertySymbol> properties(String name) {
        return properties.getOrDefault(name, List.of());
    }

    public ClassifierSymbol classifier(String name) {
        return classifiers.get(name);
    }

    public Map<String, List<FunctionSymbol>> functions() {
        return functions;
    }

    public Map<String, List<PropertySymbol>> properties() {
        return properties;
    }

    public Map<String, ClassifierSymbol> classifiers() {
        return classifiers;
    }

    public List<FunctionSymbol> constructors() {
        return constructors;
    }
}
