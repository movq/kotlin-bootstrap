package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import kotlinbootstrap.types.KType;

/** Operations on checked expression trees. */
final class TExprs {
    private TExprs() {
    }

    /**
     * Rebuilds a node with its child expressions and types transformed.
     * Function bodies (of lambdas and local functions) are not children.
     */
    static TExpr map(TExpr e, UnaryOperator<TExpr> f, UnaryOperator<KType> t) {
        return switch (e) {
            case TExpr.Const c -> new TExpr.Const(c.pos(), t.apply(c.type()), c.value());
            case TExpr.StringTemplate s -> new TExpr.StringTemplate(s.pos(), t.apply(s.type()), all(s.parts(), f));
            case TExpr.This x -> new TExpr.This(x.pos(), t.apply(x.type()), x.owner(), x.implicit());
            case TExpr.ObjectValue o -> new TExpr.ObjectValue(o.pos(), t.apply(o.type()), o.object());
            case TExpr.LocalGet g -> new TExpr.LocalGet(g.pos(), t.apply(g.type()), g.variable());
            case TExpr.LocalSet s -> new TExpr.LocalSet(s.pos(), t.apply(s.type()), s.variable(), f.apply(s.value()));
            case TExpr.PropertyGet g -> new TExpr.PropertyGet(g.pos(), t.apply(g.type()), g.property(),
                    types(g.typeArgs(), t), opt(g.dispatchReceiver(), f), opt(g.extensionReceiver(), f), g.superCall());
            case TExpr.PropertySet s -> new TExpr.PropertySet(s.pos(), t.apply(s.type()), s.property(),
                    types(s.typeArgs(), t), opt(s.dispatchReceiver(), f), opt(s.extensionReceiver(), f), s.superCall(),
                    f.apply(s.value()));
            case TExpr.FieldGet g -> new TExpr.FieldGet(g.pos(), t.apply(g.type()), g.property(), opt(g.receiver(), f));
            case TExpr.FieldSet s -> new TExpr.FieldSet(s.pos(), t.apply(s.type()), s.property(), opt(s.receiver(), f),
                    f.apply(s.value()));
            case TExpr.Call c -> new TExpr.Call(c.pos(), t.apply(c.type()), c.function(), types(c.typeArgs(), t),
                    opt(c.dispatchReceiver(), f), opt(c.extensionReceiver(), f), all(c.contextArgs(), f),
                    args(c.args(), f), c.superCall(), c.origin());
            case TExpr.Vararg v -> new TExpr.Vararg(v.pos(), t.apply(v.type()), v.elements().stream()
                    .map(el -> new TExpr.VarargElement(f.apply(el.value()), el.spread())).toList());
            case TExpr.DelegatingConstructorCall d -> new TExpr.DelegatingConstructorCall(d.pos(), t.apply(d.type()),
                    d.constructor(), types(d.typeArgs(), t), opt(d.outerReceiver(), f), args(d.args(), f));
            case TExpr.SafeCall s -> new TExpr.SafeCall(s.pos(), t.apply(s.type()), f.apply(s.receiver()), s.subject(),
                    f.apply(s.selector()));
            case TExpr.Equality q -> new TExpr.Equality(q.pos(), t.apply(q.type()), q.op(), f.apply(q.left()),
                    f.apply(q.right()));
            case TExpr.Comparison c -> new TExpr.Comparison(c.pos(), t.apply(c.type()), c.op(), f.apply(c.compareTo()));
            case TExpr.Logical l -> new TExpr.Logical(l.pos(), t.apply(l.type()), l.and(), f.apply(l.left()),
                    f.apply(l.right()));
            case TExpr.Elvis v -> new TExpr.Elvis(v.pos(), t.apply(v.type()), f.apply(v.left()), f.apply(v.right()));
            case TExpr.NotNull n -> new TExpr.NotNull(n.pos(), t.apply(n.type()), f.apply(n.expr()));
            case TExpr.TypeCheck c -> new TExpr.TypeCheck(c.pos(), t.apply(c.type()), f.apply(c.expr()),
                    t.apply(c.target()), c.negated());
            case TExpr.Cast c -> new TExpr.Cast(c.pos(), t.apply(c.type()), f.apply(c.expr()), t.apply(c.target()),
                    c.safe());
            case TExpr.SmartCast s -> new TExpr.SmartCast(s.pos(), t.apply(s.type()), f.apply(s.expr()));
            case TExpr.SamConversion s -> new TExpr.SamConversion(s.pos(), t.apply(s.type()), f.apply(s.expr()));
            case TExpr.SuspendConversion s -> new TExpr.SuspendConversion(s.pos(), t.apply(s.type()), f.apply(s.expr()));
            case TExpr.Block b -> new TExpr.Block(b.pos(), t.apply(b.type()), all(b.statements(), f));
            case TExpr.If i -> new TExpr.If(i.pos(), t.apply(i.type()), f.apply(i.condition()), f.apply(i.then()),
                    opt(i.otherwise(), f));
            case TExpr.When w -> new TExpr.When(w.pos(), t.apply(w.type()), opt(w.subject(), f), w.branches().stream()
                    .map(b -> new TExpr.WhenBranch(opt(b.condition(), f), f.apply(b.body()))).toList(), w.exhaustive());
            case TExpr.While w -> new TExpr.While(w.pos(), t.apply(w.type()), w.loop(), f.apply(w.condition()),
                    f.apply(w.body()), w.doWhile());
            case TExpr.Break b -> new TExpr.Break(b.pos(), t.apply(b.type()), b.loop());
            case TExpr.Continue c -> new TExpr.Continue(c.pos(), t.apply(c.type()), c.loop());
            case TExpr.Return r -> new TExpr.Return(r.pos(), t.apply(r.type()), r.target(), opt(r.value(), f));
            case TExpr.Throw x -> new TExpr.Throw(x.pos(), t.apply(x.type()), f.apply(x.expr()));
            case TExpr.Try x -> new TExpr.Try(x.pos(), t.apply(x.type()), f.apply(x.body()), x.catches().stream()
                    .map(c -> new TExpr.CatchClause(c.param(), f.apply(c.body()))).toList(), opt(x.finallyBlock(), f));
            case TExpr.LocalVarDecl d -> new TExpr.LocalVarDecl(d.pos(), t.apply(d.type()), d.variable(),
                    opt(d.initializer(), f));
            case TExpr.LocalFunctionDecl d -> new TExpr.LocalFunctionDecl(d.pos(), t.apply(d.type()), d.function(),
                    d.body());
            case TExpr.LocalClassDecl d -> new TExpr.LocalClassDecl(d.pos(), t.apply(d.type()), d.cls());
            case TExpr.Lambda l -> new TExpr.Lambda(l.pos(), t.apply(l.type()), l.function(), l.anonymousFunction());
            case TExpr.CallableReference r -> new TExpr.CallableReference(r.pos(), t.apply(r.type()), r.target(),
                    types(r.typeArgs(), t), opt(r.boundReceiver(), f));
            case TExpr.ClassReference r -> new TExpr.ClassReference(r.pos(), t.apply(r.type()), t.apply(r.classType()),
                    opt(r.receiver(), f));
            case TExpr.ObjectLiteral o -> new TExpr.ObjectLiteral(o.pos(), t.apply(o.type()), o.cls());
            case TExpr.Unresolved u -> new TExpr.Unresolved(u.pos(), t.apply(u.type()), u.state());
        };
    }

