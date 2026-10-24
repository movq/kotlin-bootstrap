package kotlinbootstrap.ast;

import java.util.List;

/** A type as written in source. */
public sealed interface TypeRef extends Node {
    /** A possibly qualified, possibly generic type name: {@code a.B<C>.D<E>}. */
    record Simple(int pos, List<Segment> segments) implements TypeRef {
    }

    record Segment(String name, List<TypeArg> args) {
    }

    record Nullable(int pos, TypeRef inner) implements TypeRef {
    }

    /**
     * {@code context(A) R.(P) -> T}.
     *
     * @param receiver nullable
     */
    record Function(int pos, List<NamedType> contextParams, TypeRef receiver, List<NamedType> params,
                    TypeRef returnType) implements TypeRef {
    }

    /** {@code A & B}; in practice only {@code T & Any}. */
    record Intersection(int pos, TypeRef left, TypeRef right) implements TypeRef {
    }

    record Dynamic(int pos) implements TypeRef {
    }

    /** A type with annotations and/or the {@code suspend} modifier. */
    record Modified(int pos, Modifiers modifiers, TypeRef inner) implements TypeRef {
    }

    enum Variance { INVARIANT, IN, OUT }

    /**
     * A type argument.
     *
     * @param type null for a star projection
     */
    record TypeArg(int pos, Variance variance, TypeRef type) implements Node {
    }
}
