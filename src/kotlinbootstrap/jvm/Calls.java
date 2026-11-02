package kotlinbootstrap.jvm;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Opcode;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.check.TExpr;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.PackageSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Types;

/**
 * Calls of functions, constructors and accessors, as kotlinc's
 * IrCallGenerator: the receivers and arguments in the order of the JVM
 * method's parameters, evaluated in source order, and for parameters
 * left to their defaults, the {@code $default} method with a mask of
 * them.
 */
final class Calls {
    static final ClassDesc DEFAULT_CONSTRUCTOR_MARKER =
            ClassDesc.ofInternalName("kotlin/jvm/internal/DefaultConstructorMarker");
    private static final ClassDesc ENUM = ClassDesc.ofInternalName("java/lang/Enum");
    private static final MethodTypeDesc ENUM_INIT =
            MethodTypeDesc.of(TypeMapper.VOID, TypeMapper.STRING, ConstantDescs.CD_int);

    /** A JVM method to invoke. */
    record Target(Opcode opcode, ClassDesc owner, String name, MethodTypeDesc type, boolean isInterface) {
    }

    private final ExprCodegen gen;
    private final CodeBuilder code;
    private final JvmBackend backend;

    Calls(ExprCodegen gen) {
        this.gen = gen;
        this.code = gen.code;
        this.backend = gen.backend;
    }

    ClassDesc call(TExpr.Call c) {
        FunctionSymbol f = c.function();
        if (f.owner() == null) {
            return new Lambdas(gen).callLocalFunction(c);
        }
        if (f.isConstructor()) return construct(c);
        // A SAM constructor converts its function argument.
        if (f.origin() == Origin.SYNTHETIC && f.source() instanceof ClassSymbol && c.args().size() == 1) {
            return new Lambdas(gen).samValue(c.args().getFirst().value(), c.type());
        }
        // A function type's invoke with an extension receiver passes it as the first argument.
        if (c.extensionReceiver() != null && f.signature().receiver() == null) {
            List<TExpr.Argument> args = new ArrayList<>();
            args.add(new TExpr.Argument(0, c.extensionReceiver()));
            for (TExpr.Argument a : c.args()) args.add(new TExpr.Argument(a.param() + 1, a.value()));
            return invoke(f, c.dispatchReceiver(), c.contextArgs(), null, args, c.superCall());
        }
        return invoke(f, c.dispatchReceiver(), c.contextArgs(), c.extensionReceiver(), c.args(), c.superCall());
    }

    // ==== Targets ====

    /** The class to invoke a member in: the receiver's class, which may inherit it, or the declaring class. */
    ClassSymbol memberOwner(CallableSymbol s, TExpr dispatchReceiver) {
        ClassSymbol declaring = (ClassSymbol) s.owner();
        if (TypeMapper.isClass(declaring, "kotlin.Any")) return declaring;
        if (dispatchReceiver != null && receiverClass(dispatchReceiver.type()) instanceof ClassSymbol r
                && r != declaring && TypeMapper.primitive(r) == null && TypeMapper.primitiveArrayElement(r) == null
                && !TypeMapper.isClass(r, "kotlin.Array") && !TypeMapper.isClass(r, "kotlin.Nothing")) {
            return r;
        }
        return declaring;
    }

    private static ClassSymbol receiverClass(KType t) {
        KType lower = Types.lowerBound(t);
        if (lower instanceof KType.DefinitelyNotNull d) lower = d.inner();
        return lower instanceof KType.ClassType c ? c.symbol() : null;
    }

    /** Whether a member is compiled to a static method: Java statics, and @JvmStatic members of objects. */
    static boolean isStatic(CallableSymbol s) {
        if (s.owner() instanceof PackageSymbol || s.isStatic()) return true;
        return s.owner() instanceof ClassSymbol cls && cls.isObject() && !cls.has(Modifier.COMPANION)
                && JvmNames.has(s instanceof PropertySymbol p ? JvmNames.modifiers(p) : functionModifiers(s),
                "JvmStatic");
    }

    private static kotlinbootstrap.ast.Modifiers functionModifiers(CallableSymbol s) {
        return s.source() instanceof kotlinbootstrap.ast.Decl.Function d ? d.modifiers()
                : kotlinbootstrap.ast.Modifiers.EMPTY;
    }

