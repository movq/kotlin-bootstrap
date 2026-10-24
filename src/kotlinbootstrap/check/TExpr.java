package kotlinbootstrap.check;

import java.util.List;

import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.types.KType;

/**
 * The checked, fully resolved form of an expression or statement: every
 * name is bound to a symbol, every node has its type, and what Kotlin
 * leaves implicit is explicit: receivers, smart casts, conversions,
 * operator calls ({@code a + b} is a call of {@code plus}), and the
 * desugaring of for loops, destructuring and compound assignments.
 *
 * <p>Statements are expressions of type Unit. Types are those kotlinc
 * infers, before the approximation it does for declared types.
 */
public sealed interface TExpr {
    int pos();

    KType type();

    // ==== Values ====

    /**
     * A literal: a Boolean, Character, String, a Long for any integer type,
     * a Double for Double and Float, or null.
     */
    record Const(int pos, KType type, Object value) implements TExpr {
    }

    /** A string template: the parts converted to strings and concatenated. */
    record StringTemplate(int pos, KType type, List<TExpr> parts) implements TExpr {
    }

    /**
     * A receiver: {@code this}, explicit or implicit.
     *
     * @param owner the class, the function or property with an extension
     *              receiver, or the {@link CheckedFunction} of a lambda with a receiver
     */
    record This(int pos, KType type, Object owner, boolean implicit) implements TExpr {
    }

    /** An object (or companion object) used as a value, e.g. as a receiver. */
    record ObjectValue(int pos, KType type, ClassSymbol object) implements TExpr {
    }

    record LocalGet(int pos, KType type, LocalVariable variable) implements TExpr {
    }

    record LocalSet(int pos, KType type, LocalVariable variable, TExpr value) implements TExpr {
    }

    /**
     * A property read, through the getter unless reading the backing field.
     *
     * @param dispatchReceiver  for a member, the instance; null otherwise
     * @param extensionReceiver for an extension, the receiver; null otherwise
     * @param typeArgs          for a generic extension property
     */
    record PropertyGet(int pos, KType type, PropertySymbol property, List<KType> typeArgs, TExpr dispatchReceiver,
                       TExpr extensionReceiver, boolean superCall) implements TExpr {
    }

    record PropertySet(int pos, KType type, PropertySymbol property, List<KType> typeArgs, TExpr dispatchReceiver,
                       TExpr extensionReceiver, boolean superCall, TExpr value) implements TExpr {
    }

    /** The backing field of a property, from its accessors or initializers: {@code field}. */
    record FieldGet(int pos, KType type, PropertySymbol property, TExpr receiver) implements TExpr {
    }

    record FieldSet(int pos, KType type, PropertySymbol property, TExpr receiver, TExpr value) implements TExpr {
    }

    // ==== Calls ====

    /** How a call was written. */
    enum CallOrigin {
        /** {@code f(x)} or {@code a.f(x)} */
        PLAIN,
        /** an operator: {@code a + b}, {@code -a}, {@code a in b}, {@code a[i]}, {@code a()} on a value, ... */
        OPERATOR,
        /** {@code a f b} */
        INFIX,
        /** part of a desugaring: iterator, hasNext, next, componentN, getValue, ... */
        DESUGARED
    }

    /**
     * An argument, for the parameter with the given index. Calls keep their
     * arguments in source order, which is the order of evaluation.
     *
     * @param value for a vararg parameter, a {@link Vararg}
     */
    record Argument(int param, TExpr value) {
    }

    /**
     * A call of a function or constructor. Parameters without an argument
     * take their default values.
     *
     * @param typeArgs         the function's type arguments, explicit or inferred;
     *                         for a constructor, the class's
     * @param dispatchReceiver for a member, the instance; null otherwise
     * @param superCall        whether the dispatch receiver is {@code super}: not virtual
     */
    record Call(int pos, KType type, FunctionSymbol function, List<KType> typeArgs, TExpr dispatchReceiver,
                TExpr extensionReceiver, List<TExpr> contextArgs, List<Argument> args, boolean superCall,
                CallOrigin origin) implements TExpr {
    }

    /** The arguments for a vararg parameter; spread arguments ({@code *a}) are arrays. */
    record Vararg(int pos, KType type, List<VarargElement> elements) implements TExpr {
    }

    record VarargElement(TExpr value, boolean spread) {
    }

    /** {@code this(...)} or {@code super(...)} from a constructor, or a supertype initializer. */
    record DelegatingConstructorCall(int pos, KType type, FunctionSymbol constructor, List<KType> typeArgs,
                                     TExpr outerReceiver, List<Argument> args) implements TExpr {
    }

    /**
     * {@code a?.b}: the selector reads the receiver's value from the
     * variable, and the whole is null if the receiver is.
     */
    record SafeCall(int pos, KType type, TExpr receiver, LocalVariable subject, TExpr selector) implements TExpr {
    }

    // ==== Operators with built-in meaning ====

    enum EqualityOp { EQ, NE, IDENTITY_EQ, IDENTITY_NE }

