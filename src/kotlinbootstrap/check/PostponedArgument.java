package kotlinbootstrap.check;

import java.util.List;

import kotlinbootstrap.infer.PostponedAtom;
import kotlinbootstrap.types.TypeVariable;

/**
 * An argument analyzed during the completion of its call, once the types
 * it depends on are known: a lambda or a callable reference. Until then it
 * is a {@link TExpr.Unresolved} with this as its state.
 */
abstract class PostponedArgument implements PostponedAtom {
    private boolean analyzed;
    private TExpr result;

    @Override
    public boolean analyzed() {
        return analyzed;
    }

    /** The analyzed argument, whose types may still have the call's type variables. */
    TExpr result() {
        if (!analyzed) throw new IllegalStateException("Not analyzed: " + this);
        return result;
    }

    void setResult(TExpr result) {
        this.analyzed = true;
        this.result = result;
    }

    /** Type variables the argument adds to the system, like a lambda's return type. */
    List<TypeVariable> typeVariables() {
        return List.of();
    }

    /** Once analyzed, the expressions in it that are completed with the call: a lambda's returned values. */
    List<TExpr> nestedArguments() {
        return List.of();
    }
}