    /** The class top-level declarations are called in: the facade, or for a private one, its file's part. */
    ClassDesc topLevelOwner(CallableSymbol s) {
        FileContext file = backend.names.file(s);
        if (file == null) throw new CodegenException(-1, "No file for " + s);
        String name = s.visibility() == Visibility.PRIVATE ? backend.names.filePart(file) : backend.names.facade(file);
        return ClassDesc.ofInternalName(name);
    }

    private Target target(CallableSymbol s, String name, MethodTypeDesc type, TExpr dispatchReceiver,
                          boolean superCall) {
        if (s.owner() instanceof PackageSymbol) {
            return new Target(Opcode.INVOKESTATIC, topLevelOwner(s), name, type, false);
        }
        ClassSymbol declaring = (ClassSymbol) s.owner();
        if (isStatic(s)) {
            return new Target(Opcode.INVOKESTATIC, gen.types.classDesc(declaring), name, type,
                    declaring.isInterface());
        }
        ClassSymbol owner = superCall ? superClass(dispatchReceiver, declaring) : memberOwner(s, dispatchReceiver);
        boolean isInterface = owner.isInterface() && !TypeMapper.isClass(owner, "kotlin.Any");
        ClassDesc ownerDesc = TypeMapper.isClass(owner, "kotlin.Any") ? TypeMapper.OBJECT : gen.types.classDesc(owner);
        Opcode opcode = superCall ? Opcode.INVOKESPECIAL : isInterface ? Opcode.INVOKEINTERFACE : Opcode.INVOKEVIRTUAL;
        return new Target(opcode, ownerDesc, name, type, isInterface);
    }

    private static ClassSymbol superClass(TExpr dispatchReceiver, ClassSymbol declaring) {
        ClassSymbol r = dispatchReceiver != null ? receiverClass(dispatchReceiver.type()) : null;
        return r != null ? r : declaring;
    }

    Target functionTarget(FunctionSymbol f, TExpr dispatchReceiver, boolean superCall) {
        return target(f, backend.names.functionName(f), gen.types.methodType(f), dispatchReceiver, superCall);
    }

    // ==== Calls ====

    /** Invokes a function with the receivers and arguments; returns the type it leaves. */
    ClassDesc invoke(FunctionSymbol f, TExpr dispatchReceiver, List<TExpr> contextArgs, TExpr extensionReceiver,
                     List<TExpr.Argument> args, boolean superCall) {
        Target target = functionTarget(f, dispatchReceiver, superCall);
        boolean hasDispatch = target.opcode() != Opcode.INVOKESTATIC;
        List<ClassDesc> jvmParams = target.type().parameterList();
        List<ValueParamSymbol> params = f.signature().params();
        int first = jvmParams.size() - params.size();
        if (f.source() instanceof javax.lang.model.element.ExecutableElement) first = 0;
        int[] mask = defaultMask(f, args);
        if (mask != null) {
            Target defaults = defaultTarget(f, dispatchReceiver, superCall);
            List<ClassDesc> defaultParams = defaults.type().parameterList();
            int offset = hasDispatch ? 1 : 0;
            Operands ops = new Operands();
            if (hasDispatch) ops.add(dispatchReceiver, defaultParams.getFirst());
            addReceivers(ops, f, contextArgs, extensionReceiver, defaultParams, offset);
            addArgs(ops, params, args, defaultParams, offset + first);
            ops.generate();
            for (int m : mask) code.loadConstant(m);
            code.aconst_null();
            emit(defaults);
            return defaults.type().returnType();
        }
        Operands ops = new Operands();
        if (hasDispatch) ops.add(dispatchReceiver, receiverType(target));
        if (f.source() instanceof javax.lang.model.element.ExecutableElement) {
            addJavaArgs(ops, params, args, jvmParams);
        } else {
            addReceivers(ops, f, contextArgs, extensionReceiver, jvmParams, 0);
            addArgs(ops, params, args, jvmParams, first);
        }
        ops.generate();
        emit(target);
        return target.type().returnType();
    }

    /** The type a receiver is passed as: for a super call, the class it is in, as invokespecial requires. */
    private ClassDesc receiverType(Target t) {
        return t.opcode() == Opcode.INVOKESPECIAL ? gen.owner.desc : t.owner();
    }