    /** {@code ==} (through equals, null-safe), {@code !=}, {@code ===} and {@code !==}. */
    record Equality(int pos, KType type, EqualityOp op, TExpr left, TExpr right) implements TExpr {
    }

    enum ComparisonOp { LT, GT, LE, GE }

    /** {@code a < b}: the result of {@code a.compareTo(b)} compared with 0. */
    record Comparison(int pos, KType type, ComparisonOp op, TExpr compareTo) implements TExpr {
    }

    /** {@code &&} and {@code ||}, which evaluate the right side only if needed. */
    record Logical(int pos, KType type, boolean and, TExpr left, TExpr right) implements TExpr {
    }

    /** {@code a ?: b} */
    record Elvis(int pos, KType type, TExpr left, TExpr right) implements TExpr {
    }

    /** {@code a!!} */
    record NotNull(int pos, KType type, TExpr expr) implements TExpr {
    }

    /** {@code a is T}, {@code a !is T} */
    record TypeCheck(int pos, KType type, TExpr expr, KType target, boolean negated) implements TExpr {
    }

    /** {@code a as T}, {@code a as? T} */
    record Cast(int pos, KType type, TExpr expr, KType target, boolean safe) implements TExpr {
    }

    // ==== Implicit conversions ====

    /** An expression known by data flow to have a more specific type than its own. */
    record SmartCast(int pos, KType type, TExpr expr) implements TExpr {
    }

    /** A function value converted to a Java functional (SAM) interface or a Kotlin {@code fun interface}. */
    record SamConversion(int pos, KType type, TExpr expr) implements TExpr {
    }

    /** A non-suspend function value used as a suspend function type. */
    record SuspendConversion(int pos, KType type, TExpr expr) implements TExpr {
    }

    // ==== Control flow ====

    /** Statements; the value is that of the last, unless the block has type Unit. */
    record Block(int pos, KType type, List<TExpr> statements) implements TExpr {
    }

    /** @param otherwise nullable, for an {@code if} without {@code else}, which has type Unit */
    record If(int pos, KType type, TExpr condition, TExpr then, TExpr otherwise) implements TExpr {
    }

    /**
     * A {@code when}. Conditions are Boolean expressions; for a {@code when}
     * with a subject they test the subject variable.
     *
     * @param subject    the declaration of the subject variable, nullable
     * @param exhaustive whether some branch is always taken, with or without {@code else}
     */
    record When(int pos, KType type, TExpr subject, List<WhenBranch> branches, boolean exhaustive)
            implements TExpr {
    }

    /** @param condition null for {@code else} */
    record WhenBranch(TExpr condition, TExpr body) {
    }

    /** A loop, as the target of break and continue. Compared by identity. */
    final class Loop {
        private final String label;

        public Loop(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label != null ? label : "loop";
        }
    }

    /** {@code while} and {@code do-while}; for loops are desugared into while loops. */
    record While(int pos, KType type, Loop loop, TExpr condition, TExpr body, boolean doWhile) implements TExpr {
    }

    record Break(int pos, KType type, Loop loop) implements TExpr {
    }

    record Continue(int pos, KType type, Loop loop) implements TExpr {
    }

    /** @param target the function or lambda returned from */
    record Return(int pos, KType type, CheckedFunction target, TExpr value) implements TExpr {
    }

    record Throw(int pos, KType type, TExpr expr) implements TExpr {
    }

    /** @param finallyBlock nullable */
    record Try(int pos, KType type, TExpr body, List<CatchClause> catches, TExpr finallyBlock) implements TExpr {
    }

    record CatchClause(LocalVariable param, TExpr body) {
    }

    // ==== Local declarations ====

    /** @param initializer nullable */
    record LocalVarDecl(int pos, KType type, LocalVariable variable, TExpr initializer) implements TExpr {
    }

    record LocalFunctionDecl(int pos, KType type, FunctionSymbol function, CheckedFunction body) implements TExpr {
    }

    record LocalClassDecl(int pos, KType type, ClassSymbol cls) implements TExpr {
    }

    // ==== Functions as values ====

    /**
     * A lambda or anonymous function.
     *
     * @param function its parameters, receiver and body
     */
    record Lambda(int pos, KType type, CheckedFunction function, boolean anonymousFunction) implements TExpr {
    }

    /**
     * {@code a::f}, {@code A::f} or {@code ::f}.
     *
     * @param boundReceiver the receiver bound into the reference, nullable
     */
    record CallableReference(int pos, KType type, CallableSymbol target, List<KType> typeArgs,
                             TExpr boundReceiver) implements TExpr {
    }

    /**
     * {@code A::class} or {@code a::class}.
     *
     * @param receiver for {@code a::class}, the expression; otherwise null
     */
    record ClassReference(int pos, KType type, KType classType, TExpr receiver) implements TExpr {
    }

    /** {@code object : A { ... }} */
    record ObjectLiteral(int pos, KType type, ClassSymbol cls) implements TExpr {
    }
}
