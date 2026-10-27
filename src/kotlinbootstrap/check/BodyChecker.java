package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.Stmt;
import kotlinbootstrap.ast.TypeRef;
import kotlinbootstrap.ast.ValueArg;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.syntax.SourceFile;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;

/**
 * Checks the expressions of one file, as FIR's body resolve transformers:
 * each expression is checked in a {@link Mode} that says what type is
 * expected of it and whether its calls are completed at once.
 */
final class BodyChecker {
    private final Checker owner;
    private final TypeSystem ts;
    private final SourceFile source;
    private final CallCompleter completer;
    private final CallResolver calls;

    BodyChecker(Checker owner, SourceFile source) {
        this.owner = owner;
        this.ts = owner.ts();
        this.source = source;
        this.completer = new CallCompleter(ts, this);
        this.calls = new CallResolver(owner, completer);
    }

    CheckException error(int pos, String message) {
        return new CheckException(source, pos, message);
    }

    SyntheticCalls synthetic() {
        return owner.synthetic();
    }

    /** Analyzes a lambda or callable reference during the completion of its call. */
    void analyzePostponed(ConstraintSystem system, PostponedArgument argument, Candidate candidate) {
        throw error(candidate.pos, "Lambdas and callable references are not supported yet");
    }

    // ==== Expressions ====

    TExpr expr(Expr e, Context ctx, Mode mode) {
        return switch (e) {
            case Expr.IntLit l -> integerLiteral(l, false, mode);
            case Expr.FloatLit l -> floatLiteral(l);
            case Expr.CharLit l -> new TExpr.Const(l.pos(), type("kotlin.Char"), l.value());
            case Expr.BoolLit l -> new TExpr.Const(l.pos(), bool(), l.value());
            case Expr.NullLit l -> new TExpr.Const(l.pos(), ts.nullableNothing(), null);
            case Expr.StringTemplate s -> stringTemplate(s, ctx);
            case Expr.Paren p -> expr(p.expr(), ctx, mode);
            case Expr.Annotated a -> expr(a.expr(), ctx, mode);
            case Expr.Name n -> name(n, ctx, mode);
            case Expr.Member m -> member(m, ctx, mode);
            case Expr.Call c -> call(c, ctx, mode);
            case Expr.InfixCall i -> operatorCall(i.pos(), i.name(), receiver(i.left(), ctx),
                    List.of(i.right()), TExpr.CallOrigin.INFIX, ctx, mode);
            case Expr.Index i -> operatorCall(i.pos(), "get", receiver(i.receiver(), ctx), i.indices(),
                    TExpr.CallOrigin.OPERATOR, ctx, mode);
            case Expr.Prefix p -> prefix(p, ctx, mode);
            case Expr.This t -> thisExpr(t, ctx);
            case Expr.Block b -> block(b.pos(), b.statements(), ctx, mode);
            case Expr.DeclExpr d -> declaration(d.decl(), ctx);
            case Expr.If i -> ifExpr(i, ctx, mode);
            case Expr.When w -> whenExpr(w, ctx, mode);
            case Expr.Try t -> tryExpr(t, ctx, mode);
            case Expr.Binary b -> binary(b, ctx, mode);
            case Expr.Postfix p when p.op() == Expr.PostfixOp.NOT_NULL -> checkNotNull(p, ctx, mode);
            case Expr.Is i -> {
                TExpr operand = expr(i.expr(), ctx, Mode.INDEPENDENT);
                yield new TExpr.TypeCheck(i.pos(), bool(), operand, typeOperand(i.type(), operand.type(), ctx),
                        i.negated());
            }
            case Expr.As a -> cast(a, ctx);
            case Expr.Assign a -> assign(a, ctx);
            case Expr.Return r -> returnExpr(r, ctx);
            case Expr.Throw t -> new TExpr.Throw(t.pos(), ts.nothing(),
                    expr(t.expr(), ctx, Mode.expecting(type("kotlin.Throwable"))));
            case Expr.Break b -> new TExpr.Break(b.pos(), ts.nothing(), loop(b.pos(), b.label(), ctx));
            case Expr.Continue c -> new TExpr.Continue(c.pos(), ts.nothing(), loop(c.pos(), c.label(), ctx));
            case Expr.While w -> whileLoop(w, null, ctx);
            case Expr.DoWhile w -> doWhileLoop(w, null, ctx);
            case Expr.For f -> forLoop(f, null, ctx);
            case Expr.Labeled l -> labeled(l, ctx, mode);
            default -> throw error(e.pos(), "Unsupported expression: " + e.getClass().getSimpleName());
        };
    }

    // ==== Literals ====