    void emit(Target t) {
        switch (t.opcode()) {
            case INVOKESTATIC -> code.invokestatic(t.owner(), t.name(), t.type(), t.isInterface());
            case INVOKESPECIAL -> code.invokespecial(t.owner(), t.name(), t.type(), t.isInterface());
            case INVOKEINTERFACE -> code.invokeinterface(t.owner(), t.name(), t.type());
            default -> code.invokevirtual(t.owner(), t.name(), t.type());
        }
    }

    private void addReceivers(Operands ops, CallableSymbol f, List<TExpr> contextArgs, TExpr extensionReceiver,
                              List<ClassDesc> jvmParams, int offset) {
        int i = offset;
        for (TExpr c : contextArgs) ops.add(c, jvmParams.get(i++));
        if (extensionReceiver != null) ops.add(extensionReceiver, jvmParams.get(i));
    }

    /** The value parameters' arguments, in order, with zero values for those missing. */
    private void addArgs(Operands ops, List<ValueParamSymbol> params, List<TExpr.Argument> args,
                         List<ClassDesc> jvmParams, int first) {
        TExpr[] byParam = new TExpr[params.size()];
        int[] sourceIndex = new int[params.size()];
        int n = 0;
        for (TExpr.Argument a : args) {
            byParam[a.param()] = a.value();
            sourceIndex[a.param()] = n++;
        }
        for (int i = 0; i < params.size(); i++) {
            ClassDesc type = jvmParams.get(first + i);
            if (byParam[i] != null) {
                ops.addArg(byParam[i], type, sourceIndex[i]);
            } else if (params.get(i).isVararg() && !params.get(i).hasDefault()) {
                ops.addEmptyArray(type);
            } else {
                ops.addZero(type);
            }
        }
    }

    /** Java methods take their arguments as declared; a vararg's elements, as an array. */
    private void addJavaArgs(Operands ops, List<ValueParamSymbol> params, List<TExpr.Argument> args,
                             List<ClassDesc> jvmParams) {
        addArgs(ops, params, args, jvmParams, jvmParams.size() - params.size());
    }

    // ==== Defaults ====

    /** The mask of the parameters left to their default values, by bit; null if there are none. */
    private static int[] defaultMask(FunctionSymbol f, List<TExpr.Argument> args) {
        if (f.source() instanceof javax.lang.model.element.ExecutableElement) return null;
        List<ValueParamSymbol> params = f.signature().params();
        boolean[] given = new boolean[params.size()];
        for (TExpr.Argument a : args) given[a.param()] = true;
        int[] mask = new int[Math.max(1, (params.size() + 31) / 32)];
        boolean any = false;
        for (int i = 0; i < params.size(); i++) {
            if (given[i] || params.get(i).isVararg() && !Overrides.hasDefaultValue(f, i)) continue;
            if (Overrides.hasDefaultValue(f, i)) {
                mask[i / 32] |= 1 << (i % 32);
                any = true;
            }
        }
        return any ? mask : null;
    }

    /** The function whose declaration has the default values: the function, or one it overrides. */
    static FunctionSymbol declaringDefaults(FunctionSymbol f) {
        for (ValueParamSymbol p : f.signature().params()) if (p.hasDefault()) return f;
        for (CallableSymbol o : Overrides.overridden(f)) {
            if (o instanceof FunctionSymbol of) {
                FunctionSymbol d = declaringDefaults(of);
                if (d != null) return d;
            }
        }
        return null;
    }

    /** The {@code $default} method: static, with the receiver first, then the masks and a marker. */
    private Target defaultTarget(FunctionSymbol f, TExpr dispatchReceiver, boolean superCall) {
        FunctionSymbol d = f.isConstructor() ? f : declaringDefaults(f);
        if (d == null) d = f;
        MethodTypeDesc type = defaultMethodType(backend, d);
        if (f.isConstructor()) {
            return new Target(Opcode.INVOKESPECIAL, gen.types.classDesc((ClassSymbol) f.owner()), "<init>", type,
                    false);
        }
        ClassDesc owner = d.owner() instanceof PackageSymbol ? topLevelOwner(d)
                : gen.types.classDesc((ClassSymbol) d.owner());
        boolean isInterface = d.owner() instanceof ClassSymbol cls && cls.isInterface();
        return new Target(Opcode.INVOKESTATIC, owner, backend.names.functionName(d) + "$default", type, isInterface);
    }

