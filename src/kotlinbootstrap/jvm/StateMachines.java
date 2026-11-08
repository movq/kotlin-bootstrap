package kotlinbootstrap.jvm;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.StackMapFrameInfo;
import java.lang.classfile.attribute.StackMapFrameInfo.ObjectVerificationTypeInfo;
import java.lang.classfile.attribute.StackMapFrameInfo.SimpleVerificationTypeInfo;
import java.lang.classfile.attribute.StackMapFrameInfo.VerificationTypeInfo;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.PropertySymbol;

/**
 * Suspend functions as state machines, as kotlinc's
 * CoroutineTransformerMethodVisitor. A function that suspends gets a
 * continuation class of its own, a ContinuationImpl whose label says where
 * to resume, and whose fields keep the locals and operand stack live across
 * suspension points. The function's code is generated as if calls never
 * suspended, with each suspension point marked; this transforms it, with the
 * types of the locals and the stack at the marks from the stack map frames
 * the class file API computed for them.
 */
final class StateMachines {
    /** The constant of the mark before a suspend call: {@code ldc MARKER; ifnull next; next:}. */
    static final String MARKER = "kbc$suspensionPoint";
    private static final ClassDesc CONTINUATION_IMPL =
            ClassDesc.ofInternalName("kotlin/coroutines/jvm/internal/ContinuationImpl");
    private static final ClassDesc INTRINSICS = ClassDesc.ofInternalName("kotlin/coroutines/intrinsics/IntrinsicsKt");
    private static final ClassDesc RESULT = ClassDesc.ofInternalName("kotlin/Result");
    private static final ClassDesc FAILURE = ClassDesc.ofInternalName("kotlin/Result$Failure");
    private static final ClassDesc ILLEGAL_STATE = ClassDesc.of("java.lang.IllegalStateException");
    private static final int MIN_VALUE = Integer.MIN_VALUE;

    /**
     * A suspend function's method, whose code begins by copying its
     * continuation parameter, the last, to the continuation slot.
     */
    record Method(String name, MethodTypeDesc type, boolean isStatic, int completionSlot, int continuationSlot) {
    }

    /** A spilled value: the field of the continuation that keeps it, by kind. */
    private record Spill(TypeKind kind, String field, ClassDesc type, boolean isNull) {
    }

    /** A suspension point: the call, with the frame before it, whose stack ends with the call's arguments. */
    private static final class Point {
        final int label;
        final InvokeInstruction call;
        final List<Integer> localSlots = new ArrayList<>();
        final List<Spill> locals = new ArrayList<>();
        final List<Spill> stack = new ArrayList<>();
        final List<VerificationTypeInfo> stackTypes;
        final int arguments;
        Label resume;
        Label rethrow;
        int bci;

        Point(int label, InvokeInstruction call, List<VerificationTypeInfo> stackTypes, int arguments) {
            this.label = label;
            this.call = call;
            this.stackTypes = stackTypes;
            this.arguments = arguments;
        }
    }

    private final JvmBackend backend;
    private final ClassCodegen owner;

    StateMachines(JvmBackend backend, ClassCodegen owner) {
        this.backend = backend;
        this.owner = owner;
    }

    /** Transforms the methods of the class that suspend, and generates their continuation classes. */
    byte[] transform(ClassFile cf, byte[] bytes, Map<String, Method> methods) {
        ClassModel model = cf.parse(bytes);
        return cf.transformClass(model, (cb, ce) -> {
            if (ce instanceof MethodModel m && methods.get(m.methodName().stringValue()
                    + m.methodTypeSymbol().descriptorString()) instanceof Method method
                    && m.findAttribute(Attributes.code()).orElse(null) instanceof CodeAttribute code
                    && hasSuspensionPoints(code)) {
                cb.transformMethod(m, (mb, me) -> {
                    if (me instanceof CodeModel) {
                        mb.transformCode((CodeModel) me, new Transformer(method, code));
                    } else {
                        mb.with(me);
                    }
                });
            } else {
                cb.with(ce);
            }
        });
    }

    private static boolean hasSuspensionPoints(CodeAttribute code) {
        for (CodeElement e : code) {
            if (e instanceof ConstantInstruction c && MARKER.equals(c.constantValue())) return true;
        }
        return false;
    }

    /** Collects a method's code, then generates it as a state machine. */
    private final class Transformer implements CodeTransform {
        private final Method method;
        private final CodeAttribute code;
        private final List<CodeElement> elements = new ArrayList<>();
        private final List<ExceptionCatch> catches = new ArrayList<>();
        private final Map<TypeKind, Integer> fieldCounts = new EnumMap<>(TypeKind.class);
        private ClassDesc continuation;
        private int resultSlot;
        private int suspendedSlot;
        private int tempSlot;

