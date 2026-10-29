package kotlinbootstrap.dfa;

import kotlinbootstrap.types.KType;

/**
 * A type a value can be known not to have, as FIR's DfaType: a type, a
 * singleton (an enum entry or object), or a boolean value.
 */
public sealed interface DfaType {
    record Cone(KType type) implements DfaType {
        @Override
        public String toString() {
            return type.toString();
        }
    }

    record Symbol(Object symbol) implements DfaType {
        @Override
        public String toString() {
            return symbol.toString();
        }
    }

    record BooleanLiteral(boolean value) implements DfaType {
        @Override
        public String toString() {
            return Boolean.toString(value);
        }
    }
}