    /** The descriptor of a function's {@code $default} method, or a constructor's with defaults. */
    static MethodTypeDesc defaultMethodType(JvmBackend backend, FunctionSymbol f) {
        List<ClassDesc> params = new ArrayList<>();
        if (!f.isConstructor() && !isStatic(f)) params.add(backend.types.classDesc((ClassSymbol) f.owner()));
        params.addAll(backend.types.parameterTypes(f));
        int masks = Math.max(1, (f.signature().params().size() + 31) / 32);
        for (int i = 0; i < masks; i++) params.add(ConstantDescs.CD_int);
        params.add(f.isConstructor() ? DEFAULT_CONSTRUCTOR_MARKER : TypeMapper.OBJECT);
        return MethodTypeDesc.of(backend.types.mapReturnType(f), params);
    }

    // ==== Constructors ====

    private ClassDesc construct(TExpr.Call c) {
        FunctionSymbol k = c.function();
        ClassSymbol cls = (ClassSymbol) k.owner();
        ClassDesc desc = gen.types.classDesc(cls);
        Operands ops = new Operands();
        List<ClassDesc> prefix = new ArrayList<>();
        ClassCodegen local = backend.generator(cls);
        if (local != null && cls.isLocal()) {
            for (ExprCodegen.Capture capture : local.captures()) {
                ops.addCapture(capture);
                prefix.add(capture.jvmType());
            }
        } else if (cls.isInner() && cls.outer() != null) {
            ClassDesc outer = gen.types.classDesc(cls.outer());
            ops.add(c.dispatchReceiver(), outer);
            prefix.add(outer);
        }
        int[] mask = defaultMask(k, c.args());
        MethodTypeDesc type = mask != null ? defaultMethodType(backend, k) : gen.types.methodType(k);
        if (!prefix.isEmpty()) type = type.insertParameterTypes(0, prefix.toArray(ClassDesc[]::new));
        List<ClassDesc> jvmParams = type.parameterList();
        int first = prefix.size() + k.signature().contextParams().size();
        if (k.source() instanceof javax.lang.model.element.ExecutableElement) first = 0;
        addArgs(ops, k.signature().params(), c.args(), jvmParams, first);
        // Arguments that need an empty stack are evaluated before the new instance.
        ops.spillIfNeeded(true);
        code.new_(desc).dup();
        ops.generate();
        if (mask != null) {
            for (int m : mask) code.loadConstant(m);
            code.aconst_null();
        }
        code.invokespecial(desc, "<init>", type);
        return desc;
    }

    /**
     * {@code this(...)} or {@code super(...)} in a constructor of a class:
     * the target's prefix, from the constructor's own (an enum's name and
     * ordinal), the outer instance or the target's captures, then the
     * arguments.
     */
    void delegatingConstructorCall(TExpr.DelegatingConstructorCall d, ClassCodegen owner) {
        FunctionSymbol k = d.constructor();
        ClassSymbol target = (ClassSymbol) k.owner();
        if (TypeMapper.isClass(target, "kotlin.Any")) {
            code.aload(0).invokespecial(TypeMapper.OBJECT, "<init>", MethodTypeDesc.of(TypeMapper.VOID));
            return;
        }
        if (TypeMapper.isClass(target, "kotlin.Enum")) {
            code.aload(0).aload(1).iload(2).invokespecial(ENUM, "<init>", ENUM_INIT);
            return;
        }
        Operands ops = new Operands();
        List<ClassDesc> prefix = new ArrayList<>();
        if (target.kind() == ClassKind.ENUM_CLASS) {
            ops.addLocal(TypeMapper.STRING, 1);
            ops.addLocal(ConstantDescs.CD_int, 2);
            prefix.add(TypeMapper.STRING);
            prefix.add(ConstantDescs.CD_int);
        }
        ClassCodegen local = backend.generator(target);
        if (local != null && target.isLocal()) {
            for (ExprCodegen.Capture capture : local.captures()) {
                ops.addCapture(capture);
                prefix.add(capture.jvmType());
            }
        } else if (target.isInner() && target.outer() != null) {
            ClassDesc outer = gen.types.classDesc(target.outer());
            if (d.outerReceiver() != null) ops.add(d.outerReceiver(), outer);
            else ops.addThis(target.outer(), outer);
            prefix.add(outer);
        }
        ClassDesc desc = gen.types.classDesc(target);
        MethodTypeDesc type = constructorType(k, d.args(), prefix);
        addConstructorArgs(ops, k, d.args(), type, prefix.size());
        ops.spillIfNeeded(true);
        code.aload(0);
        ops.generate();
        pushDefaultMask(k, d.args());
        code.invokespecial(desc, "<init>", type);
    }

