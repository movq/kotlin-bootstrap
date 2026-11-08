package kotlinbootstrap.jvm;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

import kotlinbootstrap.check.TExpr;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.PackageSymbol;
import kotlinbootstrap.types.KType;

/**
 * Calls of the builtins with no class files of their own, as kotlinc's
 * IntrinsicMethods: the members of primitive types and arrays, and the
 * functions of Library.kt, declared in files with
 * {@code @SuppressBytecodeGeneration}, compiled to instructions.
 */
final class Intrinsics {
    private static final ClassDesc STRING_BUILDER = ClassDesc.of("java.lang.StringBuilder");

    private final ExprCodegen gen;
    private final CodeBuilder code;

    Intrinsics(ExprCodegen gen) {
        this.gen = gen;
        this.code = gen.code;
    }

    /** Whether a declaration is a builtin without bytecode, as everything in the files of jvm/builtins. */
    static boolean isBuiltin(JvmBackend backend, CallableSymbol s) {
        if (s.owner() instanceof KotlinClassSymbol k) return JvmNames.isBuiltinFile(k.file().file());
        if (s.owner() instanceof PackageSymbol) {
            var file = backend.names.file(s);
            return file != null && JvmNames.isBuiltinFile(file.file());
        }
        return false;
    }

    /** {@code !b}, a call of Boolean.not. */
    static boolean isBooleanNot(TExpr.Call c) {
        return c.function().name().equals("not") && c.args().isEmpty() && c.dispatchReceiver() != null
                && c.function().owner() instanceof ClassSymbol cls && TypeMapper.isClass(cls, "kotlin.Boolean");
    }

    /** The primitive type of a builtin primitive class's members, or null. */
    static ClassDesc primitiveOwner(CallableSymbol f) {
        return f.owner() instanceof ClassSymbol cls ? TypeMapper.primitive(cls) : null;
    }

    /** The element type of an array class's members: the primitive, or Object for Array; null if not an array. */
    static ClassDesc arrayOwner(CallableSymbol f) {
        if (!(f.owner() instanceof ClassSymbol cls)) return null;
        if (TypeMapper.isClass(cls, "kotlin.Array")) return TypeMapper.OBJECT;
        return TypeMapper.primitiveArrayElement(cls);
    }

    /** Generates an intrinsic call and returns the type left; null if the function is not an intrinsic. */
    ClassDesc call(TExpr.Call c) {
        FunctionSymbol f = c.function();
        // kotlin.jvm.internal.unsafe's monitorEnter and monitorExit, as kotlinc's MonitorInstruction.
        if (f.owner() instanceof PackageSymbol p && p.name().equals("kotlin.jvm.internal.unsafe")
                && (f.name().equals("monitorEnter") || f.name().equals("monitorExit"))) {
            gen.genAs(arg(c, 0), TypeMapper.OBJECT);
            if (f.name().equals("monitorEnter")) code.monitorenter();
            else code.monitorexit();
            return TypeMapper.VOID;
        }
        if (f.owner() instanceof PackageSymbol p && p.name().equals("kotlin.coroutines.intrinsics")
                && f.name().equals("suspendCoroutineUninterceptedOrReturn")) {
            return suspendCoroutineUninterceptedOrReturn(arg(c, 0));
        }
        if (!isBuiltin(gen.backend, f)) return null;
        ClassDesc primitive = primitiveOwner(f);
        if (primitive != null) return primitiveCall(c, primitive);
        ClassDesc element = arrayOwner(f);
        if (element != null) return arrayCall(c);
        if (f.owner() instanceof ClassSymbol cls && TypeMapper.isClass(cls, "kotlin.String")
                && f.name().equals("plus")) {
            return concat(c.dispatchReceiver(), arg(c, 0));
        }
        if (f.owner() instanceof PackageSymbol) return libraryCall(c);
        return null;
    }

    /**
     * suspendCoroutineUninterceptedOrReturn: calls the block with the
     * continuation, a suspension point, as kotlinc's intrinsic; the block as
     * a function object.
     */
    private ClassDesc suspendCoroutineUninterceptedOrReturn(TExpr block) {
        ClassDesc function1 = ClassDesc.ofInternalName("kotlin/jvm/functions/Function1");
        if (ExprCodegen.unwrap(block) instanceof TExpr.LocalGet g
                && gen.env.lookup(g.variable()) instanceof ExprCodegen.InlineLambda) {
            gen.coerce(gen.loadForCapture(g.variable()), function1);
        } else {
            gen.genAs(block, function1);
        }
        gen.loadContinuation();
        gen.suspensionPoint();
        code.invokeinterface(function1, "invoke", MethodTypeDesc.of(TypeMapper.OBJECT, TypeMapper.OBJECT));
        return TypeMapper.OBJECT;
    }

