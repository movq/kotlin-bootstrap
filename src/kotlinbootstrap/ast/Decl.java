package kotlinbootstrap.ast;

import java.util.List;

/** Declarations: top-level, member and local. */
public sealed interface Decl extends Stmt {
    enum ClassKind { CLASS, INTERFACE, OBJECT }

    /**
     * A class, interface, or object (including companion objects and object
     * literals). Enum-ness, data-ness etc. are modifiers.
     *
     * @param name               nullable for object literals and unnamed companions
     * @param primaryConstructor nullable
     * @param enumEntries        empty unless this is an enum class
     */
    record Class(int pos, Modifiers modifiers, ClassKind kind, String name, List<TypeParam> typeParams,
                 PrimaryConstructor primaryConstructor, List<SuperTypeEntry> superTypes,
                 List<TypeConstraint> constraints, List<EnumEntry> enumEntries, List<Decl> members)
            implements Decl {
    }

    /**
     * A function. Anonymous functions are wrapped in {@link Expr.AnonymousFunction}.
     *
     * @param receiver   nullable
     * @param name       nullable for anonymous functions
     * @param returnType nullable
     * @param contract   effects of a {@code contract [...]} clause, empty if absent
     * @param body       a {@link Expr.Block} for block bodies, any other expression for
     *                   expression bodies ({@code = expr}), null if there is no body
     */
    record Function(int pos, Modifiers modifiers, List<TypeParam> typeParams, TypeRef receiver, String name,
                    List<Param> params, TypeRef returnType, List<TypeConstraint> constraints,
                    List<Expr> contract, Expr body) implements Decl {
    }

    /**
     * A property, member or local.
     *
     * @param receiver     nullable
     * @param type         nullable
     * @param initializer  nullable
     * @param delegate     the expression after {@code by}, nullable
     * @param getter       nullable
     * @param setter       nullable
     * @param backingField an explicit {@code field = ...} declaration, nullable
     */
    record Property(int pos, Modifiers modifiers, boolean isVar, List<TypeParam> typeParams, TypeRef receiver,
                    String name, TypeRef type, List<TypeConstraint> constraints, Expr initializer, Expr delegate,
                    Accessor getter, Accessor setter, BackingField backingField) implements Decl {
    }

    /**
     * {@code val (a, b) = e}, {@code val [a, b] = e} or {@code (val a, var b) = e};
     * also used without initializer for destructured lambda and loop parameters.
     *
     * @param valOrVar       the keyword before the parenthesis; NONE for the full
     *                       form, where each entry has its own
     * @param squareBrackets whether written with [] (positional) rather than ()
     * @param initializer    nullable
     */
    record Destructuring(int pos, Modifiers modifiers, ValOrVar valOrVar, boolean squareBrackets,
                         List<DestructuringEntry> entries, Expr initializer) implements Decl {
    }

    record TypeAlias(int pos, Modifiers modifiers, String name, List<TypeParam> typeParams, TypeRef type)
            implements Decl {
    }

    /** {@code init { ... }} */
    record Initializer(int pos, Modifiers modifiers, Expr.Block body) implements Decl {
    }

    /** {@code companion { ... }}: members that belong to the class rather than an instance. */
    record CompanionBlock(int pos, List<Decl> members) implements Decl {
    }

    enum DelegationKind { NONE, THIS, SUPER }

    /** @param body nullable */
    record SecondaryConstructor(int pos, Modifiers modifiers, List<Param> params, DelegationKind delegation,
                                List<ValueArg> delegationArgs, Expr.Block body) implements Decl {
    }

    enum ValOrVar { NONE, VAL, VAR }

    /**
     * A function, constructor, setter or catch parameter.
     *
     * @param type         nullable (only for setter parameters)
     * @param defaultValue nullable
     */
    record Param(int pos, Modifiers modifiers, ValOrVar valOrVar, String name, TypeRef type, Expr defaultValue)
            implements Node {
    }

    record PrimaryConstructor(int pos, Modifiers modifiers, List<Param> params) implements Node {
    }

    /**
     * An entry in a class's supertype list.
     *
     * @param args     constructor arguments, null if the type is not invoked
     *                 (an interface, or a class constructed by a secondary constructor)
     * @param delegate the expression after {@code by}, nullable
     */
    record SuperTypeEntry(int pos, TypeRef type, List<ValueArg> args, Expr delegate) implements Node {
    }

    /** @param body class body members, null if there is no body */
    record EnumEntry(int pos, Modifiers modifiers, String name, List<ValueArg> args, List<Decl> body)
            implements Node {
    }

    /**
     * A getter or setter.
     *
     * @param param      the setter parameter, nullable
     * @param returnType nullable
     * @param body       as for {@link Function#body()}; null for e.g. {@code private set}
     */
    record Accessor(int pos, Modifiers modifiers, boolean isGetter, Param param, TypeRef returnType, Expr body)
            implements Node {
    }

    /** @param type nullable; @param initializer nullable */
    record BackingField(int pos, Modifiers modifiers, TypeRef type, Expr initializer) implements Node {
    }

    /**
     * An entry of a destructuring declaration.
     *
     * @param valOrVar   only for the full form {@code (val a, var b)}
     * @param type       nullable
     * @param sourceName for name-based renaming {@code (val a = b)}, nullable
     */
    record DestructuringEntry(int pos, Modifiers modifiers, ValOrVar valOrVar, String name, TypeRef type,
                              String sourceName) implements Node {
    }

    /** @param bound nullable */
    record TypeParam(int pos, Modifiers modifiers, String name, TypeRef bound) implements Node {
    }

    /** {@code where T : Bound} */
    record TypeConstraint(int pos, List<Annotation> annotations, String name, TypeRef bound) implements Node {
    }
}