    /** An enum entry without a body: its class's constructor with the name and ordinal first. */
    void enumEntry(TExpr.DelegatingConstructorCall entry, ClassCodegen owner, String name, int ordinal) {
        FunctionSymbol k = entry.constructor();
        List<ClassDesc> prefix = List.of(TypeMapper.STRING, ConstantDescs.CD_int);
        MethodTypeDesc type = constructorType(k, entry.args(), prefix);
        Operands ops = new Operands();
        addConstructorArgs(ops, k, entry.args(), type, prefix.size());
        ops.spillIfNeeded(true);
        code.new_(owner.desc).dup().ldc(name).loadConstant(ordinal);
        ops.generate();
        pushDefaultMask(k, entry.args());
        code.invokespecial(owner.desc, "<init>", type);
    }

    /** A constructor's descriptor with a prefix: the one with defaults if arguments are left to them. */
    private MethodTypeDesc constructorType(FunctionSymbol k, List<TExpr.Argument> args, List<ClassDesc> prefix) {
        MethodTypeDesc type = defaultMask(k, args) != null ? defaultMethodType(backend, k) : gen.types.methodType(k);
        return prefix.isEmpty() ? type : type.insertParameterTypes(0, prefix.toArray(ClassDesc[]::new));
    }

    private void addConstructorArgs(Operands ops, FunctionSymbol k, List<TExpr.Argument> args, MethodTypeDesc type,
                                    int prefix) {
        int first = prefix + k.signature().contextParams().size();
        if (k.source() instanceof javax.lang.model.element.ExecutableElement) {
            first = type.parameterCount() - k.signature().params().size();
        }
        addArgs(ops, k.signature().params(), args, type.parameterList(), first);
    }

    private void pushDefaultMask(FunctionSymbol k, List<TExpr.Argument> args) {
        int[] mask = defaultMask(k, args);
        if (mask == null) return;
        for (int m : mask) code.loadConstant(m);
        code.aconst_null();
    }

    // ==== Accessors ====

    /** Calls a property's getter, or its setter with the value; returns the type left. */
    ClassDesc invokeAccessor(PropertySymbol p, boolean getter, TExpr dispatchReceiver, List<TExpr> contextArgs,
                             TExpr extensionReceiver, TExpr value, boolean superCall) {
        if (isDirectFieldAccess(p) && extensionReceiver == null) {
            Members members = new Members(gen);
            if (getter) return members.fieldGet(p, dispatchReceiver);
            members.fieldSet(p, dispatchReceiver, value);
            return TypeMapper.VOID;
        }
        String name = getter ? backend.names.getterName(p) : backend.names.setterName(p);
        MethodTypeDesc type = getter ? gen.types.getterType(p) : gen.types.setterType(p);
        Target target = target(p, name, type, dispatchReceiver, superCall);
        List<ClassDesc> jvmParams = type.parameterList();
        Operands ops = new Operands();
        if (target.opcode() != Opcode.INVOKESTATIC) ops.add(dispatchReceiver, receiverType(target));
        addReceivers(ops, p, contextArgs, extensionReceiver, jvmParams, 0);
        if (!getter) ops.add(value, jvmParams.getLast());
        ops.generate();
        emit(target);
        return type.returnType();
    }

    /**
     * Whether a property is read and written through its field, as kotlinc
     * does for private properties with default accessors, which have none.
     */
    static boolean isDirectFieldAccess(PropertySymbol p) {
        if (p.visibility() != Visibility.PRIVATE || p.signature().receiver() != null) return false;
        if (p.owner() instanceof KotlinClassSymbol cls && cls.isInterface()) return false;
        return switch (p.source()) {
            case kotlinbootstrap.ast.Decl.Property d -> d.delegate() == null
                    && (d.getter() == null || d.getter().body() == null)
                    && (d.setter() == null || d.setter().body() == null)
                    && !d.modifiers().has(Modifier.LATEINIT) && !d.modifiers().has(Modifier.EXTERNAL)
                    && (d.initializer() != null || p.owner() != null) && !p.has(Modifier.ABSTRACT);
            case kotlinbootstrap.ast.Decl.Param param -> true;
            case null, default -> false;
        };
    }