    private static TExpr arg(TExpr.Call c, int param) {
        for (TExpr.Argument a : c.args()) if (a.param() == param) return a.value();
        return null;
    }

    // ==== Primitives ====

    private ClassDesc primitiveCall(TExpr.Call c, ClassDesc own) {
        FunctionSymbol f = c.function();
        TExpr receiver = c.dispatchReceiver();
        TExpr other = arg(c, 0);
        ClassDesc result = f.returnType() == null ? TypeMapper.VOID : gen.types.mapType(f.returnType());
        String name = f.name();
        switch (name) {
            case "plus", "minus", "times", "div", "rem", "and", "or", "xor" -> {
                ClassDesc op = result.equals(ConstantDescs.CD_char) ? ConstantDescs.CD_int : result;
                binary(receiver, op, other, op);
                code.with(java.lang.classfile.instruction.OperatorInstruction.of(operator(name, TypeKind.from(op))));
                if (result.equals(ConstantDescs.CD_char)) code.i2c();
                return result;
            }
            case "shl", "shr", "ushr" -> {
                binary(receiver, own, other, ConstantDescs.CD_int);
                code.with(java.lang.classfile.instruction.OperatorInstruction.of(operator(name, TypeKind.from(own))));
                return own;
            }
            case "unaryMinus" -> {
                gen.genAs(receiver, result);
                code.with(java.lang.classfile.instruction.OperatorInstruction.of(
                        switch (TypeKind.from(result)) {
                            case LONG -> Opcode.LNEG;
                            case FLOAT -> Opcode.FNEG;
                            case DOUBLE -> Opcode.DNEG;
                            default -> Opcode.INEG;
                        }));
                return result;
            }
            case "unaryPlus" -> {
                gen.genAs(receiver, result);
                return result;
            }
            case "inv" -> {
                gen.genAs(receiver, own);
                if (own.equals(ConstantDescs.CD_long)) code.ldc(-1L).lxor(); else code.iconst_m1().ixor();
                return own;
            }
            case "not" -> {
                gen.genAs(receiver, ConstantDescs.CD_boolean);
                code.iconst_1().ixor();
                return ConstantDescs.CD_boolean;
            }
            case "inc", "dec" -> {
                ClassDesc op = TypeKind.from(own).asLoadable() == TypeKind.INT ? ConstantDescs.CD_int : own;
                gen.genAs(receiver, op);
                switch (TypeKind.from(op)) {
                    case LONG -> code.lconst_1();
                    case FLOAT -> code.fconst_1();
                    case DOUBLE -> code.dconst_1();
                    default -> code.iconst_1();
                }
                code.with(java.lang.classfile.instruction.OperatorInstruction.of(
                        operator(name.equals("inc") ? "plus" : "minus", TypeKind.from(op))));
                gen.primitiveConversion(op, own);
                return own;
            }
            case "compareTo" -> {
                ClassDesc op = compareType(own, gen.types.mapType(f.signature().params().getFirst().type()));
                binary(receiver, op, other, op);
                compare(op);
                return ConstantDescs.CD_int;
            }
            case "rangeTo", "rangeUntil" -> {
                ClassDesc rangeElement = rangeElement(result);
                binary(receiver, rangeElement, other, rangeElement);
                if (name.equals("rangeTo")) {
                    rangeOf(result, rangeElement);
                } else {
                    code.invokestatic(ClassDesc.ofInternalName("kotlin/ranges/RangesKt"), "until",
                            MethodTypeDesc.of(result, rangeElement, rangeElement));
                }
                return result;
            }
            case "equals" -> {
                gen.genAs(receiver, TypeMapper.box(own));
                gen.genAs(other, TypeMapper.OBJECT);
                code.invokestatic(ExprCodegen.INTRINSICS, "areEqual",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, TypeMapper.OBJECT, TypeMapper.OBJECT));
                return ConstantDescs.CD_boolean;
            }
            case "hashCode" -> {
                gen.genAs(receiver, own);
                code.invokestatic(TypeMapper.box(own), "hashCode", MethodTypeDesc.of(ConstantDescs.CD_int, own));
                return ConstantDescs.CD_int;
            }
            case "toString" -> {
                gen.genAs(receiver, own);
                gen.toStringValue(own);
                return TypeMapper.STRING;
            }
            default -> {
                if (name.startsWith("to") && c.args().isEmpty()) {
                    // A conversion, as Int.toLong.
                    gen.genAs(receiver, own);
                    gen.primitiveConversion(own, result);
                    return result;
                }
                throw new CodegenException(c.pos(), "Unknown intrinsic " + f);
            }
        }
    }

    /** Evaluates two operands as the types, spilling the first if the second needs an empty stack. */
    private void binary(TExpr left, ClassDesc leftType, TExpr right, ClassDesc rightType) {
        List<Object> spilled = gen.spillIfNeeded(java.util.Arrays.asList(left, right));
        gen.coerce(gen.operand(left, spilled, 0, leftType), leftType);
        gen.coerce(gen.operand(right, spilled, 1, rightType), rightType);
    }

    /** The type two primitives are compared as: the wider, as Int.compareTo(Long) compares longs. */
    static ClassDesc compareType(ClassDesc a, ClassDesc b) {
        if (a.equals(ConstantDescs.CD_boolean)) return a;
        return rank(a) >= rank(b) ? widen(a) : widen(b);
    }

    private static ClassDesc widen(ClassDesc t) {
        return TypeKind.from(t).asLoadable() == TypeKind.INT ? ConstantDescs.CD_int : t;
    }

    private static int rank(ClassDesc t) {
        return switch (TypeKind.from(t)) {
            case LONG -> 2;
            case FLOAT -> 3;
            case DOUBLE -> 4;
            default -> 1;
        };
    }

    /** Compares the two values on the stack to an int, as compareTo. */
    private void compare(ClassDesc op) {
        ClassDesc box = op.equals(ConstantDescs.CD_boolean) ? ConstantDescs.CD_Boolean : TypeMapper.box(op);
        code.invokestatic(box, "compare", MethodTypeDesc.of(ConstantDescs.CD_int, op, op));
    }

    static Opcode operator(String name, TypeKind kind) {
        int k = switch (kind.asLoadable()) {
            case LONG -> 1;
            case FLOAT -> 2;
            case DOUBLE -> 3;
            default -> 0;
        };
        Opcode[] ops = switch (name) {
            case "plus" -> new Opcode[]{Opcode.IADD, Opcode.LADD, Opcode.FADD, Opcode.DADD};
            case "minus" -> new Opcode[]{Opcode.ISUB, Opcode.LSUB, Opcode.FSUB, Opcode.DSUB};
            case "times" -> new Opcode[]{Opcode.IMUL, Opcode.LMUL, Opcode.FMUL, Opcode.DMUL};
            case "div" -> new Opcode[]{Opcode.IDIV, Opcode.LDIV, Opcode.FDIV, Opcode.DDIV};
            case "rem" -> new Opcode[]{Opcode.IREM, Opcode.LREM, Opcode.FREM, Opcode.DREM};
            case "and" -> new Opcode[]{Opcode.IAND, Opcode.LAND};
            case "or" -> new Opcode[]{Opcode.IOR, Opcode.LOR};
            case "xor" -> new Opcode[]{Opcode.IXOR, Opcode.LXOR};
            case "shl" -> new Opcode[]{Opcode.ISHL, Opcode.LSHL};
            case "shr" -> new Opcode[]{Opcode.ISHR, Opcode.LSHR};
            case "ushr" -> new Opcode[]{Opcode.IUSHR, Opcode.LUSHR};
            default -> throw new IllegalArgumentException(name);
        };
        return ops[k];
    }

    private static ClassDesc rangeElement(ClassDesc range) {
        return switch (range.descriptorString()) {
            case "Lkotlin/ranges/LongRange;" -> ConstantDescs.CD_long;
            case "Lkotlin/ranges/CharRange;" -> ConstantDescs.CD_char;
            default -> ConstantDescs.CD_int;
        };
    }

    /** Constructs a range of the two values on the stack. */
    private void rangeOf(ClassDesc range, ClassDesc element) {
        int second = gen.newSlot(element);
        int first = gen.newSlot(element);
        code.storeLocal(TypeKind.from(element), second);
        code.storeLocal(TypeKind.from(element), first);
        code.new_(range).dup();
        code.loadLocal(TypeKind.from(element), first);
        code.loadLocal(TypeKind.from(element), second);
        code.invokespecial(range, "<init>", MethodTypeDesc.of(TypeMapper.VOID, element, element));
    }

    // ==== Arrays ====

    private ClassDesc arrayCall(TExpr.Call c) {
        FunctionSymbol f = c.function();
        ClassDesc arrayType = gen.mapType(c.type());
        if (f.isConstructor()) {
            ClassDesc element = arrayType.componentType();
            TExpr init = arg(c, 1);
            if (init == null) {
                gen.genAs(arg(c, 0), ConstantDescs.CD_int);
                gen.newArray(element);
                return arrayType;
            }
            return arrayWithInit(c, arrayType, init);
        }
        ClassDesc receiverType = gen.mapType(c.dispatchReceiver().type());
        if (!receiverType.isArray()) receiverType = arrayType(f);
        ClassDesc element = receiverType.componentType();
        switch (f.name()) {
            case "get" -> {
                List<Object> spilled = gen.spillIfNeeded(java.util.Arrays.asList(c.dispatchReceiver(), arg(c, 0)));
                gen.coerce(gen.operand(c.dispatchReceiver(), spilled, 0, receiverType), receiverType);
                gen.coerce(gen.operand(arg(c, 0), spilled, 1, ConstantDescs.CD_int), ConstantDescs.CD_int);
                code.arrayLoad(TypeKind.from(element));
                return element;
            }
            case "set" -> {
                List<Object> spilled = gen.spillIfNeeded(java.util.Arrays.asList(c.dispatchReceiver(), arg(c, 0),
                        arg(c, 1)));
                gen.coerce(gen.operand(c.dispatchReceiver(), spilled, 0, receiverType), receiverType);
                gen.coerce(gen.operand(arg(c, 0), spilled, 1, ConstantDescs.CD_int), ConstantDescs.CD_int);
                gen.coerce(gen.operand(arg(c, 1), spilled, 2, element), element);
                code.arrayStore(TypeKind.from(element));
                return TypeMapper.VOID;
            }
            case "iterator" -> {
                if (element.isPrimitive()) {
                    gen.genAs(c.dispatchReceiver(), receiverType);
                    ClassDesc iterator = gen.mapType(f.returnType());
                    code.invokestatic(ClassDesc.ofInternalName("kotlin/jvm/internal/ArrayIteratorsKt"), "iterator",
                            MethodTypeDesc.of(iterator, receiverType));
                    return iterator;
                }
                gen.genAs(c.dispatchReceiver(), TypeMapper.OBJECT.arrayType());
                ClassDesc iterator = ClassDesc.of("java.util.Iterator");
                code.invokestatic(ClassDesc.ofInternalName("kotlin/jvm/internal/ArrayIteratorKt"), "iterator",
                        MethodTypeDesc.of(iterator, TypeMapper.OBJECT.arrayType()));
                return iterator;
            }
            case "clone" -> {
                gen.genAs(c.dispatchReceiver(), receiverType);
                code.invokevirtual(receiverType, "clone", MethodTypeDesc.of(TypeMapper.OBJECT));
                code.checkcast(receiverType);
                return receiverType;
            }
            default -> throw new CodegenException(c.pos(), "Unknown array intrinsic " + f);
        }
    }

    private ClassDesc arrayType(FunctionSymbol f) {
        ClassSymbol cls = (ClassSymbol) f.owner();
        ClassDesc element = TypeMapper.primitiveArrayElement(cls);
        return element != null ? element.arrayType() : TypeMapper.OBJECT.arrayType();
    }

    /** {@code IntArray(n) { init }}: a loop storing the lambda's value for each index. */
    private ClassDesc arrayWithInit(TExpr.Call c, ClassDesc arrayType, TExpr init) {
        ClassDesc element = arrayType.componentType();
        gen.genAs(arg(c, 0), ConstantDescs.CD_int);
        gen.newArray(element);
        int array = gen.newSlot(arrayType);
        code.astore(array);
        int index = gen.newSlot(ConstantDescs.CD_int);
        code.iconst_0().istore(index);
        Label start = code.newBoundLabel();
        Label end = code.newLabel();
        code.iload(index).aload(array).arraylength().if_icmpge(end);
        code.aload(array).iload(index);
        ExprCodegen.Slot indexSlot = new ExprCodegen.Slot(index, ConstantDescs.CD_int, false);
        gen.invokeWithIndex(init, indexSlot, element);
        code.arrayStore(TypeKind.from(element));
        code.iinc(index, 1);
        code.goto_(start);
        code.labelBinding(end);
        code.aload(array);
        return arrayType;
    }

    // ==== Library.kt ====

    private ClassDesc libraryCall(TExpr.Call c) {
        FunctionSymbol f = c.function();
        switch (f.name()) {
            case "toString" -> {
                gen.genAs(c.extensionReceiver(), TypeMapper.OBJECT);
                code.invokestatic(TypeMapper.STRING, "valueOf", MethodTypeDesc.of(TypeMapper.STRING, TypeMapper.OBJECT));
                return TypeMapper.STRING;
            }
            case "plus" -> {
                List<Object> spilled = gen.spillIfNeeded(java.util.Arrays.asList(c.extensionReceiver(), arg(c, 0)));
                gen.coerce(gen.operand(c.extensionReceiver(), spilled, 0, TypeMapper.STRING), TypeMapper.STRING);
                gen.coerce(gen.operand(arg(c, 0), spilled, 1, TypeMapper.OBJECT), TypeMapper.OBJECT);
                code.invokestatic(ExprCodegen.INTRINSICS, "stringPlus",
                        MethodTypeDesc.of(TypeMapper.STRING, TypeMapper.STRING, TypeMapper.OBJECT));
                return TypeMapper.STRING;
            }
            case "arrayOfNulls" -> {
                ClassDesc arrayType = gen.mapType(c.type());
                gen.genAs(arg(c, 0), ConstantDescs.CD_int);
                gen.newArray(arrayType.componentType());
                return arrayType;
            }
            case "enumValues", "enumValueOf" -> {
                KType t = TypeMapper.substitute(gen.env.reified, c.typeArgs().getFirst());
                ClassDesc enumType = gen.types.mapType(t);
                if (f.name().equals("enumValues")) {
                    code.invokestatic(enumType, "values", MethodTypeDesc.of(enumType.arrayType()));
                    return enumType.arrayType();
                }
                gen.genAs(arg(c, 0), TypeMapper.STRING);
                code.invokestatic(enumType, "valueOf", MethodTypeDesc.of(enumType, TypeMapper.STRING));
                return enumType;
            }
            default -> {
                if (f.name().equals("arrayOf") || f.name().endsWith("ArrayOf")) {
                    ClassDesc arrayType = gen.mapType(c.type());
                    TExpr elements = arg(c, 0);
                    if (elements instanceof TExpr.Vararg v) return gen.vararg(v, arrayType);
                    if (elements == null) {
                        gen.emptyArray(arrayType);
                        return arrayType;
                    }
                    gen.genAs(elements, arrayType);
                    return arrayType;
                }
                throw new CodegenException(c.pos(), "Unknown intrinsic " + f);
            }
        }
    }

    // ==== Strings ====

    /** {@code a + b} for a String a: through a StringBuilder. */
    ClassDesc concat(TExpr left, TExpr right) {
        List<TExpr> parts = new ArrayList<>();
        flatten(left, parts);
        parts.add(right);
        List<Object> spilled = gen.spillIfNeeded(parts);
        code.new_(STRING_BUILDER).dup().invokespecial(STRING_BUILDER, "<init>", MethodTypeDesc.of(TypeMapper.VOID));
        for (int i = 0; i < parts.size(); i++) gen.append(STRING_BUILDER, gen.operand(parts.get(i), spilled, i, null));
        code.invokevirtual(STRING_BUILDER, "toString", MethodTypeDesc.of(TypeMapper.STRING));
        return TypeMapper.STRING;
    }

    /** The operands of a chain of String concatenations, as {@code a + b + c}. */
    private void flatten(TExpr e, List<TExpr> parts) {
        if (e instanceof TExpr.Call c && c.function().name().equals("plus") && c.dispatchReceiver() != null
                && c.function().owner() instanceof ClassSymbol cls && TypeMapper.isClass(cls, "kotlin.String")
                && isBuiltin(gen.backend, c.function())) {
            flatten(c.dispatchReceiver(), parts);
            parts.add(arg(c, 0));
            return;
        }
        parts.add(e);
    }
}