        Transformer(Method method, CodeAttribute code) {
            this.method = method;
            this.code = code;
        }

        @Override
        public void accept(CodeBuilder b, CodeElement e) {
            if (e instanceof ExceptionCatch c) catches.add(c);
            else elements.add(e);
        }

        @Override
        public void atEnd(CodeBuilder b) {
            Map<Integer, StackMapFrameInfo> frames = new HashMap<>();
            code.findAttribute(Attributes.stackMapTable()).ifPresent(t -> {
                for (StackMapFrameInfo f : t.entries()) frames.put(code.labelToBci(f.target()), f);
            });
            // The suspension points, by the index of their call.
            Map<Integer, Point> points = new HashMap<>();
            List<Point> ordered = new ArrayList<>();
            int start = -1;
            int bci = 0;
            for (int i = 0; i < elements.size(); i++) {
                CodeElement e = elements.get(i);
                if (start < 0 && isStart(i)) start = i;
                if (e instanceof ConstantInstruction c && MARKER.equals(c.constantValue())
                        && elements.get(i + 1) instanceof BranchInstruction branch) {
                    int j = i + 2;
                    while (!(elements.get(j) instanceof Instruction)) j++;
                    if (!(elements.get(j) instanceof InvokeInstruction call)) {
                        throw new CodegenException(-1, "No call at a suspension point in " + method.name());
                    }
                    StackMapFrameInfo frame = frames.get(code.labelToBci(branch.target()));
                    MethodTypeDesc type = call.typeSymbol();
                    int arguments = type.parameterCount() + (call.opcode() == Opcode.INVOKESTATIC ? 0 : 1);
                    Point p = new Point(ordered.size() + 1, call, frame.stack(), arguments);
                    spills(p, frame);
                    points.put(j, p);
                    ordered.add(p);
                }
                if (e instanceof Instruction ins) {
                    if (points.get(i) instanceof Point p) p.bci = bci;
                    bci += ins.sizeInBytes();
                }
            }
            if (start < 0) throw new CodegenException(-1, "No continuation in " + method.name());
            continuation = ClassDesc.ofInternalName(backend.continuationClassName(owner, method.name()));
            resultSlot = code.maxLocals();
            suspendedSlot = resultSlot + 1;
            tempSlot = suspendedSlot + 1;

            List<Label> resumes = new ArrayList<>();
            for (Point p : ordered) {
                p.resume = b.newLabel();
                p.rethrow = b.newLabel();
                resumes.add(p.resume);
            }
            Label first = b.newLabel();
            prologue(b, first, resumes);
            b.labelBinding(first);
            rethrowFailure(b);
            for (int i = 0; i < elements.size(); i++) {
                CodeElement e = elements.get(i);
                if (i == start) {
                    i += 2;
                    continue;
                }
                if (e instanceof ConstantInstruction c && MARKER.equals(c.constantValue())) {
                    i++;
                    continue;
                }
                Point p = points.get(i);
                if (p == null) {
                    b.with(e);
                    continue;
                }
                suspensionPoint(b, p);
            }
            for (ExceptionCatch c : catches) splitCatch(b, c, ordered);
            continuationClass();
        }

        /** Whether the instructions at the index copy the continuation parameter to its slot. */
        private boolean isStart(int i) {
            return i + 2 < elements.size()
                    && elements.get(i) instanceof LoadInstruction l && l.slot() == method.completionSlot()
                    && elements.get(i + 1) instanceof TypeCheckInstruction t && t.opcode() == Opcode.CHECKCAST
                    && elements.get(i + 2) instanceof StoreInstruction s && s.slot() == method.continuationSlot();
        }

        /** The fields that keep the live locals and the stack under the call's arguments. */
        private void spills(Point p, StackMapFrameInfo frame) {
            Map<TypeKind, Integer> counts = new EnumMap<>(TypeKind.class);
            int slot = 0;
            for (VerificationTypeInfo t : frame.locals()) {
                int s = slot;
                slot += t == SimpleVerificationTypeInfo.LONG || t == SimpleVerificationTypeInfo.DOUBLE ? 2 : 1;
                if (t == SimpleVerificationTypeInfo.TOP || !method.isStatic() && s == 0
                        || s == method.completionSlot() || s == method.continuationSlot()) {
                    continue;
                }
                p.localSlots.add(s);
                p.locals.add(spill(t, counts));
            }
            List<VerificationTypeInfo> stack = frame.stack();
            for (int i = 0; i < stack.size() - p.arguments; i++) p.stack.add(spill(stack.get(i), counts));
            counts.forEach((k, n) -> fieldCounts.merge(k, n, Math::max));
        }

