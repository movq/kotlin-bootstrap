package kotlinbootstrap.dfa;

import java.util.Objects;

import kotlinbootstrap.types.KType;

/**
 * A storage in memory, as FIR's RealVariable: a local variable or
 * parameter, a property of a receiver that is itself a real variable, an
 * object, or an implicit receiver ({@code this}). Equal for the same
 * symbol and receivers, whatever the type.
 *
 * @param symbol            the local variable, property, object class, or for {@code this} the owner of the receiver
 * @param dispatchReceiver  nullable
 * @param extensionReceiver nullable
 */
public record RealVariable(Object symbol, boolean implicit, RealVariable dispatchReceiver,
                           RealVariable extensionReceiver, KType originalType) implements DataFlowVariable {

    public static RealVariable implicit(Object owner, KType type) {
        return new RealVariable(owner, true, null, null, type);
    }

    // The type is not compared: it can be a captured type, which is only equal to itself.
    @Override
    public boolean equals(Object o) {
        return o instanceof RealVariable v && symbol.equals(v.symbol) && implicit == v.implicit
                && Objects.equals(dispatchReceiver, v.dispatchReceiver)
                && Objects.equals(extensionReceiver, v.extensionReceiver);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, implicit, dispatchReceiver, extensionReceiver);
    }

    @Override
    public String toString() {
        String name = (implicit ? "this@" : "") + symbol;
        if (dispatchReceiver != null && extensionReceiver != null) {
            return name + "(" + dispatchReceiver + ", " + extensionReceiver + ")";
        }
        if (dispatchReceiver != null || extensionReceiver != null) {
            return name + "(" + (dispatchReceiver != null ? dispatchReceiver : extensionReceiver) + ")";
        }
        return name;
    }
}
