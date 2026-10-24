package kotlinbootstrap.ast;

/** An element of a block: an expression or a local declaration. */
public sealed interface Stmt extends Node permits Expr, Decl {
}