        private Spill spill(VerificationTypeInfo t, Map<TypeKind, Integer> counts) {
            if (t == SimpleVerificationTypeInfo.NULL) return new Spill(TypeKind.REFERENCE, null, null, true);
            TypeKind kind;
            ClassDesc type = null;
            switch (t) {
                case SimpleVerificationTypeInfo s when s == SimpleVerificationTypeInfo.INTEGER -> kind = TypeKind.INT;
                case SimpleVerificationTypeInfo s when s == SimpleVerificationTypeInfo.LONG -> kind = TypeKind.LONG;
                case SimpleVerificationTypeInfo s when s == SimpleVerificationTypeInfo.FLOAT -> kind = TypeKind.FLOAT;
                case SimpleVerificationTypeInfo s when s == SimpleVerificationTypeInfo.DOUBLE -> kind = TypeKind.DOUBLE;
                case ObjectVerificationTypeInfo o -> {
                    kind = TypeKind.REFERENCE;
                    type = o.classSymbol();
                }
                default -> throw new CodegenException(-1, "Cannot keep " + t + " across a suspension point in "
                        + owner.internalName + "." + method.name());
            }
            int n = counts.merge(kind, 1, Integer::sum) - 1;
            return new Spill(kind, fieldPrefix(kind) + "$" + n, type, false);
        }

        /**
         * Finds or creates the continuation, as kotlinc: the one passed if
         * it is this function's being resumed, else a new one; then goes to
         * where its label says.
         */
        private void prologue(CodeBuilder b, Label first, List<Label> resumes) {
            int completion = method.completionSlot();
            int cont = method.continuationSlot();
            Label create = b.newLabel();
            Label resumed = b.newLabel();
            b.aload(completion).instanceOf(continuation).ifeq(create);
            b.aload(completion).checkcast(continuation).astore(cont);
            b.aload(cont).getfield(continuation, "label", ConstantDescs.CD_int).ldc(MIN_VALUE).iand().ifeq(create);
            b.aload(cont).dup().getfield(continuation, "label", ConstantDescs.CD_int).ldc(MIN_VALUE).isub()
                    .putfield(continuation, "label", ConstantDescs.CD_int);
            b.goto_(resumed);
            b.labelBinding(create);
            b.new_(continuation).dup();
            if (!method.isStatic()) b.aload(0);
            b.aload(completion).checkcast(TypeMapper.CONTINUATION);
            b.invokespecial(continuation, "<init>", constructorType());
            b.astore(cont);
            b.labelBinding(resumed);
            b.aload(cont).getfield(continuation, "result", TypeMapper.OBJECT).astore(resultSlot);
            b.invokestatic(INTRINSICS, "getCOROUTINE_SUSPENDED", MethodTypeDesc.of(TypeMapper.OBJECT));
            b.astore(suspendedSlot);
            Label illegal = b.newLabel();
            List<SwitchCase> cases = new ArrayList<>();
            cases.add(SwitchCase.of(0, first));
            for (int i = 0; i < resumes.size(); i++) cases.add(SwitchCase.of(i + 1, resumes.get(i)));
            b.aload(cont).getfield(continuation, "label", ConstantDescs.CD_int);
            b.tableswitch(0, resumes.size(), illegal, cases);
            b.labelBinding(illegal);
            b.new_(ILLEGAL_STATE).dup().ldc("call to 'resume' before 'invoke' with coroutine");
            b.invokespecial(ILLEGAL_STATE, "<init>", MethodTypeDesc.of(TypeMapper.VOID, TypeMapper.STRING));
            b.athrow();
        }

        /** Throws the exception the continuation was resumed with, if it was resumed with a failure. */
        private void rethrowFailure(CodeBuilder b) {
            Label success = b.newLabel();
            b.aload(resultSlot).instanceOf(FAILURE).ifeq(success);
            b.aload(resultSlot).checkcast(FAILURE).getfield(FAILURE, "exception", ConstantDescs.CD_Throwable);
            b.athrow();
            b.labelBinding(success);
        }

