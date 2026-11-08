package kotlinbootstrap.jvm;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.Arrays;
import java.util.List;

import kotlinbootstrap.check.TExpr;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Types;

/**
 * Conditional jumps on equality, comparisons and type checks, as kotlinc's
 * Equals, CompareTo and TypeOperatorLowering: primitives compared
 * directly, IEEE 754 for floating-point types known statically, and
 * everything else through equals.
 */
final class Conditions {
    private final ExprCodegen gen;
    private final CodeBuilder code;

    Conditions(ExprCodegen gen) {
        this.gen = gen;
        this.code = gen.code;
    }

    // ==== Equality ====

    void equality(TExpr.Equality q, boolean ifTrue, Label target) {
        boolean identity = q.op() == TExpr.EqualityOp.IDENTITY_EQ || q.op() == TExpr.EqualityOp.IDENTITY_NE;
        boolean negated = q.op() == TExpr.EqualityOp.NE || q.op() == TExpr.EqualityOp.IDENTITY_NE;
        boolean jumpIfEqual = ifTrue != negated;
        TExpr left = q.left();
        TExpr right = q.right();
        if (isNull(right) || isNull(left)) {
            TExpr value = isNull(right) ? left : right;
            ClassDesc t = gen.gen(value, null);
            if (t.isPrimitive()) {
                gen.pop(t);
                if (!jumpIfEqual) code.goto_(target);
                return;
            }
            if (t.equals(TypeMapper.VOID)) {
                if (!jumpIfEqual) code.goto_(target);
                return;
            }
            if (jumpIfEqual) code.ifnull(target); else code.ifnonnull(target);
            return;
        }
        ClassDesc lt = primitiveType(left.type());
        ClassDesc rt = primitiveType(right.type());
        if (lt != null && rt != null && (identity || comparableAsPrimitives(lt, rt))) {
            ClassDesc op = Intrinsics.compareType(lt, rt);
            // Values narrower than int are ints on the stack: generated as such, a boxed Char isn't a Number.
            binary(left, isInt(op) ? lt : op, right, isInt(op) ? rt : op);
            compareJump(op, jumpIfEqual ? Opcode.IF_ICMPEQ : Opcode.IF_ICMPNE, target, true);
            return;
        }
        if (identity) {
            binary(left, TypeMapper.OBJECT, right, TypeMapper.OBJECT);
            if (jumpIfEqual) code.if_acmpeq(target); else code.if_acmpne(target);
            return;
        }
        binary(left, TypeMapper.OBJECT, right, TypeMapper.OBJECT);
        code.invokestatic(ExprCodegen.INTRINSICS, "areEqual",
                MethodTypeDesc.of(ConstantDescs.CD_boolean, TypeMapper.OBJECT, TypeMapper.OBJECT));
        if (jumpIfEqual) code.ifne(target); else code.ifeq(target);
    }

    private static boolean isInt(ClassDesc t) {
        return t.equals(ConstantDescs.CD_int);
    }

    private static boolean isNull(TExpr e) {
        return e instanceof TExpr.Const c && c.value() == null;
    }

    /** The primitive type a value of the type is, if not nullable; null otherwise. */
    private ClassDesc primitiveType(KType t) {
        if (t instanceof KType.Flexible) return null;
        ClassDesc d = gen.mapType(t);
        return d.isPrimitive() ? d : null;
    }

    /** Same kind of value: both numbers, both chars or both booleans, so == compares them as primitives. */
    private static boolean comparableAsPrimitives(ClassDesc a, ClassDesc b) {
        boolean an = !a.equals(ConstantDescs.CD_boolean) && !a.equals(ConstantDescs.CD_char);
        boolean bn = !b.equals(ConstantDescs.CD_boolean) && !b.equals(ConstantDescs.CD_char);
        return an && bn || a.equals(b);
    }

    private void binary(TExpr left, ClassDesc leftType, TExpr right, ClassDesc rightType) {
        List<Object> spilled = gen.spillIfNeeded(Arrays.asList(left, right));
        gen.coerce(gen.operand(left, spilled, 0, leftType), leftType);
        gen.coerce(gen.operand(right, spilled, 1, rightType), rightType);
    }

    /**
     * Jumps on comparing the two values of the type on the stack, by the
     * int comparison it would be for ints; IEEE 754 for floating point,
     * where NaN compares false.
     */
    private void compareJump(ClassDesc op, Opcode intCompare, Label target, boolean jumpIfTrue) {
        switch (TypeKind.from(op).asLoadable()) {
            case INT -> code.branch(intCompare, target);
            case LONG -> {
                code.lcmp();
                code.branch(zeroCompare(intCompare), target);
            }
            case FLOAT, DOUBLE -> {
                // NaN makes fcmpg 1 and fcmpl -1: the comparison that is false for it.
                boolean lessIsTrue = intCompare == Opcode.IF_ICMPLT || intCompare == Opcode.IF_ICMPLE;
                boolean nanGivesG = jumpIfTrue ? lessIsTrue : !lessIsTrue;
                boolean isFloat = TypeKind.from(op) == TypeKind.FLOAT;
                if (isFloat) {
                    if (nanGivesG) code.fcmpg(); else code.fcmpl();
                } else {
                    if (nanGivesG) code.dcmpg(); else code.dcmpl();
                }
                code.branch(zeroCompare(intCompare), target);
            }
            default -> throw new IllegalStateException(op.toString());
        }
    }