    // ==== Operands ====

    /**
     * The values of a call's JVM parameters, in their order, evaluated in
     * source order: spilled to slots first if the order differs, or if a
     * later one needs an empty stack.
     */
    private final class Operands {
        private record Operand(TExpr expr, ClassDesc type, int sourceIndex, Object constant) {
        }

        /** A local variable slot's value, such as a constructor's parameter. */
        private record Local(int slot) {
        }

        /** The receiver of a class or function. */
        private record Receiver(Object owner) {
        }

        private static final Object ZERO = new Object();
        private static final Object EMPTY_ARRAY = new Object();
        private final List<Operand> operands = new ArrayList<>();
        private int argBase = -1;
        private ExprCodegen.Slot[] slots;

        void add(TExpr e, ClassDesc type) {
            operands.add(new Operand(e, type, -1, null));
        }

        void addCapture(ExprCodegen.Capture capture) {
            operands.add(new Operand(null, capture.jvmType(), -1, capture));
        }

        void addArg(TExpr e, ClassDesc type, int sourceIndex) {
            if (argBase < 0) argBase = operands.size();
            operands.add(new Operand(e, type, sourceIndex, null));
        }

        void addLocal(ClassDesc type, int slot) {
            operands.add(new Operand(null, type, -1, new Local(slot)));
        }

        void addThis(Object owner, ClassDesc type) {
            operands.add(new Operand(null, type, -1, new Receiver(owner)));
        }

        void addZero(ClassDesc type) {
            operands.add(new Operand(null, type, -1, ZERO));
        }

        void addEmptyArray(ClassDesc type) {
            operands.add(new Operand(null, type, -1, EMPTY_ARRAY));
        }

        private boolean inSourceOrder() {
            int last = -1;
            for (Operand o : operands) {
                if (o.sourceIndex() < 0) continue;
                if (o.sourceIndex() < last) return false;
                last = o.sourceIndex();
            }
            return true;
        }

        private boolean needsEmptyStack(boolean all) {
            for (int i = all ? 0 : 1; i < operands.size(); i++) {
                if (operands.get(i).expr() != null && gen.needsEmptyStack(operands.get(i).expr())) return true;
            }
            return false;
        }

        /** Spills the operands if needed: out of order, or for a later one needing an empty stack. */
        void spillIfNeeded(boolean anyNeedsEmptyStack) {
            if (slots != null) return;
            if (inSourceOrder() && !needsEmptyStack(anyNeedsEmptyStack)) return;
            slots = new ExprCodegen.Slot[operands.size()];
            // Receivers first, then the arguments in source order.
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < operands.size(); i++) if (operands.get(i).sourceIndex() < 0) order.add(i);
            List<Integer> args = new ArrayList<>();
            for (int i = 0; i < operands.size(); i++) if (operands.get(i).sourceIndex() >= 0) args.add(i);
            args.sort((a, b) -> Integer.compare(operands.get(a).sourceIndex(), operands.get(b).sourceIndex()));
            order.addAll(args);
            for (int i : order) {
                Operand o = operands.get(i);
                if (o.expr() != null) slots[i] = gen.spillAs(o.expr(), o.type());
            }
        }

        void generate() {
            spillIfNeeded(false);
            for (int i = 0; i < operands.size(); i++) {
                Operand o = operands.get(i);
                if (slots != null && slots[i] != null) {
                    gen.loadSlotValue(slots[i]);
                } else if (o.constant() == ZERO) {
                    gen.pushZero(o.type());
                } else if (o.constant() == EMPTY_ARRAY) {
                    gen.emptyArray(o.type());
                } else if (o.constant() instanceof Local l) {
                    code.loadLocal(java.lang.classfile.TypeKind.from(o.type()), l.slot());
                } else if (o.constant() instanceof Receiver r) {
                    gen.coerce(gen.thisValue(r.owner()), o.type());
                } else if (o.constant() instanceof ExprCodegen.Capture capture) {
                    gen.coerce(gen.loadForCapture(capture.key()), o.type());
                } else if (o.expr() instanceof TExpr.Vararg v) {
                    gen.coerce(gen.vararg(v, o.type()), o.type());
                } else {
                    gen.genAs(o.expr(), o.type());
                }
            }
        }
    }
}