    private static TExpr opt(TExpr e, UnaryOperator<TExpr> f) {
        return e == null ? null : f.apply(e);
    }

    private static List<TExpr> all(List<TExpr> es, UnaryOperator<TExpr> f) {
        List<TExpr> result = new ArrayList<>(es.size());
        for (TExpr e : es) result.add(f.apply(e));
        return result;
    }

    private static List<TExpr.Argument> args(List<TExpr.Argument> args, UnaryOperator<TExpr> f) {
        return args.stream().map(a -> new TExpr.Argument(a.param(), f.apply(a.value()))).toList();
    }

    private static List<KType> types(List<KType> types, UnaryOperator<KType> t) {
        return types.stream().map(t).toList();
    }

    /**
     * The expression a block's value comes from: its last statement, if
     * that is an expression rather than a declaration or assignment; else null.
     */
    static TExpr lastExpression(TExpr.Block block) {
        if (block.statements().isEmpty()) return null;
        TExpr last = block.statements().getLast();
        return isStatement(last) ? null : last;
    }

    /** Declarations, assignments and loops, which have no value. */
    static boolean isStatement(TExpr e) {
        return switch (e) {
            case TExpr.LocalVarDecl d -> true;
            case TExpr.LocalFunctionDecl d -> true;
            case TExpr.LocalClassDecl d -> true;
            case TExpr.LocalSet s -> true;
            case TExpr.PropertySet s -> true;
            case TExpr.FieldSet s -> true;
            case TExpr.While w -> true;
            default -> false;
        };
    }
}
