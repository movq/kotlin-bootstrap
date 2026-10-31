package kotlinbootstrap.check;

import java.util.List;
import java.util.Map;

import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.types.KType;

/**
 * The value of a constant expression: of a const val's initializer or an
 * annotation's argument, as FIR's evaluated literals, enum entries, class
 * literals, arrays and annotations.
 */
public sealed interface ConstantValue {
    KType type();

    /**
     * A Boolean, Character, Byte, Short, Integer, Long, Float, Double or
     * String; for an unsigned type, the signed value of the same width;
     * null for the null literal.
     */
    record Primitive(KType type, Object value) implements ConstantValue {
    }

    record EnumEntry(KType type, PropertySymbol entry) implements ConstantValue {
    }

    /** {@code A::class}, of type {@code KClass<A>}. */
    record ClassLiteral(KType type, KType classType) implements ConstantValue {
    }

    record Array(KType type, List<ConstantValue> elements) implements ConstantValue {
    }

    /** @param args by parameter index; the parameters without one take their default values */
    record Annotation(KType type, FunctionSymbol constructor, Map<Integer, ConstantValue> args)
            implements ConstantValue {
    }
}
