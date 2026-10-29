package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.Stmt;
import kotlinbootstrap.ast.TypeRef;
import kotlinbootstrap.types.KType;

/**
 * The contract of a function, as FIR's ConeEffectDeclarations: what its
 * returning implies about its arguments, and how it calls its lambdas.
 * Arguments are by index: -1 is the receiver, then the value parameters.
 *
 * @param returns      the {@code returns(...) implies (...)} effects
 * @param callsInPlace for the parameters it calls in place, how often
 */
record Contract(List<Returns> returns, Map<Integer, InvocationKind> callsInPlace) {

    enum InvocationKind {
        AT_MOST_ONCE, EXACTLY_ONCE, AT_LEAST_ONCE, UNKNOWN;

        boolean isDefinitelyVisited() {
            return this == EXACTLY_ONCE || this == AT_LEAST_ONCE;
        }

        boolean canBeRevisited() {
            return this == AT_LEAST_ONCE || this == UNKNOWN;
        }
    }

    /** What a function returns: {@code returns()} is ANY. */
    enum ReturnValue { ANY, TRUE, FALSE, NULL, NOT_NULL }

    record Returns(ReturnValue value, Condition condition) {
    }

    /** A Boolean expression over the arguments. */
    sealed interface Condition {
        record Constant(boolean value) implements Condition {
        }

        record IsNull(int arg, boolean negated) implements Condition {
        }

        record IsInstance(int arg, KType type, boolean negated) implements Condition {
        }

        /** A Boolean argument's value. */
        record BooleanArg(int arg) implements Condition {
        }

        record Not(Condition condition) implements Condition {
        }

        record Logical(boolean and, Condition left, Condition right) implements Condition {
        }
    }

    static final Contract NONE = new Contract(List.of(), Map.of());

    // ==== Parsing ====

    /**
     * The contract a function declares, in a {@code contract [...]} clause
     * or as the {@code contract { ... }} call its body begins with. Effects
     * that are not understood are left out.
     */
    static Contract of(Decl.Function f, Function<TypeRef, KType> resolveType) {
        List<Expr> effects = f.contract();
        if (effects.isEmpty() && f.body() instanceof Expr.Block b && !b.statements().isEmpty()
                && b.statements().getFirst() instanceof Expr.Call c && c.callee() instanceof Expr.Name n
                && n.name().equals("contract") && c.args().isEmpty() && c.lambdas().size() == 1
                && c.lambdas().getFirst() instanceof Expr.Lambda l) {
            effects = new ArrayList<>();
            for (Stmt s : l.body()) {
                if (s instanceof Expr e) effects.add(e);
            }
        }
        if (effects.isEmpty()) return NONE;
        Parser p = new Parser(f, resolveType);
        List<Returns> returns = new ArrayList<>();
        Map<Integer, InvocationKind> callsInPlace = new HashMap<>();
        for (Expr e : effects) {
            switch (e) {
                case Expr.InfixCall i when i.name().equals("implies") -> {
                    ReturnValue value = p.returnValue(i.left());
                    Condition condition = p.condition(i.right());
                    if (value != null && condition != null) returns.add(new Returns(value, condition));
                }
                case Expr.Call c when c.callee() instanceof Expr.Name n && n.name().equals("callsInPlace")
                        && !c.args().isEmpty() -> {
                    Integer arg = p.argument(c.args().getFirst().value());
                    InvocationKind kind = c.args().size() < 2 ? InvocationKind.UNKNOWN
                            : c.args().get(1).value() instanceof Expr.Member m
                            ? InvocationKind.valueOf(m.name()) : null;
                    if (arg != null && kind != null) callsInPlace.put(arg, kind);
                }
                default -> {
                }
            }
        }
        return new Contract(List.copyOf(returns), Map.copyOf(callsInPlace));
    }

    private record Parser(Decl.Function function, Function<TypeRef, KType> resolveType) {
        ReturnValue returnValue(Expr e) {
            if (!(e instanceof Expr.Call c) || !(c.callee() instanceof Expr.Name n)) return null;
            if (n.name().equals("returnsNotNull") && c.args().isEmpty()) return ReturnValue.NOT_NULL;
            if (!n.name().equals("returns")) return null;
            if (c.args().isEmpty()) return ReturnValue.ANY;
            return switch (c.args().getFirst().value()) {
                case Expr.BoolLit b -> b.value() ? ReturnValue.TRUE : ReturnValue.FALSE;
                case Expr.NullLit l -> ReturnValue.NULL;
                default -> null;
            };
        }

        Condition condition(Expr e) {
            return switch (e) {
                case Expr.Paren p -> condition(p.expr());
                case Expr.BoolLit b -> new Condition.Constant(b.value());
                case Expr.Prefix p when p.op() == Expr.PrefixOp.NOT -> {
                    Condition c = condition(p.expr());
                    yield c == null ? null : new Condition.Not(c);
                }
                case Expr.Binary b when b.op() == Expr.BinaryOp.AND || b.op() == Expr.BinaryOp.OR -> {
                    Condition left = condition(b.left());
                    Condition right = condition(b.right());
                    yield left == null || right == null ? null
                            : new Condition.Logical(b.op() == Expr.BinaryOp.AND, left, right);
                }
                case Expr.Binary b when (b.op() == Expr.BinaryOp.EQ || b.op() == Expr.BinaryOp.NE)
                        && b.right() instanceof Expr.NullLit -> {
                    Integer arg = argument(b.left());
                    yield arg == null ? null : new Condition.IsNull(arg, b.op() == Expr.BinaryOp.NE);
                }
                case Expr.Is i -> {
                    Integer arg = argument(i.expr());
                    KType type = resolveType.apply(i.type());
                    yield arg == null || type == null ? null : new Condition.IsInstance(arg, type, i.negated());
                }
                default -> {
                    Integer arg = argument(e);
                    yield arg == null ? null : new Condition.BooleanArg(arg);
                }
            };
        }

        /** The index of the argument an expression names; null if none. */
        Integer argument(Expr e) {
            switch (e) {
                case Expr.Paren p -> {
                    return argument(p.expr());
                }
                case Expr.This t when function.receiver() != null
                        && (t.label() == null || t.label().equals(function.name())) -> {
                    return -1;
                }
                case Expr.Name n -> {
                    for (int i = 0; i < function.params().size(); i++) {
                        if (function.params().get(i).name().equals(n.name())) return i;
                    }
                    return null;
                }
                default -> {
                    return null;
                }
            }
        }
    }
}
