package kotlinbootstrap.ast;

import java.util.List;

/** Expressions. In Kotlin, control structures are expressions too. */
public sealed interface Expr extends Stmt {
    record Name(int pos, String name) implements Expr {
    }

    /** @param text source text, possibly with underscores, radix prefix and U/L suffixes */
    record IntLit(int pos, String text) implements Expr {
    }

    /** @param text source text, possibly with underscores and an F suffix */
    record FloatLit(int pos, String text) implements Expr {
    }

    record CharLit(int pos, char value) implements Expr {
    }

    record BoolLit(int pos, boolean value) implements Expr {
    }

    record NullLit(int pos) implements Expr {
    }

    /** A string literal. Escapes are decoded and adjacent text is merged. */
    record StringTemplate(int pos, List<StringPart> parts) implements Expr {
    }

    sealed interface StringPart {
    }

    record TextPart(String text) implements StringPart {
    }

    record ExprPart(Expr expr) implements StringPart {
    }

    /** @param label nullable */
    record This(int pos, String label) implements Expr {
    }

    /** {@code super<Q>@label}; @param qualifier nullable; @param label nullable */
    record Super(int pos, TypeRef qualifier, String label) implements Expr {
    }

    record Paren(int pos, Expr expr) implements Expr {
    }

    enum BinaryOp {
        MUL, DIV, REM, PLUS, MINUS, RANGE, RANGE_UNTIL, ELVIS, IN, NOT_IN,
        LT, GT, LE, GE, EQ, NE, IDENTITY_EQ, IDENTITY_NE, AND, OR
    }

    record Binary(int pos, BinaryOp op, Expr left, Expr right) implements Expr {
    }

    /** A call of an infix function: {@code a shl b}. */
    record InfixCall(int pos, Expr left, String name, Expr right) implements Expr {
    }

    enum AssignOp { ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, MUL_ASSIGN, DIV_ASSIGN, REM_ASSIGN }

    record Assign(int pos, AssignOp op, Expr target, Expr value) implements Expr {
    }

    record Is(int pos, Expr expr, TypeRef type, boolean negated) implements Expr {
    }

    record As(int pos, Expr expr, TypeRef type, boolean safe) implements Expr {
    }

    enum PrefixOp { MINUS, PLUS, NOT, INC, DEC }

    record Prefix(int pos, PrefixOp op, Expr expr) implements Expr {
    }

    enum PostfixOp { INC, DEC, NOT_NULL }

    record Postfix(int pos, PostfixOp op, Expr expr) implements Expr {
    }

    /**
     * A call. Method calls have a {@link Member} callee: {@code a.f(x)} is
     * {@code Call(Member(a, f), x)}, whereas {@code (a.f)(x)} has a {@link Paren} callee.
     *
     * @param lambdas trailing lambda arguments
     */
    record Call(int pos, Expr callee, List<TypeRef.TypeArg> typeArgs, List<ValueArg> args, List<Expr> lambdas)
            implements Expr {
    }

    /** Explicit type arguments without a call, as in the receiver of {@code List<String>::class}. */
    record TypeApplication(int pos, Expr expr, List<TypeRef.TypeArg> typeArgs) implements Expr {
    }

    record Index(int pos, Expr receiver, List<Expr> indices) implements Expr {
    }

    /** {@code a.name} or {@code a?.name} */
    record Member(int pos, Expr receiver, String name, boolean safe) implements Expr {
    }

    /** {@code a::name}; @param receiver nullable */
    record CallableRef(int pos, Expr receiver, String name) implements Expr {
    }

    /** {@code a::class} */
    record ClassLiteral(int pos, Expr receiver) implements Expr {
    }

    /**
     * A variable bound by a lambda parameter or for loop: either a name or a
     * destructuring, with an optional type.
     *
     * @param name          nullable if destructuring
     * @param destructuring nullable if name
     * @param type          nullable
     */
    record Binding(int pos, Modifiers modifiers, String name, Decl.Destructuring destructuring, TypeRef type)
            implements Node {
    }

    /**
     * @param params   explicit parameters
     * @param hasArrow whether there was a parameter list and arrow; if not, the
     *                 lambda may have an implicit {@code it} parameter
     */
    record Lambda(int pos, List<Binding> params, boolean hasArrow, List<Stmt> body) implements Expr {
    }

    record AnonymousFunction(int pos, Decl.Function function) implements Expr {
    }

    record ObjectLiteral(int pos, Decl.Class decl) implements Expr {
    }

    /** A local declaration in expression position, e.g. {@code if (c) val x = 1}. */
    record DeclExpr(int pos, Decl decl) implements Expr {
    }

    /** A block that is not a lambda: a function body or a control structure branch. */
    record Block(int pos, List<Stmt> statements) implements Expr {
    }

    /** @param then nullable; @param otherwise nullable */
    record If(int pos, Expr condition, Expr then, Expr otherwise) implements Expr {
    }

    /**
     * @param subject         nullable
     * @param subjectVariable {@code when (val x = ...)}, nullable
     */
    record When(int pos, Expr subject, Decl.Property subjectVariable, List<WhenEntry> entries) implements Expr {
    }

    /**
     * @param conditions empty for the {@code else} entry
     * @param guard      {@code if} guard, nullable
     */
    record WhenEntry(int pos, List<WhenCondition> conditions, Expr guard, Expr body) implements Node {
    }

    sealed interface WhenCondition extends Node {
    }

    record ExprCondition(int pos, Expr expr) implements WhenCondition {
    }

    record InCondition(int pos, Expr expr, boolean negated) implements WhenCondition {
    }

    record IsCondition(int pos, TypeRef type, boolean negated) implements WhenCondition {
    }

    /** @param finallyBlock nullable */
    record Try(int pos, Block body, List<Catch> catches, Block finallyBlock) implements Expr {
    }

    record Catch(int pos, Decl.Param param, Block body) implements Node {
    }

    /** @param body nullable for an empty body ({@code for (x in xs);}) */
    record For(int pos, Binding variable, Expr iterable, Expr body) implements Expr {
    }

    /** @param body nullable */
    record While(int pos, Expr condition, Expr body) implements Expr {
    }

    /** @param body nullable */
    record DoWhile(int pos, Expr body, Expr condition) implements Expr {
    }

    /** @param label nullable; @param value nullable */
    record Return(int pos, String label, Expr value) implements Expr {
    }

    /** @param label nullable */
    record Break(int pos, String label) implements Expr {
    }

    /** @param label nullable */
    record Continue(int pos, String label) implements Expr {
    }

    record Throw(int pos, Expr expr) implements Expr {
    }

    /** {@code label@ expr} */
    record Labeled(int pos, String label, Expr expr) implements Expr {
    }

    record Annotated(int pos, List<Annotation> annotations, Expr expr) implements Expr {
    }

    /** {@code [a, b]}, only valid in annotation arguments. */
    record CollectionLiteral(int pos, List<Expr> elements) implements Expr {
    }
}
