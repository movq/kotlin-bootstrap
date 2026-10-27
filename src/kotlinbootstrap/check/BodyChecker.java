package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.List;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.Stmt;
import kotlinbootstrap.ast.TypeRef;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.syntax.SourceFile;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeApproximator;
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

    BodyChecker(Checker owner, SourceFile source) {
        this.owner = owner;
        this.ts = owner.ts();
        this.source = source;
        this.completer = new CallCompleter(ts, this);
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
            case Expr.IntLit l -> integerLiteral(l, mode);
            case Expr.FloatLit l -> floatLiteral(l);
            case Expr.CharLit l -> new TExpr.Const(l.pos(), type("kotlin.Char"), l.value());
            case Expr.BoolLit l -> new TExpr.Const(l.pos(), bool(), l.value());
            case Expr.NullLit l -> new TExpr.Const(l.pos(), ts.nullableNothing(), null);
            case Expr.StringTemplate s -> stringTemplate(s, ctx);
            case Expr.Paren p -> expr(p.expr(), ctx, mode);
            case Expr.Annotated a -> expr(a.expr(), ctx, mode);
            case Expr.Name n -> name(n, ctx);
            case Expr.This t -> thisExpr(t, ctx);
            case Expr.Block b -> block(b.pos(), b.statements(), ctx, mode);
            case Expr.DeclExpr d -> declaration(d.decl(), ctx);
            case Expr.If i -> ifExpr(i, ctx, mode);
            case Expr.When w -> whenExpr(w, ctx, mode);
            case Expr.Try t -> tryExpr(t, ctx, mode);
            case Expr.Binary b -> binary(b, ctx, mode);
            case Expr.Postfix p when p.op() == Expr.PostfixOp.NOT_NULL -> checkNotNull(p, ctx, mode);
            case Expr.Is i -> new TExpr.TypeCheck(i.pos(), bool(), expr(i.expr(), ctx, Mode.INDEPENDENT),
                    resolveType(i.type(), ctx), i.negated());
            case Expr.As a -> cast(a, ctx);
            case Expr.Assign a -> assign(a, ctx);
            case Expr.Return r -> returnExpr(r, ctx);
            case Expr.Throw t -> new TExpr.Throw(t.pos(), ts.nothing(),
                    expr(t.expr(), ctx, Mode.expecting(type("kotlin.Throwable"))));
            case Expr.Break b -> new TExpr.Break(b.pos(), ts.nothing(), loop(b.pos(), b.label(), ctx));
            case Expr.Continue c -> new TExpr.Continue(c.pos(), ts.nothing(), loop(c.pos(), c.label(), ctx));
            case Expr.While w -> whileLoop(w, null, ctx);
            case Expr.DoWhile w -> doWhileLoop(w, null, ctx);
            case Expr.Labeled l -> labeled(l, ctx, mode);
            default -> throw error(e.pos(), "Unsupported expression: " + e.getClass().getSimpleName());
        };
    }

    // ==== Literals ====

    /**
     * An integer literal: without a suffix, an integer literal type, which
     * becomes a definite type at once unless the literal is an argument.
     */
    private TExpr integerLiteral(Expr.IntLit l, Mode mode) {
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

    private TExpr name(Expr.Name n, Context ctx) {
        LocalVariable v = ctx.findLocal(n.name());
        if (v != null) return new TExpr.LocalGet(n.pos(), v.type(), v);
        // TODO: properties and objects through the tower
        throw error(n.pos(), "Unresolved reference " + n.name());
    }

    private TExpr thisExpr(Expr.This t, Context ctx) {
        for (Context.ImplicitReceiver r : ctx.receivers()) {
            if (t.label() == null || t.label().equals(r.label())) return new TExpr.This(t.pos(), r.type(), r.owner(), false);
        }
        throw error(t.pos(), t.label() == null ? "'this' is not defined in this context"
                : "Unresolved label " + t.label());
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
            result.add(statement(statements.get(i), ctx, m));
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

        boolean exhaustive = isExhaustive(w, subjectVariable);
        Mode branchMode = branchMode(w.entries().size(), mode);
        List<TExpr> conditions = new ArrayList<>();
        List<TExpr> bodies = new ArrayList<>();
        for (Expr.WhenEntry entry : w.entries()) {
            conditions.add(whenCondition(entry, subjectVariable, inner));
            bodies.add(branch(entry.pos(), entry.body(), inner, branchMode));
        }
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
                        new TExpr.LocalGet(i.pos(), subject.type(), subject), resolveType(i.type(), ctx), i.negated());
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

    /** Whether some branch is always taken. */
    private boolean isExhaustive(Expr.When w, LocalVariable subject) {
        for (Expr.WhenEntry entry : w.entries()) {
            if (entry.conditions().isEmpty() && entry.guard() == null) return true;
        }
        if (subject == null) return false;
        // TODO: enum entries and sealed subclasses
        if (Types.lowerBound(subject.type()) instanceof KType.ClassType c && !c.nullable()
                && c.symbol().id().fqName().equals("kotlin.Boolean")) {
            boolean sawTrue = false;
            boolean sawFalse = false;
            for (Expr.WhenEntry entry : w.entries()) {
                if (entry.guard() != null) continue;
                for (Expr.WhenCondition cond : entry.conditions()) {
                    if (cond instanceof Expr.ExprCondition e && unparenthesized(e.expr()) instanceof Expr.BoolLit b) {
                        if (b.value()) sawTrue = true; else sawFalse = true;
                    }
                }
            }
            return sawTrue && sawFalse;
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
            default -> throw error(b.pos(), "Operator " + b.op() + " is not supported yet");
        };
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
        KType target = resolveType(a.type(), ctx);
        // TODO: the expected type from the cast for calls, as kotlinc's fromCast mode
        TExpr operand = expr(a.expr(), ctx, Mode.INDEPENDENT);
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
