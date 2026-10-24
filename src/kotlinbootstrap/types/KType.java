package kotlinbootstrap.types;

import java.util.List;
import java.util.stream.Collectors;

import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;

/**
 * A resolved type. Type aliases are expanded away. Function types are class
 * types of {@code kotlin.FunctionN} (or {@code SuspendFunctionN}), with a
 * flag for extension function types, as in kotlinc.
 */
public sealed interface KType {
    boolean nullable();

    KType withNullable(boolean nullable);

    /**
     * {@code C<A, B>}. For an inner class, the arguments are those of the
     * class followed by those of its outer classes, innermost first.
     *
     * @param extensionFunction a function type with a receiver, {@code A.(B) -> C}
     * @param contextParams     the number of leading arguments of a function type
     *                          that are context parameters
     */
    record ClassType(ClassSymbol symbol, List<TypeArg> args, boolean nullable, boolean extensionFunction,
                     int contextParams) implements KType {
        public ClassType(ClassSymbol symbol, List<TypeArg> args, boolean nullable) {
            this(symbol, args, nullable, false, 0);
        }

        @Override
        public ClassType withNullable(boolean nullable) {
            return nullable == this.nullable ? this
                    : new ClassType(symbol, args, nullable, extensionFunction, contextParams);
        }

        @Override
        public String toString() {
            String s = symbol.id().fqName();
            if (!args.isEmpty()) s += args.stream().map(TypeArg::toString).collect(Collectors.joining(", ", "<", ">"));
            if (extensionFunction) s = "@ExtensionFunctionType " + s;
            if (contextParams > 0) s = "@ContextFunctionTypeParams(" + contextParams + ") " + s;
            return nullable ? s + "?" : s;
        }
    }

    record TypeParamType(TypeParamSymbol symbol, boolean nullable) implements KType {
        @Override
        public TypeParamType withNullable(boolean nullable) {
            return nullable == this.nullable ? this : new TypeParamType(symbol, nullable);
        }

        @Override
        public String toString() {
            return nullable ? symbol.name() + "?" : symbol.name();
        }
    }

    /** {@code T & Any} */
    record DefinitelyNotNull(TypeParamType inner) implements KType {
        @Override
        public boolean nullable() {
            return false;
        }

        @Override
        public KType withNullable(boolean nullable) {
            return nullable ? inner.withNullable(true) : this;
        }

        @Override
        public String toString() {
            return inner.withNullable(false) + " & Any";
        }
    }

    /**
     * A platform type from Java, {@code (lower..upper)}: e.g. {@code String!}
     * is {@code (String..String?)}, and a Java {@code List<T>} is
     * {@code (MutableList<T>..List<T>?)}.
     */
    record Flexible(KType lower, KType upper) implements KType {
        @Override
        public boolean nullable() {
            return lower.nullable();
        }

        @Override
        public KType withNullable(boolean nullable) {
            return nullable ? upper.withNullable(true) : lower.withNullable(false);
        }

        @Override
        public String toString() {
            return "(" + lower + ".." + upper + ")";
        }
    }
}
