package kotlinbootstrap.symbols;

import java.util.Set;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.types.KType;

/**
 * A value parameter of a function or constructor.
 *
 * @param type       for a vararg parameter, the element type, as declared
 * @param hasDefault whether the parameter has a default value (possibly
 *                   inherited from an overridden or expected declaration)
 * @param modifiers  {@code vararg}, {@code noinline}, {@code crossinline}
 * @param source     the declaring AST node or element, nullable
 */
public record ValueParamSymbol(String name, KType type, boolean hasDefault, Set<Modifier> modifiers, Object source) {
    public boolean isVararg() {
        return modifiers.contains(Modifier.VARARG);
    }
}
