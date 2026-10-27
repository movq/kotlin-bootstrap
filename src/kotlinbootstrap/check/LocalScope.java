package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;

/**
 * The declarations of a block, lambda or function body: local variables
 * and parameters, local functions and local classes. Filled in as the
 * declarations are checked, so that each sees only those before it.
 */
final class LocalScope {
    private final Map<String, LocalVariable> variables = new HashMap<>();
    private final Map<String, List<FunctionSymbol>> functions = new HashMap<>();
    private final Map<String, ClassifierSymbol> classifiers = new HashMap<>();

    void add(LocalVariable v) {
        variables.put(v.name(), v);
    }

    void add(FunctionSymbol f) {
        functions.computeIfAbsent(f.name(), k -> new ArrayList<>()).add(f);
    }

    void add(ClassifierSymbol c) {
        classifiers.put(c.name(), c);
    }

    LocalVariable variable(String name) {
        return variables.get(name);
    }

    List<FunctionSymbol> functions(String name) {
        return functions.getOrDefault(name, List.of());
    }

    ClassifierSymbol classifier(String name) {
        return classifiers.get(name);
    }
}
