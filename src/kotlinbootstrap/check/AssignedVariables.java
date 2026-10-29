package kotlinbootstrap.check;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import kotlinbootstrap.ast.Expr;

/**
 * The local variables a loop or lambda assigns, as FIR's
 * FirLocalVariableAssignmentAnalyzer finds them: the names that are targets
 * of assignments, increments and decrements anywhere in its syntax, as the
 * scope at its start resolves them. Works reflectively on the syntax tree.
 */
final class AssignedVariables {
    private AssignedVariables() {
    }

    static Set<LocalVariable> in(Context ctx, Object... nodes) {
        Set<String> names = new LinkedHashSet<>();
        for (Object node : nodes) collect(node, names);
        Set<LocalVariable> result = new LinkedHashSet<>();
        for (String name : names) {
            LocalVariable v = ctx.findLocal(name);
            if (v != null && v.isVar()) result.add(v);
        }
        return result;
    }

    private static void collect(Object node, Set<String> names) {
        if (node == null) return;
        switch (node) {
            case Expr.Assign a -> addTarget(a.target(), names);
            case Expr.Prefix p when p.op() == Expr.PrefixOp.INC || p.op() == Expr.PrefixOp.DEC ->
                    addTarget(p.expr(), names);
            case Expr.Postfix p when p.op() == Expr.PostfixOp.INC || p.op() == Expr.PostfixOp.DEC ->
                    addTarget(p.expr(), names);
            default -> {
            }
        }
        switch (node) {
            case Record r -> {
                for (RecordComponent c : r.getClass().getRecordComponents()) collect(get(r, c), names);
            }
            case Collection<?> c -> {
                for (Object item : c) collect(item, names);
            }
            default -> {
            }
        }
    }

    private static void addTarget(Expr target, Set<String> names) {
        while (target instanceof Expr.Paren p) target = p.expr();
        if (target instanceof Expr.Name n) names.add(n.name());
    }

    private static Object get(Record r, RecordComponent c) {
        try {
            return c.getAccessor().invoke(r);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }
}
