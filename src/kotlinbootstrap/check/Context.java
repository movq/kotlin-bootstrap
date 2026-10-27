package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.List;

import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.Scope;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.types.KType;

/**
 * What is in scope at a point in a body, as FIR's FirTowerDataContext plus
 * the targets of jumps. Immutable: entering a block or lambda makes a new
 * context; a lambda whose analysis is postponed keeps the one it was in.
 *
 * @param typeScope the scope for type names
 * @param locals    the local scopes, innermost last
 * @param elements  the other levels of the tower, outermost first
 * @param function  the innermost function or lambda, for return
 * @param loop      the innermost loop, for break and continue; nullable
 */
record Context(FileContext file, Scope typeScope, List<LocalScope> locals, List<TowerElement> elements,
               FunctionFrame function, LoopFrame loop) {

    /** A non-local level of the tower: a scope of static declarations, or an implicit receiver. */
    sealed interface TowerElement {
    }

    /**
     * An implicit receiver: of a class body, or of a function or lambda with
     * an extension receiver.
     *
     * @param owner the class, the function or property symbol, or the lambda's {@link CheckedFunction}
     * @param label the name {@code this@label} refers to it by, nullable
     */
    record ImplicitReceiver(KType type, Object owner, String label) implements TowerElement {
        TExpr expr(int pos) {
            return new TExpr.This(pos, type, owner, true);
        }
    }

    /** A function or lambda being checked, the target of returns in it. */
    static final class FunctionFrame {
        final CheckedFunction function;
        final String label;
        /** A lambda, which an unlabeled return passes through; not an anonymous function. */
        final boolean isLambda;
        final FunctionFrame outer;
        /** For a lambda, the values its returns return. */
        final List<TExpr> returns = new ArrayList<>();

        FunctionFrame(CheckedFunction function, String label, boolean isLambda, FunctionFrame outer) {
            this.function = function;
            this.label = label;
            this.isLambda = isLambda;
            this.outer = outer;
        }
    }

    record LoopFrame(TExpr.Loop loop, LoopFrame outer) {
    }

    // ==== Lookups ====

    LocalVariable findLocal(String name) {
        for (int i = locals.size() - 1; i >= 0; i--) {
            LocalVariable v = locals.get(i).variable(name);
            if (v != null) return v;
        }
        return null;
    }

    /** The implicit receivers, innermost first. */
    List<ImplicitReceiver> receivers() {
        List<ImplicitReceiver> result = new ArrayList<>();
        for (int i = elements.size() - 1; i >= 0; i--) {
            if (elements.get(i) instanceof ImplicitReceiver r) result.add(r);
        }
        return result;
    }

    // ==== Derived contexts ====

    /** With a new, empty local scope. */
    Context withLocalScope(LocalScope scope) {
        List<LocalScope> newLocals = new ArrayList<>(locals);
        newLocals.add(scope);
        Scope newTypeScope = Scope.of(scope::classifier, typeScope);
        return new Context(file, newTypeScope, List.copyOf(newLocals), elements, function, loop);
    }

    Context withElement(TowerElement element) {
        List<TowerElement> newElements = new ArrayList<>(elements);
        newElements.add(element);
        return new Context(file, typeScope, locals, List.copyOf(newElements), function, loop);
    }

    Context withTypeScope(Scope scope) {
        return new Context(file, scope, locals, elements, function, loop);
    }

    Context withFunction(FunctionFrame frame) {
        return new Context(file, typeScope, locals, elements, frame, null);
    }

    Context withLoop(TExpr.Loop l) {
        return new Context(file, typeScope, locals, elements, function, new LoopFrame(l, loop));
    }

    ClassifierSymbol findClassifier(String name) {
        return typeScope.findClassifier(name);
    }

    /** The innermost class whose body this is in, or null. */
    ClassSymbol enclosingClass() {
        for (int i = elements.size() - 1; i >= 0; i--) {
            if (elements.get(i) instanceof ImplicitReceiver r && r.owner() instanceof ClassSymbol cls) return cls;
        }
        return null;
    }
}