        /**
         * A suspension point: saves the locals and the stack under the
         * arguments, makes the call, and returns if it suspended; resuming,
         * restores them and continues with the value resumed with.
         */
        private void suspensionPoint(CodeBuilder b, Point p) {
            int cont = method.continuationSlot();
            List<VerificationTypeInfo> stack = p.stackTypes;
            int[] temps = new int[stack.size()];
            boolean spillStack = !p.stack.isEmpty();
            if (spillStack) {
                int slot = tempSlot;
                for (int i = 0; i < stack.size(); i++) {
                    temps[i] = slot;
                    slot += isWide(stack.get(i)) ? 2 : 1;
                }
                for (int i = stack.size() - 1; i >= 0; i--) b.storeLocal(kind(stack.get(i)), temps[i]);
            }
            for (int i = 0; i < p.locals.size(); i++) {
                Spill s = p.locals.get(i);
                if (s.isNull()) continue;
                b.aload(cont).loadLocal(s.kind(), p.localSlots.get(i));
                b.putfield(continuation, s.field(), fieldType(s.kind()));
            }
            for (int i = 0; i < p.stack.size(); i++) {
                Spill s = p.stack.get(i);
                if (s.isNull()) continue;
                b.aload(cont).loadLocal(s.kind(), temps[i]);
                b.putfield(continuation, s.field(), fieldType(s.kind()));
            }
            b.aload(cont).loadConstant(p.label).putfield(continuation, "label", ConstantDescs.CD_int);
            if (spillStack) {
                for (int i = 0; i < stack.size(); i++) b.loadLocal(kind(stack.get(i)), temps[i]);
            }
            b.with(p.call);
            Label next = b.newLabel();
            b.dup().aload(suspendedSlot).if_acmpne(next).areturn();
            b.labelBinding(p.resume);
            for (int i = 0; i < p.locals.size(); i++) {
                Spill s = p.locals.get(i);
                load(b, s);
                b.storeLocal(s.kind(), p.localSlots.get(i));
            }
            for (Spill s : p.stack) load(b, s);
            b.labelBinding(p.rethrow);
            rethrowFailure(b);
            b.aload(resultSlot);
            b.labelBinding(next);
        }

        private void load(CodeBuilder b, Spill s) {
            if (s.isNull()) {
                b.aconst_null();
                return;
            }
            b.aload(method.continuationSlot()).getfield(continuation, s.field(), fieldType(s.kind()));
            if (s.type() != null && !s.type().equals(TypeMapper.OBJECT)) b.checkcast(s.type());
        }

        /**
         * An exception handler, with its ranges split around the code that
         * restores the locals, which is reached from the start of the method
         * with none of them, as kotlinc's splitTryCatchBlocksContainingSuspensionPoint.
         */
        private void splitCatch(CodeBuilder b, ExceptionCatch c, List<Point> points) {
            int startBci = code.labelToBci(c.tryStart());
            int endBci = code.labelToBci(c.tryEnd());
            Label start = c.tryStart();
            for (Point p : points) {
                if (p.bci < startBci || p.bci >= endBci) continue;
                b.exceptionCatch(start, p.resume, c.handler(), c.catchType());
                start = p.rethrow;
            }
            b.exceptionCatch(start, c.tryEnd(), c.handler(), c.catchType());
        }

        private MethodTypeDesc constructorType() {
            return method.isStatic() ? MethodTypeDesc.of(TypeMapper.VOID, TypeMapper.CONTINUATION)
                    : MethodTypeDesc.of(TypeMapper.VOID, owner.desc, TypeMapper.CONTINUATION);
        }