    /**
     * An integer literal: without a suffix, an integer literal type, which
     * becomes a definite type at once unless the literal is an argument.
     *
     * @param negate for {@code -1}, which is a literal rather than a call when without a suffix
     */
    private TExpr integerLiteral(Expr.IntLit l, boolean negate, Mode mode) {
        String text = l.text().replace("_", "");
        boolean unsignedLong = text.endsWith("ul") || text.endsWith("uL") || text.endsWith("Ul") || text.endsWith("UL");
        boolean unsigned = !unsignedLong && (text.endsWith("u") || text.endsWith("U"));
        boolean isLong = !unsignedLong && (text.endsWith("l") || text.endsWith("L"));
        String digits = text.substring(0, text.length() - (unsignedLong ? 2 : unsigned || isLong ? 1 : 0));
        int radix = 10;
        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            radix = 16;
            digits = digits.substring(2);
        } else if (digits.startsWith("0b") || digits.startsWith("0B")) {
            radix = 2;
            digits = digits.substring(2);
        }
        long value;
        try {
            value = unsigned || unsignedLong ? Long.parseUnsignedLong(digits, radix) : Long.parseLong(digits, radix);
        } catch (NumberFormatException ex) {
            throw error(l.pos(), "The value is out of range");
        }
        if (unsignedLong) return new TExpr.Const(l.pos(), type("kotlin.ULong"), value);
        if (isLong) return new TExpr.Const(l.pos(), type("kotlin.Long"), value);
        if (negate) value = -value;
        KType literalType = integerLiteralType(value, unsigned);
        if (literalType instanceof KType.IntegerLiteral ilt) {
            literalType = switch (mode) {
                // TODO: integer literal operator types for receivers, as in 1.plus(2)
                case Mode.WithExpectedType w -> TypeApproximator.integerLiteralType(ilt, w.type());
                case Mode.ContextIndependent m -> TypeApproximator.integerLiteralType(ilt, null);
                case Mode.Receiver m -> TypeApproximator.integerLiteralType(ilt, null);
                case Mode.ContextDependent m -> ilt;
            };
        }
        return new TExpr.Const(l.pos(), literalType, value);
    }

    /** As ConeIntegerLiteralConstantTypeImpl.create: the types the value fits, or the one if only one. */
    private KType integerLiteralType(long value, boolean unsigned) {
        List<KType.ClassType> possible = new ArrayList<>();
        if (unsigned) {
            if (value >= 0 && value <= 0xFFFF_FFFFL) possible.add(type("kotlin.UInt"));
            possible.add(type("kotlin.ULong"));
            if (value >= 0 && value <= 0xFF) possible.add(type("kotlin.UByte"));
            if (value >= 0 && value <= 0xFFFF) possible.add(type("kotlin.UShort"));
        } else {
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) possible.add(type("kotlin.Int"));
            possible.add(type("kotlin.Long"));
            if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) possible.add(type("kotlin.Byte"));
            if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) possible.add(type("kotlin.Short"));
        }
        if (possible.size() == 1) return possible.getFirst();
        return new KType.IntegerLiteral(value, List.copyOf(possible), unsigned, false);
    }

    private TExpr floatLiteral(Expr.FloatLit l) {
        String text = l.text().replace("_", "");
        boolean isFloat = text.endsWith("f") || text.endsWith("F");
        try {
            if (isFloat) {
                return new TExpr.Const(l.pos(), type("kotlin.Float"), (double) Float.parseFloat(text));
            }
            return new TExpr.Const(l.pos(), type("kotlin.Double"), Double.parseDouble(text));
        } catch (NumberFormatException ex) {
            throw error(l.pos(), "Invalid floating-point literal");
        }
    }

    private TExpr stringTemplate(Expr.StringTemplate s, Context ctx) {
        KType string = type("kotlin.String");
        List<TExpr> parts = new ArrayList<>();
        for (Expr.StringPart part : s.parts()) {
            parts.add(switch (part) {
                case Expr.TextPart t -> new TExpr.Const(s.pos(), string, t.text());
                case Expr.ExprPart p -> expr(p.expr(), ctx, Mode.INDEPENDENT);
            });
        }
        if (parts.size() == 1 && parts.getFirst() instanceof TExpr.Const c && c.value() instanceof String) return c;
        return new TExpr.StringTemplate(s.pos(), string, parts);
    }

    // ==== Names ====

    private TExpr name(Expr.Name n, Context ctx, Mode mode) {
        LocalVariable v = ctx.findLocal(n.name());
        if (v != null) return new TExpr.LocalGet(n.pos(), v.type(), v);
        return completer.completeCall(resolved(variable(n.pos(), n.name(), null), ctx), mode);
    }

    private TExpr thisExpr(Expr.This t, Context ctx) {
        for (Context.ImplicitReceiver r : ctx.receivers()) {
            if (t.label() == null || t.label().equals(r.label())) return new TExpr.This(t.pos(), r.type(), r.owner(), false);
        }
        throw error(t.pos(), t.label() == null ? "'this' is not defined in this context"
                : "Unresolved label " + t.label());
    }

    // ==== Calls ====

    private static CallInfo variable(int pos, String name, CallInfo.Receiver receiver) {
        return new CallInfo(CallInfo.Kind.VARIABLE, name, receiver, List.of(), List.of(), TExpr.CallOrigin.PLAIN,
                false, pos);
    }

    private static CallInfo function(int pos, String name, CallInfo.Receiver receiver, List<CallInfo.Argument> args,
                                     List<TypeArg> typeArgs, TExpr.CallOrigin origin) {
        return new CallInfo(CallInfo.Kind.FUNCTION, name, receiver, args, typeArgs, origin, false, pos);
    }

    /** The candidate a call resolves to, or an error if none or more than one. */
    private Candidate resolved(CallInfo info, Context ctx) {
        return resolved(info, calls.resolve(info, ctx));
    }

    private Candidate resolved(CallInfo info, CallResolver.Result result) {
        List<Candidate> candidates = result.candidates();
        if (result.success() && candidates.size() == 1) return candidates.getFirst();
        if (candidates.isEmpty()) throw error(info.pos(), "Unresolved reference " + info.name());
        if (result.success()) throw error(info.pos(), "Ambiguous call of " + info.name() + ": " + candidates);
        throw error(info.pos(), "No applicable " + info.name() + ": " + candidates);
    }

    /** {@code a.b} or {@code a?.b} as a value. */
    private TExpr member(Expr.Member m, Context ctx, Mode mode) {
        return onReceiver(m.pos(), receiver(m.receiver(), ctx), m.safe(),
                r -> completer.completeCall(resolved(variable(m.pos(), m.name(), r), ctx), mode));
    }

    private TExpr call(Expr.Call c, Context ctx, Mode mode) {
        List<TypeArg> typeArgs = typeArguments(c.typeArgs(), ctx);
        return switch (c.callee()) {
            case Expr.Name n -> completer.completeCall(resolved(function(c.pos(), n.name(), null,
                    arguments(c.args(), c.lambdas(), ctx), typeArgs, TExpr.CallOrigin.PLAIN), ctx), mode);
            case Expr.Member m -> {
                CallInfo.Receiver receiver = receiver(m.receiver(), ctx);
                List<CallInfo.Argument> args = arguments(c.args(), c.lambdas(), ctx);
                yield onReceiver(m.pos(), receiver, m.safe(), r -> completer.completeCall(
                        resolved(function(c.pos(), m.name(), r, args, typeArgs, TExpr.CallOrigin.PLAIN), ctx), mode));
            }
            default -> {
                // (f)(x): the invoke of a value.
                TExpr callee = expr(c.callee(), ctx, Mode.RECEIVER);
                CallInfo info = new CallInfo(CallInfo.Kind.FUNCTION, "invoke", new CallInfo.ValueReceiver(callee),
                        arguments(c.args(), c.lambdas(), ctx), typeArgs, TExpr.CallOrigin.PLAIN, true, c.pos());
                yield completer.completeCall(resolved(info, ctx), mode);
            }
        };
    }

    /** The arguments of a call, checked without completing their calls. */
    private List<CallInfo.Argument> arguments(List<ValueArg> args, List<Expr> lambdas, Context ctx) {
        List<CallInfo.Argument> result = new ArrayList<>();
        for (ValueArg a : args) {
            result.add(new CallInfo.Argument(expr(a.value(), ctx, Mode.DEPENDENT), a.name(), a.spread(), false));
        }
        for (Expr l : lambdas) result.add(new CallInfo.Argument(expr(l, ctx, Mode.DEPENDENT), null, false, true));
        return result;
    }

    /** Explicit type arguments; {@code _} is null, to be inferred. */
    private List<TypeArg> typeArguments(List<TypeRef.TypeArg> args, Context ctx) {
        List<TypeArg> result = new ArrayList<>();
        for (TypeRef.TypeArg a : args) {
            boolean underscore = a.type() instanceof TypeRef.Simple t && t.segments().size() == 1
                    && t.segments().getFirst().name().equals("_") && t.segments().getFirst().args().isEmpty();
            if (a.type() == null) throw error(a.pos(), "Projections are not allowed as type arguments of a call");
            result.add(underscore ? null : TypeArg.of(resolveType(a.type(), ctx)));
        }
        return result;
    }

    /**
     * A call on a receiver; for a safe call, on the receiver's value in a
     * variable, which the selector sees as not null.
     */
    private TExpr onReceiver(int pos, CallInfo.Receiver receiver, boolean safe,
                             Function<CallInfo.Receiver, TExpr> selector) {
        if (!safe) return selector.apply(receiver);
        if (!(receiver instanceof CallInfo.ValueReceiver v)) throw error(pos, "A safe call needs a value as receiver");
        LocalVariable subject = temporary("<safe>", Types.makeDefinitelyNotNull(v.value().type()));
        TExpr result = selector.apply(new CallInfo.ValueReceiver(new TExpr.LocalGet(pos, subject.type(), subject)));
        KType type = TExprs.isStatement(result) ? result.type() : Types.makeNullable(result.type());
        return new TExpr.SafeCall(pos, type, v.value(), subject, result);
    }

    /** The receiver of a member access or call: a value, {@code super}, or a class or package as a qualifier. */
    private CallInfo.Receiver receiver(Expr e, Context ctx) {
        return switch (e) {
            case Expr.Super s -> superReceiver(s, ctx);
            case Expr.Name n when ctx.findLocal(n.name()) == null -> qualifiedName(n.pos(), n.name(), null, ctx);
            case Expr.Member m when !m.safe() -> qualifiedName(m.pos(), m.name(), receiver(m.receiver(), ctx), ctx);
            default -> new CallInfo.ValueReceiver(expr(e, ctx, Mode.RECEIVER));
        };
    }

    /**
     * A name as a receiver: a value, or a class (with its object or
     * companion object, if any) or package, as FIR's resolved qualifiers.
     */
    private CallInfo.Receiver qualifiedName(int pos, String name, CallInfo.Receiver receiver, Context ctx) {
        CallInfo info = variable(pos, name, receiver);
        CallResolver.Result result = calls.resolve(info, ctx);
        Applicability best = Applicability.HIDDEN;
        for (Candidate c : result.candidates()) {
            if (c.applicability().compareTo(best) > 0) best = c.applicability();
        }
        for (Candidate c : result.candidates()) {
            if (c.symbol instanceof ClassifierSymbol classifier && c.applicability() == best
                    && (result.success() || best == Applicability.K2_NO_COMPANION_OBJECT)) {
                TExpr value = result.success() ? completer.completeCall(c, Mode.RECEIVER) : null;
                return new CallInfo.Qualifier(pos, null, classifier, List.of(), value);
            }
        }
        if (!result.success()) {
            String pkg = switch (receiver) {
                case null -> name;
                case CallInfo.Qualifier q when q.classifier() == null -> q.packageName() + "." + name;
                default -> null;
            };
            if (pkg != null && ts.table().packageExists(pkg)) {
                return new CallInfo.Qualifier(pos, pkg, null, List.of(), null);
            }
        }
        return new CallInfo.ValueReceiver(completer.completeCall(resolved(info, result), Mode.RECEIVER));
    }

    private CallInfo.Receiver superReceiver(Expr.Super s, Context ctx) {
        for (Context.ImplicitReceiver r : ctx.receivers()) {
            if (!(r.owner() instanceof ClassSymbol cls) || s.label() != null && !s.label().equals(r.label())) continue;
            List<KType> supertypes = cls.supertypes();
            KType supertype;
            if (s.qualifier() != null) {
                KType written = resolveType(s.qualifier(), ctx);
                supertype = supertypes.stream()
                        .filter(t -> t instanceof KType.ClassType c && written instanceof KType.ClassType w
                                && c.symbol() == w.symbol())
                        .findFirst().orElseThrow(() -> error(s.pos(), written + " is not a supertype"));
            } else {
                supertype = supertypes.size() == 1 ? supertypes.getFirst() : ts.intersect(supertypes);
            }
            return new CallInfo.SuperReceiver(new TExpr.This(s.pos(), r.type(), cls, false), supertype);
        }
        throw error(s.pos(), "'super' is not allowed here");
    }

    // ==== Blocks and declarations ====

    /**
     * A block in a new scope. The last statement gets the block's mode, the
     * others are on their own. With Unit expected, the block has type Unit
     * whatever its last statement.
     */
    TExpr block(int pos, List<Stmt> statements, Context ctx, Mode mode) {
        Context inner = ctx.withLocalScope(new LocalScope());
        return blockInScope(pos, statements, inner, mode);
    }

    private TExpr blockInScope(int pos, List<Stmt> statements, Context ctx, Mode mode) {
        List<TExpr> result = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            Mode m = Mode.INDEPENDENT;
            if (i == statements.size() - 1) {
                m = mode instanceof Mode.WithExpectedType w
                        ? new Mode.WithExpectedType(w.type(), true, w.fromCast(), w.forceFullCompletion()) : mode;
            }
            if (statements.get(i) instanceof Decl.Destructuring d) {
                destructuringDeclaration(d, ctx, result);
            } else {
                result.add(statement(statements.get(i), ctx, m));
            }
        }
        TExpr.Block block = new TExpr.Block(pos, unit(), result);
        KType type;
        if (mode instanceof Mode.WithExpectedType w && isUnitOrFlexibleUnit(w.type())) {
            type = w.type();
        } else {
            TExpr last = TExprs.lastExpression(block);
            type = last == null ? unit() : last.type();
        }
        return new TExpr.Block(pos, type, result);
    }

    /** The body of a branch or loop, which is a block even if written as a single expression. */
    private TExpr branch(int pos, Expr body, Context ctx, Mode mode) {
        if (body == null) return block(pos, List.of(), ctx, mode);
        if (body instanceof Expr.Block b) return block(b.pos(), b.statements(), ctx, mode);
        return block(body.pos(), List.of(body), ctx, mode);
    }

    private TExpr statement(Stmt s, Context ctx, Mode mode) {
        return switch (s) {
            case Decl d -> declaration(d, ctx);
            case Expr e -> expr(e, ctx, mode);
        };
    }

    /** A local declaration, added to the innermost local scope. */
    private TExpr declaration(Decl d, Context ctx) {
        return switch (d) {
            case Decl.Property p -> localVariable(p, ctx);
            default -> throw error(d.pos(), "Unsupported local declaration: " + d.getClass().getSimpleName());
        };
    }

    private TExpr localVariable(Decl.Property p, Context ctx) {
        if (p.delegate() != null) throw error(p.pos(), "Local delegated properties are not supported yet");
        KType declared = p.type() == null ? null : resolveType(p.type(), ctx);
        TExpr initializer = null;
        if (p.initializer() != null) {
            initializer = expr(p.initializer(), ctx, declared != null ? Mode.expecting(declared) : Mode.INDEPENDENT);
        }
        KType type = declared;
        if (type == null) {
            if (initializer == null) throw error(p.pos(), "A local variable needs a type or an initializer");
            type = approximateLocalType(initializer.type());
        }
        LocalVariable v = new LocalVariable(p.name(), type, LocalVariable.Kind.LOCAL, p.isVar(), null);
        ctx.locals().getLast().add(v);
        return new TExpr.LocalVarDecl(p.pos(), unit(), v, initializer);
    }

    /** {@code val (a, b) = x}: a temporary for {@code x}, then the entries. */
    private void destructuringDeclaration(Decl.Destructuring d, Context ctx, List<TExpr> out) {
        if (d.initializer() == null) throw error(d.pos(), "A destructuring declaration needs an initializer");
        TExpr initializer = expr(d.initializer(), ctx, Mode.INDEPENDENT);
        LocalVariable source = temporary("<destruct>", approximateLocalType(initializer.type()));
        out.add(new TExpr.LocalVarDecl(d.pos(), unit(), source, initializer));
        destructure(d, source, ctx, out);
    }

    /**
     * The entries of a destructuring of a variable: {@code val a = x.component1()}
     * and so on, skipping {@code _}; for the full form {@code (val a, val b)},
     * which destructures by name, {@code val a = x.a}.
     */
    private void destructure(Decl.Destructuring d, LocalVariable source, Context ctx, List<TExpr> out) {
        boolean byName = !d.squareBrackets() && d.valOrVar() == Decl.ValOrVar.NONE
                && d.entries().stream().allMatch(e -> e.valOrVar() != Decl.ValOrVar.NONE);
        for (int i = 0; i < d.entries().size(); i++) {
            Decl.DestructuringEntry e = d.entries().get(i);
            if (e.name().equals("_") && (!byName || e.sourceName() == null)) continue;
            KType declared = e.type() == null ? null : resolveType(e.type(), ctx);
            Mode mode = declared != null ? Mode.expecting(declared) : Mode.INDEPENDENT;
            CallInfo.Receiver r = localValue(e.pos(), source);
            TExpr value = byName
                    ? completer.completeCall(resolved(variable(e.pos(), e.sourceName() != null ? e.sourceName() : e.name(),
                            r), ctx), mode)
                    : desugaredCall(e.pos(), "component" + (i + 1), r, ctx, mode);
            boolean isVar = (byName ? e.valOrVar() : d.valOrVar()) == Decl.ValOrVar.VAR;
            LocalVariable v = new LocalVariable(e.name(), declared != null ? declared : approximateLocalType(value.type()),
                    LocalVariable.Kind.LOCAL, isVar, null);
            if (!e.name().equals("_")) ctx.locals().getLast().add(v);
            out.add(new TExpr.LocalVarDecl(e.pos(), unit(), v, value));
        }
    }

    private static LocalVariable temporary(String name, KType type) {
        return new LocalVariable(name, type, LocalVariable.Kind.TEMPORARY, false, null);
    }

    private static CallInfo.Receiver localValue(int pos, LocalVariable v) {
        return new CallInfo.ValueReceiver(new TExpr.LocalGet(pos, v.type(), v));
    }

    /** A call of an operator convention without arguments, such as {@code iterator()} or {@code component1()}. */
    private TExpr desugaredCall(int pos, String name, CallInfo.Receiver receiver, Context ctx, Mode mode) {
        return completer.completeCall(resolved(function(pos, name, receiver, List.of(), List.of(),
                TExpr.CallOrigin.DESUGARED), ctx), mode);
    }

    KType approximateLocalType(KType type) {
        return TypeApproximator.approximateToSuperTypeOrSelf(ts, type, TypeApproximator.Config.LOCAL_DECLARATION);
    }

    private TExpr assign(Expr.Assign a, Context ctx) {
        if (a.op() != Expr.AssignOp.ASSIGN) throw error(a.pos(), "Compound assignments are not supported yet");
        if (a.target() instanceof Expr.Name n) {
            LocalVariable v = ctx.findLocal(n.name());
            if (v != null) {
                TExpr value = expr(a.value(), ctx, Mode.expecting(v.type()));
                return new TExpr.LocalSet(a.pos(), unit(), v, value);
            }
        }
        throw error(a.pos(), "Unsupported assignment target");
    }

    // ==== When and if ====

    private TExpr ifExpr(Expr.If i, Context ctx, Mode mode) {
        boolean exhaustive = i.otherwise() != null;
        Mode branchMode = branchMode(exhaustive ? 2 : 1, mode);
        TExpr condition = expr(i.condition(), ctx, Mode.expecting(bool()));
        TExpr then = branch(i.pos(), i.then(), ctx, branchMode);
        TExpr otherwise = exhaustive ? branch(i.pos(), i.otherwise(), ctx, branchMode) : null;
        if (branchMode == Mode.INDEPENDENT) {
            return new TExpr.If(i.pos(), exhaustive ? then.type() : unit(), condition, then, otherwise);
        }
        List<TExpr> bodies = exhaustive ? List.of(then, otherwise) : List.of(then);
        Candidate candidate = synthetic().whenCall(i.pos(), bodies, null);
        candidate.builder = w -> new TExpr.If(i.pos(), exhaustive ? w.callType(candidate, candidate.returnType()) : unit(),
                condition, w.writeArgument(then, SyntheticCalls.parameterType(candidate, 0)),
                otherwise == null ? null : w.writeArgument(otherwise, SyntheticCalls.parameterType(candidate, 1)));
        return completer.completeCall(candidate, exhaustive ? mode : Mode.INDEPENDENT);
    }

    /**
     * The mode for the branches of a when: a when with one branch, checked
     * on its own, has its branch checked on its own; otherwise the branches
     * are arguments of the when's synthetic call.
     */
    private static Mode branchMode(int branches, Mode mode) {
        if (branches == 1 && mode.forceFullCompletion() && !(mode instanceof Mode.WithExpectedType)) {
            return Mode.INDEPENDENT;
        }
        if (mode instanceof Mode.WithExpectedType w && !w.fromCast()) {
            return new Mode.WithExpectedType(w.type(), w.lastStatementInBlock(), false, false);
        }
        return Mode.DEPENDENT;
    }

    private TExpr whenExpr(Expr.When w, Context ctx, Mode mode) {
        Context inner = ctx.withLocalScope(new LocalScope());
        TExpr subject = null;
        LocalVariable subjectVariable = null;
        if (w.subjectVariable() != null) {
            subject = localVariable(w.subjectVariable(), inner);
            subjectVariable = ((TExpr.LocalVarDecl) subject).variable();
        } else if (w.subject() != null) {
            TExpr value = expr(w.subject(), inner, Mode.INDEPENDENT);
            subjectVariable = new LocalVariable("<subject>", value.type(), LocalVariable.Kind.TEMPORARY, false, null);
            subject = new TExpr.LocalVarDecl(w.subject().pos(), unit(), subjectVariable, value);
        }
        if (w.entries().isEmpty()) return new TExpr.When(w.pos(), unit(), subject, List.of(), false);

        Mode branchMode = branchMode(w.entries().size(), mode);
        List<TExpr> conditions = new ArrayList<>();
        List<TExpr> bodies = new ArrayList<>();
        for (Expr.WhenEntry entry : w.entries()) {
            conditions.add(whenCondition(entry, subjectVariable, inner));
            bodies.add(branch(entry.pos(), entry.body(), inner, branchMode));
        }
        boolean exhaustive = isExhaustive(w, subjectVariable, conditions);
        TExpr finalSubject = subject;
        if (branchMode == Mode.INDEPENDENT) {
            return new TExpr.When(w.pos(), exhaustive ? bodies.getFirst().type() : unit(), subject,
                    List.of(new TExpr.WhenBranch(conditions.getFirst(), bodies.getFirst())), exhaustive);
        }
        Candidate candidate = synthetic().whenCall(w.pos(), bodies, null);
        candidate.builder = wr -> {
            List<TExpr.WhenBranch> branches = new ArrayList<>();
            for (int i = 0; i < bodies.size(); i++) {
                branches.add(new TExpr.WhenBranch(conditions.get(i),
                        wr.writeArgument(bodies.get(i), SyntheticCalls.parameterType(candidate, i))));
            }
            KType type = exhaustive ? wr.callType(candidate, candidate.returnType()) : unit();
            return new TExpr.When(w.pos(), type, finalSubject, branches, exhaustive);
        };
        return completer.completeCall(candidate, exhaustive ? mode : Mode.INDEPENDENT);
    }

    /** The condition of a when entry as a Boolean expression; null for else. */
    private TExpr whenCondition(Expr.WhenEntry entry, LocalVariable subject, Context ctx) {
        TExpr condition = null;
        for (Expr.WhenCondition c : entry.conditions()) {
            TExpr test = switch (c) {
                case Expr.ExprCondition e when subject == null -> expr(e.expr(), ctx, Mode.expecting(bool()));
                case Expr.ExprCondition e -> equality(e.pos(), Expr.BinaryOp.EQ,
                        new TExpr.LocalGet(e.pos(), subject.type(), subject), e.expr(), ctx, Mode.INDEPENDENT);
                case Expr.IsCondition i when subject != null -> new TExpr.TypeCheck(i.pos(), bool(),
                        new TExpr.LocalGet(i.pos(), subject.type(), subject), typeOperand(i.type(), subject.type(), ctx),
                        i.negated());
                case Expr.InCondition i when subject != null ->
                        throw error(i.pos(), "'in' conditions are not supported yet");
                default -> throw error(c.pos(), "A when without a subject needs Boolean conditions");
            };
            condition = condition == null ? test : new TExpr.Logical(c.pos(), bool(), false, condition, test);
        }
        if (entry.guard() != null) {
            TExpr guard = expr(entry.guard(), ctx, Mode.expecting(bool()));
            condition = condition == null ? guard : new TExpr.Logical(entry.guard().pos(), bool(), true, condition, guard);
        }
        return condition;
    }

    // ==== Exhaustiveness ====

    /** What the conditions of a when match of its subject. */
    private static final class Coverage {
        boolean isNull;
        final Set<Boolean> booleans = new HashSet<>();
        /** Enum entries and objects compared with. */
        final Set<Object> values = new HashSet<>();
        /** Classes tested with is. */
        final List<ClassSymbol> classes = new ArrayList<>();
    }

    /**
     * Whether some branch is always taken, as FIR's
     * FirWhenExhaustivenessTransformer: there is an else, or the conditions
     * without guards match every value of the subject's type.
     */
    private boolean isExhaustive(Expr.When w, LocalVariable subject, List<TExpr> conditions) {
        for (Expr.WhenEntry entry : w.entries()) {
            if (entry.conditions().isEmpty() && entry.guard() == null) return true;
        }
        if (subject == null) return false;
        Coverage coverage = new Coverage();
        for (int i = 0; i < conditions.size(); i++) {
            if (w.entries().get(i).guard() == null) collect(conditions.get(i), subject, coverage);
        }
        return covers(subject.type(), coverage);
    }

    private static void collect(TExpr condition, LocalVariable subject, Coverage coverage) {
        switch (condition) {
            case TExpr.Logical l when !l.and() -> {
                collect(l.left(), subject, coverage);
                collect(l.right(), subject, coverage);
            }
            case TExpr.Equality e when (e.op() == TExpr.EqualityOp.EQ || e.op() == TExpr.EqualityOp.IDENTITY_EQ)
                    && e.left() instanceof TExpr.LocalGet g && g.variable() == subject -> {
                switch (e.right()) {
                    case TExpr.Const c when c.value() == null -> coverage.isNull = true;
                    case TExpr.Const c when c.value() instanceof Boolean b -> coverage.booleans.add(b);
                    case TExpr.PropertyGet p when p.property().isEnumEntry() -> coverage.values.add(p.property());
                    case TExpr.ObjectValue o -> coverage.values.add(o.object());
                    default -> {
                    }
                }
            }
            case TExpr.TypeCheck t when !t.negated() && t.expr() instanceof TExpr.LocalGet g
                    && g.variable() == subject -> {
                if (Types.isMarkedNullable(t.target())) coverage.isNull = true;
                if (Types.lowerBound(t.target()) instanceof KType.ClassType c) coverage.classes.add(c.symbol());
            }
            default -> {
            }
        }
    }

    private boolean covers(KType type, Coverage coverage) {
        return switch (type) {
            case KType.Flexible f -> covers(Types.makeDefinitelyNotNull(f.lower()), coverage);
            case KType.DefinitelyNotNull d -> covers(d.inner().withNullable(false), coverage);
            case KType.ClassType c -> (!c.nullable() || coverage.isNull) && covers(c.symbol(), coverage);
            case KType.Intersection i -> i.components().stream().anyMatch(t -> covers(t, coverage));
            case KType.TypeParamType t -> (!t.nullable() || coverage.isNull)
                    && t.symbol().bounds().stream().anyMatch(b -> covers(Types.makeDefinitelyNotNull(b), coverage));
            default -> false;
        };
    }

    private boolean covers(ClassSymbol cls, Coverage coverage) {
        for (ClassSymbol tested : coverage.classes) {
            if (VisibilityChecker.isSubclassOf(cls, tested)) return true;
        }
        if (cls.id().fqName().equals("kotlin.Boolean")) return coverage.booleans.size() == 2;
        if (cls.isObject()) return coverage.values.contains(cls);
        if (cls.kind() == ClassKind.ENUM_CLASS) {
            return cls.members().properties().values().stream().flatMap(List::stream)
                    .filter(PropertySymbol::isEnumEntry).allMatch(coverage.values::contains);
        }
        if (cls.modality() == Modality.SEALED) {
            return owner.sealedInheritors(cls).stream().allMatch(sub -> covers(sub, coverage));
        }
        return false;
    }

    private static Expr unparenthesized(Expr e) {
        while (e instanceof Expr.Paren p) e = p.expr();
        return e;
    }

    // ==== Try ====

    private TExpr tryExpr(Expr.Try t, Context ctx, Mode mode) {
        List<TExpr> bodies = new ArrayList<>();
        bodies.add(block(t.body().pos(), t.body().statements(), ctx, Mode.DEPENDENT));
        List<LocalVariable> params = new ArrayList<>();
        for (Expr.Catch c : t.catches()) {
            KType type = resolveType(c.param().type(), ctx);
            LocalVariable param = new LocalVariable(c.param().name(), type, LocalVariable.Kind.CATCH_PARAMETER, false, null);
            LocalScope scope = new LocalScope();
            scope.add(param);
            params.add(param);
            bodies.add(block(c.body().pos(), c.body().statements(), ctx.withLocalScope(scope), Mode.DEPENDENT));
        }
        TExpr[] finallyBlock = new TExpr[1];
        Candidate candidate = synthetic().tryCall(t.pos(), bodies, null);
        candidate.builder = w -> {
            List<TExpr.CatchClause> catches = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                catches.add(new TExpr.CatchClause(params.get(i),
                        w.writeArgument(bodies.get(i + 1), SyntheticCalls.parameterType(candidate, i + 1))));
            }
            return new TExpr.Try(t.pos(), w.callType(candidate, candidate.returnType()),
                    w.writeArgument(bodies.getFirst(), SyntheticCalls.parameterType(candidate, 0)), catches,
                    finallyBlock[0]);
        };
        TExpr result = completer.completeCall(candidate, mode);
        if (t.finallyBlock() != null) {
            finallyBlock[0] = block(t.finallyBlock().pos(), t.finallyBlock().statements(), ctx, Mode.INDEPENDENT);
            // The builder ran already if the call was completed.
            if (result instanceof TExpr.Try r) {
                result = new TExpr.Try(r.pos(), r.type(), r.body(), r.catches(), finallyBlock[0]);
            }
        }
        return result;
    }

    // ==== Operators ====

    private TExpr binary(Expr.Binary b, Context ctx, Mode mode) {
        return switch (b.op()) {
            case ELVIS -> elvis(b, ctx, mode);
            case AND, OR -> new TExpr.Logical(b.pos(), bool(), b.op() == Expr.BinaryOp.AND,
                    expr(b.left(), ctx, Mode.expecting(bool())), expr(b.right(), ctx, Mode.expecting(bool())));
            case EQ, NE, IDENTITY_EQ, IDENTITY_NE ->
                    equality(b.pos(), b.op(), expr(b.left(), ctx, Mode.INDEPENDENT), b.right(), ctx, mode);
            case MUL, DIV, REM, PLUS, MINUS, RANGE, RANGE_UNTIL -> operatorCall(b.pos(), operatorName(b.op()),
                    receiver(b.left(), ctx), List.of(b.right()), TExpr.CallOrigin.OPERATOR, ctx, mode);
            case IN -> operatorCall(b.pos(), "contains", receiver(b.right(), ctx), List.of(b.left()),
                    TExpr.CallOrigin.OPERATOR, ctx, mode);
            // a !in b is !(b.contains(a)).
            case NOT_IN -> operatorCall(b.pos(), "not", new CallInfo.ValueReceiver(operatorCall(b.pos(), "contains",
                    receiver(b.right(), ctx), List.of(b.left()), TExpr.CallOrigin.OPERATOR, ctx, Mode.RECEIVER)),
                    List.of(), TExpr.CallOrigin.OPERATOR, ctx, mode);
            case LT, GT, LE, GE -> new TExpr.Comparison(b.pos(), bool(), comparisonOp(b.op()),
                    operatorCall(b.pos(), "compareTo", receiver(b.left(), ctx), List.of(b.right()),
                            TExpr.CallOrigin.OPERATOR, ctx, Mode.INDEPENDENT));
            default -> throw error(b.pos(), "Operator " + b.op() + " is not supported yet");
        };
    }

    private static String operatorName(Expr.BinaryOp op) {
        return switch (op) {
            case MUL -> "times";
            case DIV -> "div";
            case REM -> "rem";
            case PLUS -> "plus";
            case MINUS -> "minus";
            case RANGE -> "rangeTo";
            case RANGE_UNTIL -> "rangeUntil";
            default -> throw new IllegalArgumentException(op.toString());
        };
    }

    private static TExpr.ComparisonOp comparisonOp(Expr.BinaryOp op) {
        return switch (op) {
            case LT -> TExpr.ComparisonOp.LT;
            case GT -> TExpr.ComparisonOp.GT;
            case LE -> TExpr.ComparisonOp.LE;
            default -> TExpr.ComparisonOp.GE;
        };
    }

    /** A call of an operator or infix function: {@code a + b} is {@code a.plus(b)}. */
    private TExpr operatorCall(int pos, String name, CallInfo.Receiver receiver, List<Expr> args,
                               TExpr.CallOrigin origin, Context ctx, Mode mode) {
        List<CallInfo.Argument> arguments = new ArrayList<>();
        for (Expr a : args) arguments.add(new CallInfo.Argument(expr(a, ctx, Mode.DEPENDENT), null, false, false));
        return completer.completeCall(resolved(function(pos, name, receiver, arguments, List.of(), origin), ctx), mode);
    }

    private TExpr prefix(Expr.Prefix p, Context ctx, Mode mode) {
        String name = switch (p.op()) {
            case MINUS -> "unaryMinus";
            case PLUS -> "unaryPlus";
            case NOT -> "not";
            default -> throw error(p.pos(), "Operator " + p.op() + " is not supported yet");
        };
        if ((p.op() == Expr.PrefixOp.MINUS || p.op() == Expr.PrefixOp.PLUS)
                && unparenthesized(p.expr()) instanceof Expr.IntLit l && isUnsuffixed(l)) {
            return integerLiteral(l, p.op() == Expr.PrefixOp.MINUS, mode);
        }
        return operatorCall(p.pos(), name, receiver(p.expr(), ctx), List.of(), TExpr.CallOrigin.OPERATOR, ctx, mode);
    }

    private static boolean isUnsuffixed(Expr.IntLit l) {
        return !l.text().matches(".*[lLuU]$");
    }

    /**
     * {@code ==} and the like, with the left side checked already. The right
     * side is checked as an argument, and completed through a synthetic call
     * if its type is not known yet.
     */
    private TExpr equality(int pos, Expr.BinaryOp op, TExpr left, Expr rightExpr, Context ctx, Mode mode) {
        TExpr.EqualityOp equalityOp = switch (op) {
            case EQ -> TExpr.EqualityOp.EQ;
            case NE -> TExpr.EqualityOp.NE;
            case IDENTITY_EQ -> TExpr.EqualityOp.IDENTITY_EQ;
            default -> TExpr.EqualityOp.IDENTITY_NE;
        };
        TExpr right = expr(rightExpr, ctx, Mode.DEPENDENT);
        if (!Types.contains(right.type(), t -> t instanceof KType.TypeVariableType || t instanceof KType.IntegerLiteral)) {
            return new TExpr.Equality(pos, bool(), equalityOp, left, right);
        }
        Candidate candidate = synthetic().equalityCall(pos, left, right, null);
        candidate.builder = w -> new TExpr.Equality(pos, bool(), equalityOp, w.writeArgument(left, ts.nullableAny()),
                w.writeArgument(right, ts.nullableAny()));
        return completer.completeCall(candidate, mode);
    }

    private TExpr elvis(Expr.Binary b, Context ctx, Mode mode) {
        // The expected type of a when branch is not used: K1 did not.
        Mode data = mode instanceof Mode.WithExpectedType w && !w.forceFullCompletion() ? Mode.DEPENDENT : mode;
        KType expected = data instanceof Mode.WithExpectedType w ? w.type() : null;
        boolean last = data instanceof Mode.WithExpectedType w && w.lastStatementInBlock();
        Mode lhsMode = last && isUnitOrFlexibleUnit(expected) || expected == null ? Mode.DEPENDENT
                : Mode.expecting(expected.withNullable(true));
        TExpr lhs = expr(b.left(), ctx, lhsMode);
        Mode rhsMode = expected == null ? Mode.DEPENDENT : new Mode.WithExpectedType(expected, last, false, true);
        TExpr rhs = expr(b.right(), ctx, rhsMode);

        Candidate candidate = synthetic().elvisCall(b.pos(), lhs, rhs, null);
        candidate.builder = w -> new TExpr.Elvis(b.pos(), w.callType(candidate, candidate.returnType()),
                w.writeArgument(lhs, SyntheticCalls.parameterType(candidate, 0)),
                w.writeArgument(rhs, SyntheticCalls.parameterType(candidate, 1)));
        TExpr result = completer.completeCall(candidate, data);

        // As kotlinc, which adjusts the type of the call after completion.
        KType lhsType = result instanceof TExpr.Elvis e ? e.left().type() : lhs.type();
        KType rhsType = result instanceof TExpr.Elvis e ? e.right().type() : rhs.type();
        KType type = result.type();
        if (ts.isNothing(rhsType)) type = Types.makeDefinitelyNotNull(lhsType);
        if (!(type instanceof KType.TypeVariableType) && Types.canBeNull(type)) {
            if (!Types.canBeNull(rhsType)) {
                type = Types.makeDefinitelyNotNull(type);
            } else if (rhsType instanceof KType.Flexible f && !Types.canBeNull(f.lower())) {
                type = withNotNullableLowerBound(type);
            }
        }
        return switch (result) {
            case TExpr.Elvis e -> new TExpr.Elvis(e.pos(), type, e.left(), e.right());
            case TExpr.Unresolved u -> new TExpr.Unresolved(u.pos(), type, u.state());
            default -> result;
        };
    }

    private static KType withNotNullableLowerBound(KType type) {
        return switch (type) {
            case KType.Flexible f -> Types.canBeNull(f.lower())
                    ? Types.flexible(Types.makeDefinitelyNotNull(f.lower()), f.upper()) : f;
            case KType.Intersection i -> new KType.Intersection(
                    i.components().stream().map(BodyChecker::withNotNullableLowerBound).toList());
            default -> Types.flexible(Types.makeDefinitelyNotNull(type), type);
        };
    }

    private TExpr checkNotNull(Expr.Postfix p, Context ctx, Mode mode) {
        TExpr arg = expr(p.expr(), ctx, Mode.DEPENDENT);
        Candidate candidate = synthetic().checkNotNullCall(p.pos(), arg, null);
        candidate.builder = w -> new TExpr.NotNull(p.pos(), w.callType(candidate, candidate.returnType()),
                w.writeArgument(arg, SyntheticCalls.parameterType(candidate, 0)));
        return completer.completeCall(candidate, mode);
    }

    private TExpr cast(Expr.As a, Context ctx) {
        // TODO: the expected type from the cast for calls, as kotlinc's fromCast mode
        TExpr operand = expr(a.expr(), ctx, Mode.INDEPENDENT);
        KType target = typeOperand(a.type(), operand.type(), ctx);
        return new TExpr.Cast(a.pos(), a.safe() ? target.withNullable(true) : target, operand, target, a.safe());
    }

    // ==== Jumps and loops ====

    private TExpr returnExpr(Expr.Return r, Context ctx) {
        Context.FunctionFrame target = ctx.function();
        while (target != null && (r.label() == null ? target.isLambda : !r.label().equals(target.label))) {
            target = target.outer;
        }
        if (target == null) {
            throw error(r.pos(), r.label() == null ? "'return' is not allowed here" : "Unresolved label " + r.label());
        }
        KType returnType = target.function.returnType();
        TExpr value = null;
        if (r.value() != null) {
            value = expr(r.value(), ctx, returnType != null ? Mode.expecting(returnType) : Mode.INDEPENDENT);
        }
        if (target.isLambda) target.returns.add(value);
        return new TExpr.Return(r.pos(), ts.nothing(), target.function, value);
    }

    private TExpr.Loop loop(int pos, String label, Context ctx) {
        for (Context.LoopFrame f = ctx.loop(); f != null; f = f.outer()) {
            if (label == null || label.equals(f.loop().label())) return f.loop();
        }
        throw error(pos, label == null ? "'break' and 'continue' are only allowed in loops" : "Unresolved label " + label);
    }

    private TExpr labeled(Expr.Labeled l, Context ctx, Mode mode) {
        Expr inner = l.expr();
        while (inner instanceof Expr.Annotated a) inner = a.expr();
        return switch (inner) {
            case Expr.While w -> whileLoop(w, l.label(), ctx);
            case Expr.DoWhile w -> doWhileLoop(w, l.label(), ctx);
            case Expr.For f -> forLoop(f, l.label(), ctx);
            // TODO: labeled lambdas
            default -> expr(inner, ctx, mode);
        };
    }

    private TExpr whileLoop(Expr.While w, String label, Context ctx) {
        TExpr.Loop loop = new TExpr.Loop(label);
        TExpr condition = expr(w.condition(), ctx, Mode.expecting(bool()));
        TExpr body = branch(w.pos(), w.body(), ctx.withLoop(loop), Mode.INDEPENDENT);
        return new TExpr.While(w.pos(), unit(), loop, condition, body, false);
    }

    /**
     * {@code for (x in r) body}, desugared as kotlinc does: a temporary for
     * {@code r.iterator()}, and a while loop on its {@code hasNext()} whose
     * body begins with {@code val x = it.next()}.
     */
    private TExpr forLoop(Expr.For f, String label, Context ctx) {
        TExpr.Loop loop = new TExpr.Loop(label);
        TExpr iterator = desugaredCall(f.pos(), "iterator", receiver(f.iterable(), ctx), ctx, Mode.INDEPENDENT);
        LocalVariable it = temporary("<iterator>", approximateLocalType(iterator.type()));
        TExpr hasNext = desugaredCall(f.pos(), "hasNext", localValue(f.pos(), it), ctx, Mode.expecting(bool()));

        Context inner = ctx.withLocalScope(new LocalScope()).withLoop(loop);
        Expr.Binding b = f.variable();
        KType declared = b.type() == null ? null : resolveType(b.type(), inner);
        TExpr next = desugaredCall(b.pos(), "next", localValue(b.pos(), it), inner,
                declared != null ? Mode.expecting(declared) : Mode.INDEPENDENT);
        List<TExpr> statements = new ArrayList<>();
        KType type = declared != null ? declared : approximateLocalType(next.type());
        if (b.destructuring() != null) {
            LocalVariable v = temporary("<destruct>", type);
            statements.add(new TExpr.LocalVarDecl(b.pos(), unit(), v, next));
            destructure(b.destructuring(), v, inner, statements);
        } else {
            LocalVariable v = new LocalVariable(b.name(), type, LocalVariable.Kind.LOCAL, false, null);
            if (!b.name().equals("_")) inner.locals().getLast().add(v);
            statements.add(new TExpr.LocalVarDecl(b.pos(), unit(), v, next));
        }
        statements.add(branch(f.pos(), f.body(), inner, Mode.INDEPENDENT));
        TExpr body = new TExpr.Block(f.pos(), unit(), statements);
        return new TExpr.Block(f.pos(), unit(), List.of(
                new TExpr.LocalVarDecl(f.pos(), unit(), it, iterator),
                new TExpr.While(f.pos(), unit(), loop, hasNext, body, false)));
    }

    /** The body and condition of a do-while share a scope: the condition sees the body's variables. */
    private TExpr doWhileLoop(Expr.DoWhile w, String label, Context ctx) {
        TExpr.Loop loop = new TExpr.Loop(label);
        Context inner = ctx.withLocalScope(new LocalScope()).withLoop(loop);
        List<Stmt> statements = w.body() == null ? List.of()
                : w.body() instanceof Expr.Block b ? b.statements() : List.of(w.body());
        TExpr body = blockInScope(w.pos(), statements, inner, Mode.INDEPENDENT);
        TExpr condition = expr(w.condition(), inner, Mode.expecting(bool()));
        return new TExpr.While(w.pos(), unit(), loop, condition, body, true);
    }

    // ==== Types ====

    KType resolveType(TypeRef ref, Context ctx) {
        return owner.resolver().types().resolve(ref, ctx.typeScope(), ctx.file());
    }

    /** The type after {@code is} or {@code as}, a bare type with the arguments the operand's type implies. */
    private KType typeOperand(TypeRef ref, KType operandType, Context ctx) {
        KType type = owner.resolver().types().resolveAllowingBare(ref, ctx.typeScope(), ctx.file());
        if (!BareTypes.isBare(type)) return type;
        KType.ClassType bare = (KType.ClassType) Types.lowerBound(type);
        KType.ClassType result = BareTypes.representative(ts, owner.resolver().types(), bare, operandType);
        if (result == null) throw error(ref.pos(), "No type arguments for " + bare.symbol());
        return result;
    }

    private KType.ClassType type(String fqName) {
        return ts.classType(fqName);
    }

    private KType bool() {
        return type("kotlin.Boolean");
    }

    KType unit() {
        return type("kotlin.Unit");
    }

    static boolean isUnitOrFlexibleUnit(KType t) {
        return t != null && Types.lowerBound(t) instanceof KType.ClassType c && !c.nullable()
                && c.symbol().id().fqName().equals("kotlin.Unit");
    }
}