    private static Opcode zeroCompare(Opcode intCompare) {
        return switch (intCompare) {
            case IF_ICMPEQ -> Opcode.IFEQ;
            case IF_ICMPNE -> Opcode.IFNE;
            case IF_ICMPLT -> Opcode.IFLT;
            case IF_ICMPGE -> Opcode.IFGE;
            case IF_ICMPGT -> Opcode.IFGT;
            case IF_ICMPLE -> Opcode.IFLE;
            default -> throw new IllegalArgumentException(intCompare.toString());
        };
    }

    private static Opcode negate(Opcode intCompare) {
        return switch (intCompare) {
            case IF_ICMPEQ -> Opcode.IF_ICMPNE;
            case IF_ICMPNE -> Opcode.IF_ICMPEQ;
            case IF_ICMPLT -> Opcode.IF_ICMPGE;
            case IF_ICMPGE -> Opcode.IF_ICMPLT;
            case IF_ICMPGT -> Opcode.IF_ICMPLE;
            case IF_ICMPLE -> Opcode.IF_ICMPGT;
            default -> throw new IllegalArgumentException(intCompare.toString());
        };
    }

    // ==== Comparisons ====

    void comparison(TExpr.Comparison c, boolean ifTrue, Label target) {
        Opcode op = switch (c.op()) {
            case LT -> Opcode.IF_ICMPLT;
            case GT -> Opcode.IF_ICMPGT;
            case LE -> Opcode.IF_ICMPLE;
            case GE -> Opcode.IF_ICMPGE;
        };
        Opcode jump = ifTrue ? op : negate(op);
        TExpr compareTo = ExprCodegen.unwrap(c.compareTo());
        if (compareTo instanceof TExpr.Call call && call.function().name().equals("compareTo")
                && Intrinsics.primitiveOwner(call.function()) != null
                && Intrinsics.isBuiltin(gen.backend, call.function())) {
            ClassDesc own = Intrinsics.primitiveOwner(call.function());
            ClassDesc other = gen.types.mapType(call.function().signature().params().getFirst().type());
            ClassDesc type = Intrinsics.compareType(own, other);
            binary(call.dispatchReceiver(), type, call.args().getFirst().value(), type);
            compareJump(type, jump, target, ifTrue);
            return;
        }
        gen.genAs(c.compareTo(), ConstantDescs.CD_int);
        code.branch(zeroCompare(jump), target);
    }

    // ==== Type checks ====

    /** Evaluates an expression and checks it is an instance of the type, leaving a boolean. */
    void isInstance(TExpr expr, KType type) {
        gen.genAs(expr, TypeMapper.OBJECT);
        instanceOfOnStack(type);
    }

    /** Replaces the value on the stack by whether it is an instance of the type, null being one of a nullable type. */
    void instanceOfOnStack(KType type) {
        type = TypeMapper.substitute(gen.env.reified, type);
        if (Types.isMarkedOrFlexiblyNullable(type)) {
            Label notNull = code.newLabel();
            Label end = code.newLabel();
            code.dup().ifnonnull(notNull);
            code.pop().iconst_1().goto_(end);
            code.labelBinding(notNull);
            instanceOf(type);
            code.labelBinding(end);
            return;
        }
        instanceOf(type);
    }

    private void instanceOf(KType type) {
        ClassDesc d = classToCheck(type);
        if (d == null) {
            // Any non-null value is an Any, or an instance of an erased type parameter bounded by it.
            Label isTrue = code.newLabel();
            Label end = code.newLabel();
            code.ifnonnull(isTrue);
            code.iconst_0().goto_(end);
            code.labelBinding(isTrue);
            code.iconst_1();
            code.labelBinding(end);
            return;
        }
        code.instanceOf(d);
    }

    /** The class an instance check tests, null for any non-null value. */
    private ClassDesc classToCheck(KType type) {
        KType t = Types.makeDefinitelyNotNull(type);
        if (t instanceof KType.TypeParamType p) {
            TypeParamSymbol s = p.symbol();
            if (s.bounds().isEmpty()) return null;
            return classToCheck(s.bounds().getFirst());
        }
        if (t instanceof KType.ClassType c) {
            ClassSymbol cls = c.symbol();
            if (TypeMapper.isClass(cls, "kotlin.Any")) return null;
            if (FunctionTypes.isFunctionType(c)) {
                int arity = c.args().size() - 1;
                return ClassDesc.ofInternalName("kotlin/jvm/functions/Function" + arity);
            }
        }
        ClassDesc d = gen.types.mapType(t);
        return d.isPrimitive() ? TypeMapper.box(d) : d;
    }
}
