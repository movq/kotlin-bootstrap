package kotlinbootstrap.types;

import java.util.List;

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
            return TypeRenderer.render(this);
        }
    }

    record TypeParamType(TypeParamSymbol symbol, boolean nullable) implements KType {
        @Override
        public TypeParamType withNullable(boolean nullable) {
            return nullable == this.nullable ? this : new TypeParamType(symbol, nullable);
        }

        @Override
        public String toString() {
            return TypeRenderer.render(this);
        }
    }

    /**
     * {@code T & Any}: a type parameter, type variable or captured type
     * (the inner type, which is not nullable) without null.
     */
    record DefinitelyNotNull(KType inner) implements KType {
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
            return TypeRenderer.render(this);
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

        /** Both bounds with or without null: {@code String!} becomes String or {@code String?}. */
        @Override
        public KType withNullable(boolean nullable) {
            return Types.flexible(lower.withNullable(nullable), upper.withNullable(nullable));
        }

        @Override
        public String toString() {
            return TypeRenderer.render(this);
        }
    }

    /** A type variable of a constraint system, standing for a type to be inferred. */
    record TypeVariableType(TypeVariable variable, boolean nullable) implements KType {
        @Override
        public TypeVariableType withNullable(boolean nullable) {
            return nullable == this.nullable ? this : new TypeVariableType(variable, nullable);
        }

        @Override
        public String toString() {
            return TypeRenderer.render(this);
        }
    }

    /**
     * {@code A & B}: a value of both types, e.g. after smart casts. Never
     * nullable as a whole; making it nullable makes the components nullable.
     */
    record Intersection(List<KType> components) implements KType {
        @Override
        public boolean nullable() {
            return components.stream().allMatch(KType::nullable);
        }

        @Override
        public KType withNullable(boolean nullable) {
            return new Intersection(components.stream().map(c -> c.withNullable(nullable)).toList());
        }

        @Override
        public String toString() {
            return TypeRenderer.render(this);
        }
    }

    /**
     * The type of an unknown argument of a projected type: in a value of
     * {@code List<out Number>}, the element type is a type {@code Captured(out Number)}
     * that is a subtype of Number.
     *
     * @param lowerType for an {@code in} projection, the projected type; otherwise null
     */
    record Captured(CapturedTypeConstructor constructor, KType lowerType, boolean nullable) implements KType {
        @Override
        public Captured withNullable(boolean nullable) {
            return nullable == this.nullable ? this : new Captured(constructor, lowerType, nullable);
        }

        @Override
        public String toString() {
            return TypeRenderer.render(this);
        }
    }

    /**
     * The type of an integer literal before its type is known from the
     * context: one of the possible types, by default the first.
     *
     * @param possibleTypes e.g. Int, Long, Byte, Short for {@code 1}; Long for {@code 1L}
     */
    record IntegerLiteral(long value, List<ClassType> possibleTypes, boolean unsigned, boolean nullable)
            implements KType {
        @Override
        public IntegerLiteral withNullable(boolean nullable) {
            return nullable == this.nullable ? this : new IntegerLiteral(value, possibleTypes, unsigned, nullable);
        }

        @Override
        public String toString() {
            return TypeRenderer.render(this);
        }
    }
}