        /**
         * The continuation class: its result and label, the fields spilled
         * values are kept in, and invokeSuspend calling the function again
         * with it, as kotlinc's generated ContinuationImpl subclasses.
         */
        private void continuationClass() {
            String name = continuation.descriptorString();
            String internalName = name.substring(1, name.length() - 1);
            backend.syntheticSupers.put(internalName, CONTINUATION_IMPL);
            MethodTypeDesc invokeSuspend = invokeSuspendType();
            byte[] bytes = ClassFile.of().build(continuation, cb -> {
                cb.withVersion(ClassFile.JAVA_17_VERSION, 0);
                cb.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_SYNTHETIC);
                cb.withSuperclass(CONTINUATION_IMPL);
                cb.withField("result", TypeMapper.OBJECT, 0);
                cb.withField("label", ConstantDescs.CD_int, 0);
                fieldCounts.forEach((kind, n) -> {
                    for (int i = 0; i < n; i++) cb.withField(fieldPrefix(kind) + "$" + i, fieldType(kind), 0);
                });
                if (!method.isStatic()) cb.withField("this$0", owner.desc, ClassFile.ACC_FINAL);
                cb.withMethodBody("<init>", constructorType(), 0, b -> {
                    int completion = 1;
                    if (!method.isStatic()) {
                        b.aload(0).aload(1).putfield(continuation, "this$0", owner.desc);
                        completion = 2;
                    }
                    b.aload(0).aload(completion);
                    b.invokespecial(CONTINUATION_IMPL, "<init>",
                            MethodTypeDesc.of(TypeMapper.VOID, TypeMapper.CONTINUATION));
                    b.return_();
                });
                cb.withMethodBody("invokeSuspend", invokeSuspend, ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, b -> {
                    b.aload(0);
                    if (invokeSuspend.parameterType(0).equals(RESULT)) {
                        // Result is boxed: its value, or null for none.
                        Label isNull = b.newLabel();
                        Label done = b.newLabel();
                        b.aload(1).ifnull(isNull);
                        b.aload(1).invokevirtual(RESULT, resultValueGetter(), MethodTypeDesc.of(TypeMapper.OBJECT));
                        b.goto_(done);
                        b.labelBinding(isNull);
                        b.aconst_null();
                        b.labelBinding(done);
                    } else {
                        b.aload(1);
                    }
                    b.putfield(continuation, "result", TypeMapper.OBJECT);
                    b.aload(0).dup().getfield(continuation, "label", ConstantDescs.CD_int).ldc(MIN_VALUE).ior()
                            .putfield(continuation, "label", ConstantDescs.CD_int);
                    if (!method.isStatic()) b.aload(0).getfield(continuation, "this$0", owner.desc);
                    List<ClassDesc> params = method.type().parameterList();
                    for (int i = 0; i < params.size() - 1; i++) pushZero(b, params.get(i));
                    b.aload(0);
                    if (method.isStatic()) {
                        b.invokestatic(owner.desc, method.name(), method.type(), owner.isInterface);
                    } else if (owner.isInterface) {
                        b.invokeinterface(owner.desc, method.name(), method.type());
                    } else {
                        b.invokevirtual(owner.desc, method.name(), method.type());
                    }
                    b.areturn();
                });
                if (owner.nestHost != null) backend.nests.member(owner.nestHost, continuation, cb);
            });
            backend.output.put(internalName, bytes);
        }
    }

    private MethodTypeDesc invokeSuspendType() {
        ClassSymbol base = backend.resolver.table().requireClass("kotlin.coroutines.jvm.internal.BaseContinuationImpl");
        FunctionSymbol f = base.members().functions("invokeSuspend").getFirst();
        return backend.types.methodType(f);
    }

    private String resultValueGetter() {
        ClassSymbol result = backend.resolver.table().requireClass("kotlin.Result");
        PropertySymbol value = result.members().properties("value").getFirst();
        return backend.names.getterName(value);
    }

    private static void pushZero(CodeBuilder b, ClassDesc type) {
        if (!type.isPrimitive()) {
            b.aconst_null();
            return;
        }
        switch (TypeKind.from(type)) {
            case LONG -> b.lconst_0();
            case FLOAT -> b.fconst_0();
            case DOUBLE -> b.dconst_0();
            default -> b.iconst_0();
        }
    }

    private static boolean isWide(VerificationTypeInfo t) {
        return t == SimpleVerificationTypeInfo.LONG || t == SimpleVerificationTypeInfo.DOUBLE;
    }

    private static TypeKind kind(VerificationTypeInfo t) {
        if (t == SimpleVerificationTypeInfo.INTEGER) return TypeKind.INT;
        if (t == SimpleVerificationTypeInfo.LONG) return TypeKind.LONG;
        if (t == SimpleVerificationTypeInfo.FLOAT) return TypeKind.FLOAT;
        if (t == SimpleVerificationTypeInfo.DOUBLE) return TypeKind.DOUBLE;
        if (t == SimpleVerificationTypeInfo.NULL || t instanceof ObjectVerificationTypeInfo) return TypeKind.REFERENCE;
        throw new CodegenException(-1, "Cannot keep " + t + " across a suspension point");
    }

    /** The prefix of the fields that keep values of the kind, as kotlinc's L$0, I$0, J$0, F$0, D$0. */
    private static String fieldPrefix(TypeKind kind) {
        return switch (kind) {
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            default -> "L";
        };
    }

    private static ClassDesc fieldType(TypeKind kind) {
        return switch (kind) {
            case INT -> ConstantDescs.CD_int;
            case LONG -> ConstantDescs.CD_long;
            case FLOAT -> ConstantDescs.CD_float;
            case DOUBLE -> ConstantDescs.CD_double;
            default -> TypeMapper.OBJECT;
        };
    }
}
