package kotlinbootstrap.resolve;

import java.util.List;
import java.util.function.Function;

import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;

/**
 * A lexical scope for type names: a chain of levels, searched innermost
 * first. Lookups only look at classifiers; scopes for values and
 * functions will come with the expression checker.
 */
public abstract class Scope {
    private final Scope parent;

    protected Scope(Scope parent) {
        this.parent = parent;
    }

    public Scope parent() {
        return parent;
    }

    /** The classifier this level declares under the name, or null. */
    protected abstract ClassifierSymbol own(String name);

    public ClassifierSymbol findClassifier(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            ClassifierSymbol c = s.own(name);
            if (c != null) return c;
        }
        return null;
    }

    public static Scope typeParams(List<TypeParamSymbol> params, Scope parent) {
        if (params.isEmpty()) return parent;
        return new Scope(parent) {
            @Override
            protected ClassifierSymbol own(String name) {
                for (TypeParamSymbol p : params) {
                    if (p.name().equals(name)) return p;
                }
                return null;
            }
        };
    }

    public static Scope of(Function<String, ClassifierSymbol> lookup, Scope parent) {
        return new Scope(parent) {
            @Override
            protected ClassifierSymbol own(String name) {
                return lookup.apply(name);
            }
        };
    }
}
