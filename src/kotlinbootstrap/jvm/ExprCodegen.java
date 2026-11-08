package kotlinbootstrap.jvm;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.check.CheckedFunction;
import kotlinbootstrap.check.LocalVariable;
import kotlinbootstrap.check.TExpr;
import kotlinbootstrap.check.TExprs;
import kotlinbootstrap.java.JavaTypeMapping;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.PackageSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.syntax.SourceFile;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeRenderer;
import kotlinbootstrap.types.Types;

/**
 * Generates the code of a JVM method from checked expressions. Values are
 * left on the operand stack with the JVM type {@link #gen} returns, and
 * converted where used ({@link #coerce}). Calls of inline functions with
 * bodies in the sources are inlined, with their lambda arguments, from
 * the checked bodies, in an environment of their own.
 */
final class ExprCodegen {
    static final ClassDesc INTRINSICS = ClassDesc.ofInternalName("kotlin/jvm/internal/Intrinsics");
    static final ClassDesc VOID = TypeMapper.VOID;
    private static final ClassDesc NPE = ClassDesc.of("java.lang.NullPointerException");
    static final ClassDesc OBJECT = TypeMapper.OBJECT;

    // ==== Bindings ====

    /** A local slot: a value, or for a variable changed by closures, its kotlin.jvm.internal.Ref. */
    record Slot(int slot, ClassDesc type, boolean ref) {
    }

    /** A lambda or reference passed to an inline function, inlined where it is invoked, in its own environment. */
    record InlineLambda(TExpr fn, Env env) {
    }

    record LoopLabels(Label breakLabel, Label continueLabel, int finallyDepth) {
    }

    /**
     * A value a closure captures: a variable, its Ref if closures change it,
     * or a receiver, by its owner.
     */
    record Capture(Object key, ClassDesc type, boolean ref) {
        ClassDesc jvmType() {
            return ref ? Refs.refClass(type) : type;
        }
    }

    /** The end of an inlined function or lambda, where its returns go with their value in the slot. */
    record ReturnTarget(Label end, int slot, ClassDesc type, int finallyDepth) {
    }

    /**
     * What names mean: variables, receivers by owner, loops, inlined
     * functions; and the type arguments of reified type parameters.
     * Inlined bodies get an environment of their own.
     */
    static final class Env {
        final Env parent;
        final Map<Object, Object> bindings = new IdentityHashMap<>();
        final Map<Object, ReturnTarget> returns = new IdentityHashMap<>();
        Substitution reified;

        Env(Env parent) {
            this.parent = parent;
            this.reified = parent != null ? parent.reified : null;
        }

        Object lookup(Object key) {
            for (Env e = this; e != null; e = e.parent) {
                Object b = e.bindings.get(key);
                if (b != null) return b;
            }
            return null;
        }

        void bind(Object key, Object binding) {
            bindings.put(key, binding);
        }

        /** Where returns to an inlined function or lambda go, apart from its receiver bound to it. */
        void bindReturn(CheckedFunction function, ReturnTarget target) {
            returns.put(function, target);
        }

        ReturnTarget lookupReturn(Object function) {
            for (Env e = this; e != null; e = e.parent) {
                ReturnTarget t = e.returns.get(function);
                if (t != null) return t;
            }
            return null;
        }
    }

    /** A finally block to run before jumping out of its try, with the exception ranges it splits. */
    static final class Finally {
        final TExpr block;
        final Env env;
        final List<Label[]> ranges = new ArrayList<>();
        Label start;

        Finally(TExpr block, Env env, Label start) {
            this.block = block;
            this.env = env;
            this.start = start;
        }
    }

    final ClassCodegen owner;
    final JvmBackend backend;
    final TypeMapper types;
    final CodeBuilder code;
    final boolean isStatic;
    /** The function whose returns are returns of this method; null for initializers. */
    final CheckedFunction function;
    final String methodName;
    final ClassDesc returnType;
    final SourceFile source;
    private int nextSlot;
    Env env = new Env(null);
    private final List<Finally> finallies = new ArrayList<>();
    private final Set<LocalVariable> boxed = Collections.newSetFromMap(new IdentityHashMap<>());
    private int inlineDepth;
    private int lastLine = -1;
    private final Intrinsics intrinsics;
    /** In the body of a suspend function, the slot of the continuation its suspend calls pass; else -1. */
    private int continuationSlot = -1;

    ExprCodegen(ClassCodegen owner, CodeBuilder code, boolean isStatic, CheckedFunction function, String methodName,
                ClassDesc returnType) {
        this.owner = owner;
        this.backend = owner.backend;
        this.types = backend.types;
        this.code = code;
        this.isStatic = isStatic;
        this.function = function;
        this.methodName = methodName;
        this.returnType = returnType;
        this.source = owner.file != null ? owner.file.file().source() : null;
        this.nextSlot = isStatic ? 0 : 1;
        this.intrinsics = new Intrinsics(this);
    }

    // ==== Slots ====

    int newSlot(ClassDesc type) {
        int slot = nextSlot;
        nextSlot += type.equals(VOID) ? 0 : TypeKind.from(type).slotSize();
        return slot;
    }

    /** A parameter, in order: a slot bound to the key. */
    Slot parameter(Object key, ClassDesc type) {
        Slot s = new Slot(newSlot(type), type, false);
        if (key != null) env.bind(key, s);
        return s;
    }

    /** Binds what a closure captures to its first parameters. */
    void bindCaptures(List<Capture> captures) {
        for (Capture c : captures) {
            Slot s = new Slot(newSlot(c.jvmType()), c.type(), c.ref());
            env.bind(c.key(), s);
        }
    }

    /**
     * The parameters of a closure's method as erased by its interface,
     * converted to their types in new slots.
     */
    List<Slot> erasedParameters(List<ClassDesc> erased, List<KType> types) {
        List<Slot> params = new ArrayList<>();
        for (ClassDesc t : erased) params.add(new Slot(newSlot(t), t, false));
        List<Slot> result = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            Slot p = params.get(i);
            ClassDesc type = i < types.size() ? mapType(types.get(i)) : p.type();
            if (type.equals(p.type())) {
                result.add(p);
                continue;
            }
            loadSlot(p.type(), p.slot());
            coerce(p.type(), type);
            Slot s = new Slot(newSlot(type), type, false);
            store(type, s.slot());
            result.add(s);
        }
        return result;
    }

    /** What capturing a variable or receiver takes, as it is here. */
    Capture capture(Object key) {
        Object b = env.lookup(key);
        if (b == null && key instanceof LocalVariable v && v.param() != null) b = env.lookup(v.param());
        if (b instanceof Slot s) return new Capture(key, s.type(), s.ref());
        if (key instanceof LocalVariable v) {
            if (b instanceof InlineLambda) return new Capture(key, mapType(v.type()), false);
            Capture field = owner.capturedField(key);
            if (field != null) return field;
            return new Capture(key, mapType(v.type()), false);
        }
        if (key == owner.cls && !isStatic) return new Capture(key, owner.desc, false);
        Capture field = owner.capturedField(key);
        if (field != null) return field;
        if (key instanceof ClassSymbol c) return new Capture(key, types.classDesc(c), false);
        if (key instanceof CheckedFunction f && f.receiverType() != null) {
            return new Capture(key, mapType(f.receiverType()), false);
        }
        if (key instanceof CallableSymbol s && s.signature().receiver() != null) {
            return new Capture(key, mapType(s.signature().receiver()), false);
        }
        throw new CodegenException(-1, "Cannot capture " + key + " in " + owner.internalName);
    }

    /** Loads a captured value as a closure takes it: a variable's Ref itself if it has one. */
    ClassDesc loadForCapture(Object key) {
        Object b = env.lookup(key);
        if (b == null && key instanceof LocalVariable v && v.param() != null) b = env.lookup(v.param());
        if (b instanceof Slot s) {
            if (s.ref()) {
                code.aload(s.slot());
                return Refs.refClass(s.type());
            }
            loadSlot(s.type(), s.slot());
            return s.type();
        }
        if (key instanceof LocalVariable v) {
            if (b instanceof InlineLambda l) {
                // A crossinline lambda used in a closure: a function object.
                Env saved = env;
                env = l.env();
                ClassDesc t = new Lambdas(this).lambda((TExpr.Lambda) l.fn(), null);
                env = saved;
                return t;
            }
            ClassDesc t = owner.loadCaptured(this, key, true);
            if (t != null) return t;
            return loadLocal(v);
        }
        return thisValue(key);
    }

    /** Pushes the zero value of a type, null for references, as for a parameter left to its default. */
    void pushZero(ClassDesc type) {
        if (type.equals(VOID)) return;
        if (!type.isPrimitive()) {
            code.aconst_null();
            return;
        }
        pushDefault(type);
    }

    /**
     * Names the classes of the object literals in a body, before code that
     * mentions their types, such as a call of a member of one, is generated.
     */
    void nameObjects(TExpr e) {
        if (e == null) return;
        switch (e) {
            case TExpr.ObjectLiteral o -> {
                backend.localClass((KotlinClassSymbol) o.cls(), owner, methodName);
                Closures.children(e, this::nameObjects);
            }
            case TExpr.Lambda l -> nameObjects(l.function().body());
            default -> Closures.children(e, this::nameObjects);
        }
    }

    /** Marks the variables of a body that closures change, to keep them in Refs. */
    void scanBoxed(TExpr body) {
        if (body != null) boxed.addAll(backend.closures.boxedVariables(body));
    }

    void load(Slot s) {
        code.loadLocal(TypeKind.from(s.slot() >= 0 && s.ref() ? Refs.refClass(s.type()) : s.type()), s.slot());
    }

    private void store(ClassDesc type, int slot) {
        code.storeLocal(TypeKind.from(type), slot);
    }

    private void loadSlot(ClassDesc type, int slot) {
        code.loadLocal(TypeKind.from(type), slot);
    }

    // ==== Coroutines ====

    /**
     * Begins the body of a suspend function, whose continuation the slot
     * has: copied to the slot its suspend calls pass, which its state
     * machine, if it suspends, makes its own continuation (see {@link StateMachines}).
     */
    void beginSuspend(int completionSlot) {
        continuationSlot = newSlot(TypeMapper.CONTINUATION);
        code.aload(completionSlot).checkcast(TypeMapper.CONTINUATION).astore(continuationSlot);
        owner.stateMachine(completionSlot, continuationSlot);
    }

    void loadContinuation() {
        if (continuationSlot < 0) throw new CodegenException(-1, "Suspend call outside a suspend function");
        code.aload(continuationSlot);
    }

    /** Marks the call that follows as a suspension point, for the state machine. */
    void suspensionPoint() {
        Label next = code.newLabel();
        code.ldc(StateMachines.MARKER).ifnull(next).labelBinding(next);
    }

    // ==== Entry points ====

    /** Generates a function body and returns from it. */
    void body(TExpr body) {
        scanBoxed(body);
        nameObjects(body);
        ClassDesc t = gen(body, returnType);
        coerce(t, returnType);
        returnValue(returnType);
    }

    void returnValue(ClassDesc type) {
        if (type.equals(VOID)) code.return_();
        else code.return_(TypeKind.from(type));
    }

    void line(int pos) {
        if (inlineDepth > 0 || source == null || pos < 0) return;
        int line = source.line(pos);
        if (line != lastLine) {
            lastLine = line;
            code.lineNumber(line);
        }
    }

    // ==== Conversions ====

    void genAs(TExpr e, ClassDesc type) {
        coerce(gen(e, type), type);
    }

    /** Converts the value on the stack: boxing, unboxing, casts; Unit for no value, or popping it. */
    void coerce(ClassDesc from, ClassDesc to) {
        if (from.equals(to)) return;
        if (to.equals(VOID)) {
            pop(from);
            return;
        }
        if (from.equals(VOID)) {
            pushDefault(to);
            return;
        }
        if (from.isPrimitive() && to.isPrimitive()) {
            primitiveConversion(from, to);
            return;
        }
        if (from.isPrimitive()) {
            ClassDesc box = TypeMapper.box(from);
            ClassDesc unboxedTarget = TypeMapper.unbox(to);
            if (unboxedTarget != null && !unboxedTarget.equals(from)) {
                primitiveConversion(from, unboxedTarget);
                box = to;
                from = unboxedTarget;
            }
            code.invokestatic(box, "valueOf", MethodTypeDesc.of(box, from));
            checkcast(box, to);
            return;
        }
        if (to.isPrimitive()) {
            ClassDesc fromPrimitive = TypeMapper.unbox(from);
            if (fromPrimitive == null) {
                ClassDesc box = TypeMapper.box(to);
                // Numbers convert to other numbers, as for a Number smart cast to Int.
                if (!to.equals(ConstantDescs.CD_boolean) && !to.equals(ConstantDescs.CD_char)) {
                    box = ConstantDescs.CD_Number;
                }
                code.checkcast(box);
                unboxFrom(box, to);
                return;
            }
            unboxFrom(from, fromPrimitive);
            primitiveConversion(fromPrimitive, to);
            return;
        }
        checkcast(from, to);
    }

    private void checkcast(ClassDesc from, ClassDesc to) {
        if (to.equals(OBJECT) || from.equals(to)) return;
        if (TypeMapper.unbox(from) != null && (to.equals(ConstantDescs.CD_Number)
                || to.descriptorString().equals("Ljava/lang/Comparable;")
                || to.descriptorString().equals("Ljava/io/Serializable;"))) {
            return;
        }
        code.checkcast(to);
    }

    private void unboxFrom(ClassDesc box, ClassDesc primitive) {
        String name = switch (primitive.descriptorString()) {
            case "Z" -> "booleanValue";
            case "C" -> "charValue";
            case "B" -> "byteValue";
            case "S" -> "shortValue";
            case "I" -> "intValue";
            case "J" -> "longValue";
            case "F" -> "floatValue";
            default -> "doubleValue";
        };
        code.invokevirtual(box, name, MethodTypeDesc.of(primitive));
    }

    void primitiveConversion(ClassDesc from, ClassDesc to) {
        if (from.equals(to)) return;
        TypeKind f = TypeKind.from(from);
        TypeKind t = TypeKind.from(to);
        if (f == TypeKind.BOOLEAN || t == TypeKind.BOOLEAN) return;
        TypeKind fs = f.asLoadable();
        TypeKind ts = t.asLoadable();
        if (fs != ts) code.conversion(fs, ts);
        if (ts == TypeKind.INT && t != TypeKind.INT && !(f == t)) {
            switch (t) {
                case BYTE -> code.i2b();
                case SHORT -> code.i2s();
                case CHAR -> code.i2c();
                default -> {
                }
            }
        }
    }

    void pop(ClassDesc type) {
        if (type.equals(VOID)) return;
        if (TypeKind.from(type).slotSize() == 2) code.pop2(); else code.pop();
    }

    void pushDefault(ClassDesc type) {
        if (type.equals(VOID)) return;
        if (!type.isPrimitive()) {
            if (type.equals(OBJECT) || type.equals(TypeMapper.UNIT)) {
                code.getstatic(TypeMapper.UNIT, "INSTANCE", TypeMapper.UNIT);
            } else {
                code.aconst_null();
            }
            return;
        }
        switch (TypeKind.from(type)) {
            case LONG -> code.lconst_0();
            case FLOAT -> code.fconst_0();
            case DOUBLE -> code.dconst_0();
            default -> code.iconst_0();
        }
    }

    ClassDesc mapType(KType t) {
        return types.mapType(TypeMapper.substitute(env.reified, t));
    }

    ClassDesc mapBoxedType(KType t) {
        return types.mapBoxedType(TypeMapper.substitute(env.reified, t));
    }

    /** The type of an expression as used: none for Unit. */
    ClassDesc valueType(TExpr e) {
        return TypeMapper.isUnit(e.type()) ? VOID : mapType(e.type());
    }

    // ==== Expressions ====

    /**
     * Generates an expression; returns the JVM type of the value it leaves,
     * VOID for none.
     *
     * @param hint the type the value is used as, VOID if not used, or null
     */
    ClassDesc gen(TExpr e, ClassDesc hint) {
        return switch (e) {
            case TExpr.Const c -> constant(c);
            case TExpr.StringTemplate s -> stringTemplate(s);
            case TExpr.This t -> thisValue(t.owner());
            case TExpr.ObjectValue o -> objectValue(o.object());
            case TExpr.LocalGet g -> loadLocal(g.variable());
            case TExpr.LocalSet s -> {
                storeLocal(s.variable(), s.value());
                yield VOID;
            }
            case TExpr.PropertyGet g when TypeMapper.isNothing(g.type()) -> {
                new Members(this).propertyGet(g);
                code.aconst_null().athrow();
                yield VOID;
            }
            case TExpr.PropertyGet g -> new Members(this).propertyGet(g);
            case TExpr.PropertySet s -> {
                new Members(this).propertySet(s);
                yield VOID;
            }
            case TExpr.FieldGet g -> new Members(this).fieldGet(g.property(), g.receiver());
            case TExpr.FieldSet s -> {
                new Members(this).fieldSet(s.property(), s.receiver(), s.value());
                yield VOID;
            }
            case TExpr.DelegateGet g -> new Members(this).delegateGet(g.property(), g.receiver());
            case TExpr.Call c when TypeMapper.isNothing(c.type()) -> {
                // A call that does not return ends the code, for the verifier, as kotlinc does.
                call(c, VOID);
                code.aconst_null().athrow();
                yield VOID;
            }
            case TExpr.Call c -> call(c, hint);
            case TExpr.Vararg v -> vararg(v, mapType(v.type()));
            case TExpr.ArrayLiteral a -> arrayLiteral(a);
            case TExpr.DelegatingConstructorCall d -> {
                owner.delegatingConstructorCall(this, d);
                yield VOID;
            }
            case TExpr.SafeCall s -> safeCall(s, hint);
            case TExpr.Equality q -> condition(q);
            case TExpr.Comparison c -> condition(c);
            case TExpr.Logical l -> condition(l);
            case TExpr.Elvis v -> elvis(v, hint);
            case TExpr.NotNull n -> {
                ClassDesc t = gen(n.expr(), null);
                if (!t.isPrimitive() && !t.equals(VOID)) {
                    code.dup();
                    code.invokestatic(INTRINSICS, "checkNotNull", MethodTypeDesc.of(VOID, OBJECT));
                }
                yield t;
            }
            case TExpr.TypeCheck c -> condition(c);
            case TExpr.Cast c -> cast(c);
            case TExpr.SmartCast s -> smartCast(s, hint);
            case TExpr.SamConversion s -> new Lambdas(this).samConversion(s);
            case TExpr.SuspendConversion s -> new Lambdas(this).suspendConversion(s);
            case TExpr.Block b -> block(b, hint);
            case TExpr.If i -> ifExpr(i, hint);
            case TExpr.When w -> whenExpr(w, hint);
            case TExpr.While w -> {
                whileLoop(w);
                yield VOID;
            }
            case TExpr.Break b -> {
                LoopLabels l = (LoopLabels) env.lookup(b.loop());
                jump(l.breakLabel(), l.finallyDepth());
                yield VOID;
            }
            case TExpr.Continue c -> {
                LoopLabels l = (LoopLabels) env.lookup(c.loop());
                jump(l.continueLabel(), l.finallyDepth());
                yield VOID;
            }
            case TExpr.Return r -> {
                returnExpr(r);
                yield VOID;
            }
            case TExpr.Throw t -> {
                genAs(t.expr(), ConstantDescs.CD_Throwable);
                code.athrow();
                yield VOID;
            }
            case TExpr.Try t -> tryExpr(t, hint);
            case TExpr.LocalVarDecl d -> {
                declare(d.variable(), d.initializer());
                yield VOID;
            }
            case TExpr.LocalDelegatedVarDecl d -> {
                Slot s = new Slot(newSlot(OBJECT), OBJECT, false);
                genAs(d.delegate().delegate(), OBJECT);
                store(OBJECT, s.slot());
                env.bind(d.variable().property(), s);
                env.bind(d.variable(), d.delegate());
                yield VOID;
            }
            case TExpr.LocalFunctionDecl d -> {
                new Lambdas(this).localFunction(d);
                yield VOID;
            }
            case TExpr.LocalClassDecl d -> {
                backend.localClass((KotlinClassSymbol) d.cls(), owner, methodName).setEnclosing(this);
                yield VOID;
            }
            case TExpr.Lambda l -> new Lambdas(this).lambda(l, null);
            case TExpr.CallableReference r -> new Lambdas(this).reference(r, null);
            case TExpr.ClassReference r -> classReference(r);
            case TExpr.ObjectLiteral o -> objectLiteral(o);
            case TExpr.Unresolved u -> throw unsupported(u, "unresolved expression");
        };
    }

    static CodegenException unsupported(TExpr e, String what) {
        return new CodegenException(e.pos(), "Not supported yet: " + what);
    }

    // ==== Values ====

    private ClassDesc constant(TExpr.Const c) {
        Object v = c.value();
        if (v == null) {
            code.aconst_null();
            ClassDesc t = mapType(c.type());
            return t.isPrimitive() ? TypeMapper.box(t) : t;
        }
        ClassSymbol valueClass = TypeMapper.valueClass(c.type());
        if (valueClass != null) {
            ClassDesc u = types.underlyingType(valueClass);
            return newValueClass(valueClass, () -> number((Number) v, u));
        }
        ClassDesc t = mapType(c.type());
        ClassDesc p = t.isPrimitive() ? t : TypeMapper.unbox(t);
        if (v instanceof String s) {
            code.ldc(s);
            return TypeMapper.STRING;
        }
        if (v instanceof Boolean b) {
            code.loadConstant(b ? 1 : 0);
            return ConstantDescs.CD_boolean;
        }
        if (v instanceof Character ch) {
            code.loadConstant((int) ch);
            return ConstantDescs.CD_char;
        }
        if (p == null) p = v instanceof Double ? ConstantDescs.CD_double : ConstantDescs.CD_int;
        number((Number) v, p);
        return p;
    }

    private void number(Number n, ClassDesc p) {
        switch (TypeKind.from(p)) {
            case LONG -> code.loadConstant(n.longValue());
            case FLOAT -> code.loadConstant(n.floatValue());
            case DOUBLE -> code.loadConstant(n.doubleValue());
            case CHAR -> code.loadConstant((int) (char) n.intValue());
            default -> code.loadConstant(n.intValue());
        }
    }

    /**
     * Boxes an underlying value into a value class, an unsigned literal's: value
     * classes are always boxed here, unlike kotlinc's, which keeps them unboxed.
     */
    ClassDesc newValueClass(ClassSymbol v, Runnable underlying) {
        ClassDesc d = types.classDesc(v);
        ClassDesc u = types.underlyingType(v);
        code.new_(d).dup();
        underlying.run();
        code.invokespecial(d, "<init>", MethodTypeDesc.of(TypeMapper.VOID, u));
        return d;
    }

    private ClassDesc stringTemplate(TExpr.StringTemplate s) {
        List<TExpr> parts = s.parts();
        if (parts.size() == 1) {
            ClassDesc t = gen(parts.getFirst(), null);
            if (t.equals(TypeMapper.STRING)) return t;
            toStringValue(t);
            return TypeMapper.STRING;
        }
        List<Object> operands = spillIfNeeded(parts);
        ClassDesc sb = ClassDesc.of("java.lang.StringBuilder");
        code.new_(sb).dup().invokespecial(sb, "<init>", MethodTypeDesc.of(VOID));
        for (int i = 0; i < parts.size(); i++) {
            ClassDesc t = operand(parts.get(i), operands, i, null);
            append(sb, t);
        }
        code.invokevirtual(sb, "toString", MethodTypeDesc.of(TypeMapper.STRING));
        return TypeMapper.STRING;
    }

    void append(ClassDesc sb, ClassDesc t) {
        ClassDesc param = switch (t.descriptorString()) {
            case "Z", "C", "J", "F", "D", "I" -> t;
            case "B", "S" -> ConstantDescs.CD_int;
            case "Ljava/lang/String;" -> TypeMapper.STRING;
            case "V" -> {
                pushDefault(OBJECT);
                yield OBJECT;
            }
            default -> OBJECT;
        };
        code.invokevirtual(sb, "append", MethodTypeDesc.of(sb, param));
    }

    void toStringValue(ClassDesc t) {
        if (t.equals(VOID)) pushDefault(OBJECT);
        ClassDesc param = switch (t.descriptorString()) {
            case "Z", "C", "J", "F", "D", "I" -> t;
            case "B", "S" -> ConstantDescs.CD_int;
            default -> OBJECT;
        };
        code.invokestatic(TypeMapper.STRING, "valueOf", MethodTypeDesc.of(TypeMapper.STRING, param));
    }

    /** A receiver: of a lambda or inlined function by binding, the class's own, or captured. */
    ClassDesc thisValue(Object receiverOwner) {
        Object b = env.lookup(receiverOwner);
        if (b instanceof Slot s) {
            loadSlotValue(s);
            return s.type();
        }
        if (receiverOwner == owner.cls && !isStatic) {
            code.aload(0);
            return owner.desc;
        }
        if (receiverOwner instanceof ClassSymbol c && c.isObject() && c != owner.cls) return objectValue(c);
        ClassDesc captured = owner.loadCaptured(this, receiverOwner, false);
        if (captured != null) return captured;
        throw new CodegenException(-1, "No receiver for " + receiverOwner + " in " + owner.internalName);
    }

    ClassDesc objectValue(ClassSymbol object) {
        ClassDesc desc = types.classDesc(object);
        if (object.kind() == ClassKind.ENUM_ENTRY) throw new CodegenException(-1, "Enum entry as object: " + object);
        ClassSymbol outer = object.outer();
        if (outer != null && outer.companion() == object && JavaTypeMapping.javaClass(object.id()) == null) {
            code.getstatic(types.classDesc(outer), object.name(), desc);
        } else {
            code.getstatic(desc, "INSTANCE", desc);
        }
        return desc;
    }

    private ClassDesc loadLocal(LocalVariable v) {
        Object b = env.lookup(v);
        switch (b) {
            case Slot s -> {
                loadSlotValue(s);
                return s.type();
            }
            case kotlinbootstrap.check.CheckedDelegate d -> {
                return inlineAccessor(d, d.getter(), null);
            }
            case InlineLambda l -> throw new CodegenException(-1, "An inline lambda used as a value: " + v);
            case null, default -> {
            }
        }
        if (v.kind() == LocalVariable.Kind.BACKING_FIELD) {
            return new Members(this).fieldGet(v.property(), null);
        }
        if (v.param() != null && env.lookup(v.param()) instanceof Slot s) {
            loadSlotValue(s);
            return s.type();
        }
        ClassDesc captured = owner.loadCaptured(this, v, false);
        if (captured != null) return captured;
        throw new CodegenException(-1, "No variable " + v + " in " + owner.internalName + "." + methodName);
    }

    void loadSlotValue(Slot s) {
        if (!s.ref()) {
            loadSlot(s.type(), s.slot());
            return;
        }
        ClassDesc ref = Refs.refClass(s.type());
        code.aload(s.slot());
        derefLoad(ref, s.type());
    }

    void derefLoad(ClassDesc ref, ClassDesc type) {
        ClassDesc element = Refs.elementType(type);
        code.getfield(ref, "element", element);
        coerce(element, type);
    }

    private void storeLocal(LocalVariable v, TExpr value) {
        Object b = env.lookup(v);
        if (b instanceof kotlinbootstrap.check.CheckedDelegate d) {
            inlineAccessor(d, d.setter(), value);
            return;
        }
        if (b == null && v.kind() == LocalVariable.Kind.BACKING_FIELD) {
            new Members(this).fieldSet(v.property(), null, value);
            return;
        }
        if (b == null && v.param() != null) b = env.lookup(v.param());
        if (b instanceof Slot s) {
            if (s.ref()) {
                ClassDesc ref = Refs.refClass(s.type());
                code.aload(s.slot());
                genAs(value, s.type());
                coerce(s.type(), Refs.elementType(s.type()));
                code.putfield(ref, "element", Refs.elementType(s.type()));
            } else {
                genAs(value, s.type());
                store(s.type(), s.slot());
            }
            return;
        }
        if (owner.storeCaptured(this, v, value)) return;
        throw new CodegenException(value.pos(), "No variable " + v + " to assign in " + owner.internalName);
    }

    /** Declares a local variable, in a Ref if closures change it, with its initial value if any. */
    Slot declare(LocalVariable v, TExpr initializer) {
        boolean ref = boxed.contains(v);
        if (!ref && initializer != null) {
            // The initializer first: an object literal's class is named as it is generated.
            line(initializer.pos());
            boolean local = v.type() instanceof KType.ClassType c && c.symbol().isLocal();
            ClassDesc t = gen(initializer, local ? null : mapType(v.type()));
            ClassDesc type = mapType(v.type());
            coerce(t, type);
            Slot s = new Slot(newSlot(type), type, false);
            store(type, s.slot());
            env.bind(v, s);
            return s;
        }
        ClassDesc type = mapType(v.type());
        Slot s = new Slot(newSlot(ref ? Refs.refClass(type) : type), type, ref);
        if (ref) {
            ClassDesc refClass = Refs.refClass(type);
            code.new_(refClass).dup().invokespecial(refClass, "<init>", MethodTypeDesc.of(VOID));
            code.astore(s.slot());
        }
        env.bind(v, s);
        if (initializer != null) {
            line(initializer.pos());
            if (ref) {
                code.aload(s.slot());
                genAs(initializer, type);
                coerce(type, Refs.elementType(type));
                code.putfield(Refs.refClass(type), "element", Refs.elementType(type));
            } else {
                genAs(initializer, type);
                store(type, s.slot());
            }
        }
        return s;
    }

    /** A local delegated property's accessor, inlined: the getter, or the setter with the value. */
    private ClassDesc inlineAccessor(kotlinbootstrap.check.CheckedDelegate d, CheckedFunction accessor, TExpr value) {
        Env saved = env;
        env = new Env(saved);
        List<TExpr> args = new ArrayList<>();
        if (value != null) {
            Slot s = parameterSlotFor(accessor.params().getFirst(), value);
            env.bind(accessor.params().getFirst(), s);
        }
        ClassDesc result = inlineBody(accessor, args);
        env = saved;
        return result;
    }

    private Slot parameterSlotFor(LocalVariable param, TExpr value) {
        ClassDesc type = mapType(param.type());
        Env inner = env;
        env = env.parent != null ? env.parent : env;
        genAs(value, type);
        env = inner;
        Slot s = new Slot(newSlot(type), type, false);
        store(type, s.slot());
        return s;
    }

    // ==== Calls ====

    private ClassDesc call(TExpr.Call c, ClassDesc hint) {
        line(c.pos());
        ClassDesc intrinsic = intrinsics.call(c);
        if (intrinsic != null) return intrinsic;
        FunctionSymbol f = c.function();
        // The invoke of an inline lambda parameter.
        if (f.name().equals("invoke") && c.dispatchReceiver() != null
                && unwrap(c.dispatchReceiver()) instanceof TExpr.LocalGet g
                && env.lookup(g.variable()) instanceof InlineLambda l) {
            // An extension function type's receiver is its first argument.
            List<TExpr> args = new ArrayList<>();
            if (c.extensionReceiver() != null) args.add(c.extensionReceiver());
            for (TExpr.Argument a : c.args()) args.add(a.value());
            return invokeInlineLambda(l, args, hint);
        }
        if (isInline(f)) return inlineCall(c, hint);
        ClassDesc result = new Calls(this).call(c);
        // A generic function's result of type Unit is Unit itself, as kotlinc does.
        if (!result.isPrimitive() && !result.equals(VOID) && TypeMapper.isUnit(c.type())) {
            pop(result);
            return VOID;
        }
        return result;
    }

    static TExpr unwrap(TExpr e) {
        while (e instanceof TExpr.SmartCast s) e = s.expr();
        return e;
    }

    /** Whether calls of a function are inlined: an inline function with a body in the sources. */
    boolean isInline(FunctionSymbol f) {
        return f.has(Modifier.INLINE) && f.origin() == Origin.KOTLIN_SOURCE
                && f.source() instanceof Decl.Function d && d.body() != null && !f.has(Modifier.EXTERNAL);
    }

    /** Evaluates the arguments of a call to slots in source order, then inlines the function's body. */
    private ClassDesc inlineCall(TExpr.Call c, ClassDesc hint) {
        FunctionSymbol f = c.function();
        return inlineFunction(f, backend.checker.checkedFunction(f), c.typeArgs(), c.dispatchReceiver(),
                c.contextArgs(), c.extensionReceiver(), c.args(), c.pos());
    }

    /**
     * Inlines a function or property accessor: its receivers and arguments
     * evaluated to slots in source order, bound in an environment of its own.
     */
    ClassDesc inlineFunction(CallableSymbol f, CheckedFunction body, List<KType> callTypeArgs, TExpr dispatchReceiver,
                             List<TExpr> contextArgs, TExpr extensionReceiver, List<TExpr.Argument> args, int pos) {
        Env callee = new Env(null);
        // The type arguments, for reified type parameters, with those of the caller's substituted.
        List<KType> typeArgs = new ArrayList<>();
        for (KType t : callTypeArgs) typeArgs.add(TypeMapper.substitute(env.reified, t));
        List<TypeParamSymbol> typeParams = f.typeParams();
        callee.reified = typeArgs.size() == typeParams.size() ? Substitution.ofTypes(typeParams, typeArgs) : null;
        Env caller = env;
        if (dispatchReceiver != null) {
            Object receiverKey = f.owner();
            callee.bind(receiverKey, spill(dispatchReceiver));
        }
        List<LocalVariable> contextParams = body.contextParams();
        for (int i = 0; i < contextArgs.size() && i < contextParams.size(); i++) {
            callee.bind(contextParams.get(i), spill(contextArgs.get(i)));
        }
        if (extensionReceiver != null) callee.bind(f, spill(extensionReceiver));
        List<LocalVariable> params = body.params();
        List<ValueParamSymbol> symbols = f.signature().params();
        boolean[] given = new boolean[params.size()];
        for (TExpr.Argument a : args) {
            LocalVariable p = params.get(a.param());
            given[a.param()] = true;
            TExpr value = a.value();
            ValueParamSymbol ps = a.param() < symbols.size() ? symbols.get(a.param()) : null;
            boolean inlined = ps != null && !ps.modifiers().contains(Modifier.NOINLINE)
                    && FunctionTypes.isFunctionType(ps.type());
            if (inlined && unwrapConversions(value) instanceof TExpr.Lambda l) {
                callee.bind(p, new InlineLambda(l, caller));
            } else if (inlined && unwrap(value) instanceof TExpr.LocalGet g
                    && env.lookup(g.variable()) instanceof InlineLambda l) {
                callee.bind(p, l);
            } else if (value instanceof TExpr.Vararg v) {
                Slot s = new Slot(newSlot(mapType(v.type())), mapType(v.type()), false);
                coerce(vararg(v, s.type()), s.type());
                store(s.type(), s.slot());
                callee.bind(p, s);
            } else {
                callee.bind(p, spillAs(value, mapType(p.type())));
            }
        }
        // Defaults are evaluated in the callee's environment, after the explicit arguments.
        env = callee;
        for (int i = 0; i < params.size(); i++) {
            if (given[i]) continue;
            TExpr dv = i < body.defaultValues().size() ? body.defaultValues().get(i) : null;
            LocalVariable p = params.get(i);
            ClassDesc type = mapType(p.type());
            if (dv == null && i < symbols.size() && symbols.get(i).isVararg()) {
                emptyArray(type);
            } else if (dv == null) {
                throw new CodegenException(pos, "No argument for " + p + " of " + f);
            } else {
                genAs(dv, type);
            }
            Slot s = new Slot(newSlot(type), type, false);
            store(type, s.slot());
            callee.bind(p, s);
        }
        ClassDesc result = inlineBody(body, List.of());
        env = caller;
        return result;
    }

    /** A lambda argument through its conversions, which inlining makes unneeded. */
    private static TExpr unwrapConversions(TExpr e) {
        while (true) {
            switch (e) {
                case TExpr.SmartCast s -> e = s.expr();
                case TExpr.SamConversion s -> {
                    return e;
                }
                default -> {
                    return e;
                }
            }
        }
    }

    /** Evaluates a value into a fresh slot of its own type. */
    Slot spill(TExpr e) {
        ClassDesc type = gen(e, null);
        if (type.equals(VOID)) {
            pushDefault(TypeMapper.UNIT);
            type = TypeMapper.UNIT;
        }
        Slot s = new Slot(newSlot(type), type, false);
        store(type, s.slot());
        return s;
    }

    Slot spillAs(TExpr e, ClassDesc type) {
        genAs(e, type);
        Slot s = new Slot(newSlot(type), type, false);
        store(type, s.slot());
        return s;
    }

    /**
     * Generates an inlined body, whose bindings are in the current
     * environment, with its returns going to its end.
     */
    ClassDesc inlineBody(CheckedFunction body, List<TExpr> unused) {
        ClassDesc type = body.returnType() == null || TypeMapper.isUnitOrNothing(body.returnType()) ? VOID
                : mapType(body.returnType());
        Label end = code.newLabel();
        int slot = type.equals(VOID) ? -1 : newSlot(type);
        env.bindReturn(body, new ReturnTarget(end, slot, type, finallies.size()));
        inlineDepth++;
        scanBoxed(body.body());
        ClassDesc t = gen(body.body(), type);
        if (!t.equals(VOID) || !type.equals(VOID)) {
            coerce(t, type);
            if (slot >= 0) store(type, slot);
        }
        inlineDepth--;
        code.labelBinding(end);
        if (slot >= 0) loadSlot(type, slot);
        return type;
    }

    /** Invokes a lambda passed to an inline function: its body, with the arguments bound to its parameters. */
    ClassDesc invokeInlineLambda(InlineLambda l, List<TExpr> args, ClassDesc hint) {
        if (!(l.fn() instanceof TExpr.Lambda lambda)) {
            throw new CodegenException(l.fn().pos(), "Inline argument not a lambda");
        }
        CheckedFunction fn = lambda.function();
        List<Slot> values = new ArrayList<>();
        List<KType> paramTypes = lambdaParameterTypes(fn);
        for (int i = 0; i < args.size(); i++) {
            ClassDesc type = i < paramTypes.size() ? mapType(paramTypes.get(i)) : OBJECT;
            values.add(spillAs(args.get(i), type));
        }
        return invokeInlineLambda(l, values);
    }

    /** The types of the arguments of a lambda: a receiver comes first among those of a function type with one. */
    static List<KType> lambdaParameterTypes(CheckedFunction fn) {
        List<KType> paramTypes = new ArrayList<>();
        if (fn.receiverType() != null) paramTypes.add(fn.receiverType());
        for (LocalVariable p : fn.contextParams()) paramTypes.add(p.type());
        for (LocalVariable p : fn.params()) paramTypes.add(p.type());
        return paramTypes;
    }

    /** Invokes an inline lambda with its arguments in slots. */
    ClassDesc invokeInlineLambda(InlineLambda l, List<Slot> values) {
        CheckedFunction fn = ((TExpr.Lambda) l.fn()).function();
        Env saved = env;
        env = new Env(l.env());
        int k = 0;
        if (fn.receiverType() != null && k < values.size()) env.bind(fn, values.get(k++));
        for (LocalVariable p : fn.contextParams()) if (k < values.size()) env.bind(p, values.get(k++));
        for (LocalVariable p : fn.params()) if (k < values.size()) env.bind(p, values.get(k++));
        ClassDesc result = inlineBody(fn, List.of());
        env = saved;
        return result;
    }

    /**
     * Calls a function value with an index, leaving its result as the type:
     * inlined for a lambda, as the init of an array constructor.
     */
    void invokeWithIndex(TExpr fn, Slot index, ClassDesc type) {
        InlineLambda l = null;
        TExpr f = unwrapConversions(fn);
        if (f instanceof TExpr.Lambda) l = new InlineLambda(f, env);
        else if (unwrap(f) instanceof TExpr.LocalGet g && env.lookup(g.variable()) instanceof InlineLambda bound) l = bound;
        if (l != null) {
            CheckedFunction lambda = ((TExpr.Lambda) l.fn()).function();
            ClassDesc paramType = mapType(lambdaParameterTypes(lambda).getFirst());
            Slot arg = index;
            if (!paramType.equals(index.type())) {
                loadSlot(index.type(), index.slot());
                coerce(index.type(), paramType);
                arg = new Slot(newSlot(paramType), paramType, false);
                store(paramType, arg.slot());
            }
            coerce(invokeInlineLambda(l, List.of(arg)), type);
            return;
        }
        ClassDesc function1 = ClassDesc.ofInternalName("kotlin/jvm/functions/Function1");
        genAs(fn, function1);
        loadSlot(index.type(), index.slot());
        coerce(index.type(), OBJECT);
        code.invokeinterface(function1, "invoke", MethodTypeDesc.of(OBJECT, OBJECT));
        coerce(OBJECT, type);
    }

    // ==== Operands ====

    /** Whether evaluating an expression may need an empty stack: it has a try, maybe through an inlined call. */
    boolean needsEmptyStack(TExpr e) {
        if (e == null) return false;
        boolean[] found = new boolean[1];
        scan(e, found);
        return found[0];
    }

    private void scan(TExpr e, boolean[] found) {
        if (found[0] || e == null) return;
        switch (e) {
            case TExpr.Try t -> found[0] = true;
            case TExpr.Break b -> found[0] = true;
            case TExpr.Continue c -> found[0] = true;
            case TExpr.Return r -> found[0] = true;
            case TExpr.Call c when isInline(c.function()) -> found[0] = true;
            case TExpr.Lambda l -> {
            }
            default -> Closures.children(e, c -> scan(c, found));
        }
    }

    /**
     * For operands evaluated onto the stack, where a later one needs an
     * empty stack: the slots the operands are evaluated into first;
     * otherwise null.
     */
    List<Object> spillIfNeeded(List<TExpr> operands) {
        boolean needed = false;
        for (int i = 1; i < operands.size() && !needed; i++) needed = needsEmptyStack(operands.get(i));
        if (!needed) return null;
        List<Object> slots = new ArrayList<>();
        for (TExpr e : operands) slots.add(e == null ? null : spill(e));
        return slots;
    }

    /** An operand: from its slot if spilled, otherwise generated now. */
    ClassDesc operand(TExpr e, List<Object> spilled, int index, ClassDesc hint) {
        if (spilled != null && spilled.get(index) instanceof Slot s) {
            loadSlot(s.type(), s.slot());
            return s.type();
        }
        return gen(e, hint);
    }

    // ==== Arrays ====

    ClassDesc vararg(TExpr.Vararg v, ClassDesc arrayType) {
        ClassDesc element = arrayType.componentType();
        List<TExpr.VarargElement> elements = v.elements();
        boolean anySpread = elements.stream().anyMatch(TExpr.VarargElement::spread);
        if (!anySpread) {
            code.loadConstant(elements.size());
            newArray(element);
            for (int i = 0; i < elements.size(); i++) {
                code.dup();
                code.loadConstant(i);
                genAs(elements.get(i).value(), element);
                code.arrayStore(TypeKind.from(element));
            }
            return arrayType;
        }
        if (elements.size() == 1) {
            genAs(elements.getFirst().value(), arrayType);
            code.dup().arraylength();
            ClassDesc copyType = element.isPrimitive() ? arrayType : OBJECT.arrayType();
            code.invokestatic(ClassDesc.of("java.util.Arrays"), "copyOf",
                    MethodTypeDesc.of(copyType, copyType, ConstantDescs.CD_int));
            coerce(copyType, arrayType);
            return arrayType;
        }
        // A spread builder collects elements and arrays.
        String kind = element.isPrimitive() ? switch (TypeKind.from(element)) {
            case BOOLEAN -> "Boolean";
            case CHAR -> "Char";
            case BYTE -> "Byte";
            case SHORT -> "Short";
            case INT -> "Int";
            case LONG -> "Long";
            case FLOAT -> "Float";
            default -> "Double";
        } : "";
        ClassDesc builder = ClassDesc.ofInternalName("kotlin/jvm/internal/" + kind + "SpreadBuilder");
        code.new_(builder).dup().loadConstant(elements.size())
                .invokespecial(builder, "<init>", MethodTypeDesc.of(VOID, ConstantDescs.CD_int));
        for (TExpr.VarargElement el : elements) {
            code.dup();
            if (el.spread()) {
                genAs(el.value(), element.isPrimitive() ? arrayType : OBJECT);
                code.invokevirtual(builder, "addSpread",
                        MethodTypeDesc.of(VOID, element.isPrimitive() ? OBJECT : OBJECT));
            } else {
                ClassDesc t = element.isPrimitive() ? element : OBJECT;
                genAs(el.value(), t);
                code.invokevirtual(builder, "add", MethodTypeDesc.of(VOID, t));
            }
        }
        if (element.isPrimitive()) {
            code.invokevirtual(builder, "toArray", MethodTypeDesc.of(OBJECT));
            code.checkcast(arrayType);
        } else {
            code.dup().invokevirtual(builder, "size", MethodTypeDesc.of(ConstantDescs.CD_int));
            newArray(element);
            code.invokevirtual(builder, "toArray", MethodTypeDesc.of(OBJECT.arrayType(), OBJECT.arrayType()));
            coerce(OBJECT.arrayType(), arrayType);
        }
        return arrayType;
    }

    void emptyArray(ClassDesc arrayType) {
        code.iconst_0();
        newArray(arrayType.componentType());
    }

    void newArray(ClassDesc element) {
        if (element.isPrimitive()) code.newarray(TypeKind.from(element));
        else code.anewarray(element);
    }

    private ClassDesc arrayLiteral(TExpr.ArrayLiteral a) {
        ClassDesc type = mapType(a.type());
        ClassDesc element = type.componentType();
        code.loadConstant(a.elements().size());
        newArray(element);
        for (int i = 0; i < a.elements().size(); i++) {
            code.dup().loadConstant(i);
            genAs(a.elements().get(i), element);
            code.arrayStore(TypeKind.from(element));
        }
        return type;
    }

    // ==== Null safety ====

    private ClassDesc safeCall(TExpr.SafeCall s, ClassDesc hint) {
        ClassDesc receiverType = gen(s.receiver(), null);
        if (receiverType.isPrimitive()) {
            coerce(receiverType, TypeMapper.box(receiverType));
            receiverType = TypeMapper.box(receiverType);
        }
        boolean used = hint == null || !hint.equals(VOID);
        ClassDesc resultType = !used || TypeMapper.isUnit(Types.makeDefinitelyNotNull(s.type()))
                || TExprs.isStatement(s.selector()) ? VOID : mapBoxedType(s.type());
        Label isNull = code.newLabel();
        Label end = code.newLabel();
        code.dup();
        code.ifnull(isNull);
        Env saved = env;
        env = new Env(saved);
        ClassDesc subjectType = mapType(s.subject().type());
        coerce(receiverType, subjectType);
        Slot subject = new Slot(newSlot(subjectType), subjectType, false);
        store(subjectType, subject.slot());
        env.bind(s.subject(), subject);
        genAs(s.selector(), resultType);
        env = saved;
        code.goto_(end);
        code.labelBinding(isNull);
        code.pop();
        if (!resultType.equals(VOID)) code.aconst_null();
        code.labelBinding(end);
        return resultType;
    }

    private ClassDesc elvis(TExpr.Elvis v, ClassDesc hint) {
        ClassDesc resultType = hint != null && !hint.equals(VOID) ? hint : mapType(v.type());
        if (hint != null && hint.equals(VOID)) resultType = VOID;
        ClassDesc left = gen(v.left(), null);
        if (left.isPrimitive()) {
            coerce(left, resultType);
            return resultType;
        }
        Label notNull = code.newLabel();
        Label end = code.newLabel();
        code.dup();
        code.ifnonnull(notNull);
        code.pop();
        genAs(v.right(), resultType);
        code.goto_(end);
        code.labelBinding(notNull);
        coerce(left, resultType);
        code.labelBinding(end);
        return resultType;
    }

    // ==== Conditions ====

    /** A Boolean value from a condition. */
    private ClassDesc condition(TExpr e) {
        Label isFalse = code.newLabel();
        Label end = code.newLabel();
        jump(e, false, isFalse);
        code.iconst_1();
        code.goto_(end);
        code.labelBinding(isFalse);
        code.iconst_0();
        code.labelBinding(end);
        return ConstantDescs.CD_boolean;
    }

    /** Jumps to the label if the condition has the value. */
    void jump(TExpr e, boolean ifTrue, Label target) {
        switch (e) {
            case TExpr.Const c when c.value() instanceof Boolean b -> {
                if (b == ifTrue) code.goto_(target);
            }
            case TExpr.Logical l -> {
                if (l.and() != ifTrue) {
                    // and, jumping if false; or, jumping if true: either side decides.
                    jump(l.left(), ifTrue, target);
                    jump(l.right(), ifTrue, target);
                } else {
                    Label skip = code.newLabel();
                    jump(l.left(), !ifTrue, skip);
                    jump(l.right(), ifTrue, target);
                    code.labelBinding(skip);
                }
            }
            case TExpr.Call c when Intrinsics.isBooleanNot(c) -> jump(c.dispatchReceiver(), !ifTrue, target);
            case TExpr.Equality q -> new Conditions(this).equality(q, ifTrue, target);
            case TExpr.Comparison c -> new Conditions(this).comparison(c, ifTrue, target);
            case TExpr.TypeCheck c -> {
                new Conditions(this).isInstance(c.expr(), c.target());
                if (ifTrue != c.negated()) code.ifne(target); else code.ifeq(target);
            }
            default -> {
                genAs(e, ConstantDescs.CD_boolean);
                if (ifTrue) code.ifne(target); else code.ifeq(target);
            }
        }
    }

    private ClassDesc cast(TExpr.Cast c) {
        ClassDesc target = mapType(c.target());
        if (!c.safe()) {
            ClassDesc t = gen(c.expr(), null);
            if (t.isPrimitive() && target.isPrimitive() && !t.equals(target)) {
                coerce(t, TypeMapper.box(t));
                t = TypeMapper.box(t);
            }
            // Null is not cast to a non-null class type, as kotlinc's TypeIntrinsics.
            if (!t.isPrimitive() && !target.isPrimitive() && c.target() instanceof KType.ClassType ct
                    && !ct.nullable() && !Types.isMarkedOrFlexiblyNullable(ct)) {
                Label nonNull = code.newLabel();
                code.dup().ifnonnull(nonNull);
                code.new_(NPE).dup().ldc("null cannot be cast to non-null type " + TypeRenderer.render(ct))
                        .invokespecial(NPE, "<init>", MethodTypeDesc.of(VOID, TypeMapper.STRING)).athrow();
                code.labelBinding(nonNull);
            }
            coerce(t, target);
            return target;
        }
        ClassDesc boxedTarget = target.isPrimitive() ? TypeMapper.box(target) : target;
        ClassDesc t = gen(c.expr(), null);
        coerce(t, OBJECT);
        Label fail = code.newLabel();
        Label end = code.newLabel();
        code.dup();
        new Conditions(this).instanceOfOnStack(c.target());
        code.ifeq(fail);
        coerce(OBJECT, boxedTarget);
        code.goto_(end);
        code.labelBinding(fail);
        code.pop();
        code.aconst_null();
        code.labelBinding(end);
        return boxedTarget;
    }

    private ClassDesc smartCast(TExpr.SmartCast s, ClassDesc hint) {
        ClassDesc t = gen(s.expr(), null);
        KType type = s.typeWithoutNullableNothing() != null ? s.typeWithoutNullableNothing() : s.type();
        ClassDesc target = mapType(type);
        if (target.descriptorString().equals("Ljava/lang/Void;")) return t;
        if (t.isPrimitive() || t.equals(VOID)) return t;
        coerce(t, target);
        return target;
    }

    // ==== Control flow ====

    private ClassDesc block(TExpr.Block b, ClassDesc hint) {
        List<TExpr> statements = b.statements();
        if (statements.isEmpty()) return VOID;
        Env saved = env;
        env = new Env(saved);
        for (int i = 0; i < statements.size() - 1; i++) {
            line(statements.get(i).pos());
            genAs(statements.get(i), VOID);
        }
        TExpr last = statements.getLast();
        line(last.pos());
        ClassDesc result;
        if (TypeMapper.isUnit(b.type()) && !TypeMapper.isUnit(last.type()) || hint != null && hint.equals(VOID)) {
            genAs(last, VOID);
            result = VOID;
        } else {
            result = gen(last, hint);
        }
        env = saved;
        return result;
    }

    /** The JVM type of a conditional's value: as hinted, else of its own type; none if not used or Unit. */
    private ClassDesc branchType(TExpr e, ClassDesc hint, boolean hasValue) {
        if (!hasValue || hint != null && hint.equals(VOID) || TypeMapper.isUnit(e.type())) return VOID;
        if (TypeMapper.isClass(e.type(), "kotlin.Nothing")) return VOID;
        return hint != null ? hint : mapType(e.type());
    }

    private ClassDesc ifExpr(TExpr.If i, ClassDesc hint) {
        ClassDesc type = branchType(i, hint, i.otherwise() != null);
        Label otherwise = code.newLabel();
        Label end = code.newLabel();
        jump(i.condition(), false, otherwise);
        genAs(i.then(), type);
        code.goto_(end);
        code.labelBinding(otherwise);
        if (i.otherwise() != null) genAs(i.otherwise(), type);
        code.labelBinding(end);
        return type;
    }

    private ClassDesc whenExpr(TExpr.When w, ClassDesc hint) {
        ClassDesc type = branchType(w, hint, w.exhaustive());
        Env saved = env;
        env = new Env(saved);
        if (w.subject() != null) genAs(w.subject(), VOID);
        Label end = code.newLabel();
        boolean hasElse = false;
        for (TExpr.WhenBranch b : w.branches()) {
            if (b.condition() == null) {
                hasElse = true;
                genAs(b.body(), type);
                code.goto_(end);
                break;
            }
            Label next = code.newLabel();
            jump(b.condition(), false, next);
            genAs(b.body(), type);
            code.goto_(end);
            code.labelBinding(next);
        }
        if (!hasElse && w.exhaustive()) {
            ClassDesc ex = ClassDesc.ofInternalName("kotlin/NoWhenBranchMatchedException");
            code.new_(ex).dup().invokespecial(ex, "<init>", MethodTypeDesc.of(VOID)).athrow();
        } else if (!hasElse && !type.equals(VOID)) {
            pushDefault(type);
        }
        code.labelBinding(end);
        env = saved;
        return type;
    }

    private void whileLoop(TExpr.While w) {
        Label start = code.newLabel();
        Label cont = code.newLabel();
        Label brk = code.newLabel();
        Env saved = env;
        env = new Env(saved);
        env.bind(w.loop(), new LoopLabels(brk, cont, finallies.size()));
        if (w.doWhile()) {
            code.labelBinding(start);
            // The condition sees the body's variables.
            if (w.body() instanceof TExpr.Block b) {
                for (TExpr s : b.statements()) {
                    line(s.pos());
                    genAs(s, VOID);
                }
            } else {
                genAs(w.body(), VOID);
            }
            code.labelBinding(cont);
            jump(w.condition(), true, start);
        } else {
            code.labelBinding(start);
            code.labelBinding(cont);
            jump(w.condition(), false, brk);
            genAs(w.body(), VOID);
            code.goto_(start);
        }
        code.labelBinding(brk);
        env = saved;
    }

    private void returnExpr(TExpr.Return r) {
        ReturnTarget t = env.lookupReturn(r.target());
        if (t != null) {
            if (r.value() != null) {
                genAs(r.value(), t.type());
                if (t.slot() >= 0) store(t.type(), t.slot());
            } else if (!t.type().equals(VOID)) {
                pushDefault(t.type());
                store(t.type(), t.slot());
            }
            jump(t.end(), t.finallyDepth());
            return;
        }
        if (r.target() != function) {
            throw new CodegenException(r.pos(), "Return to " + r.target() + " not in " + methodName);
        }
        if (r.value() != null) genAs(r.value(), returnType); else pushDefault(returnType);
        if (!finallies.isEmpty()) {
            int slot = returnType.equals(VOID) ? -1 : newSlot(returnType);
            if (slot >= 0) store(returnType, slot);
            runFinallies(0);
            if (slot >= 0) loadSlot(returnType, slot);
        }
        returnValue(returnType);
    }

    /** Jumps out to a label, running the finally blocks above the depth on the way. */
    void jump(Label target, int finallyDepth) {
        if (finallies.size() > finallyDepth) runFinallies(finallyDepth);
        code.goto_(target);
        for (int i = finallyDepth; i < finallies.size(); i++) finallies.get(i).start = code.newBoundLabel();
    }

    private void runFinallies(int depth) {
        Label here = code.newBoundLabel();
        for (int i = depth; i < finallies.size(); i++) {
            Finally f = finallies.get(i);
            f.ranges.add(new Label[]{f.start, here});
        }
        List<Finally> saved = new ArrayList<>(finallies.subList(depth, finallies.size()));
        for (int i = saved.size() - 1; i >= 0; i--) {
            Finally f = saved.get(i);
            while (finallies.size() > depth + i) finallies.removeLast();
            // A try's marker, for the ranges its catch clauses cover, has no block.
            if (f.block == null) continue;
            Env e = env;
            env = new Env(f.env);
            genAs(f.block, VOID);
            env = e;
        }
        finallies.addAll(saved.subList(finallies.size() - depth, saved.size()));
        for (int i = depth; i < finallies.size(); i++) finallies.get(i).start = code.newBoundLabel();
    }

    private ClassDesc tryExpr(TExpr.Try t, ClassDesc hint) {
        ClassDesc type = branchType(t, hint, true);
        int result = type.equals(VOID) ? -1 : newSlot(type);
        Label start = code.newBoundLabel();
        Finally fin = t.finallyBlock() != null ? new Finally(t.finallyBlock(), env, start) : null;
        Label end = code.newLabel();
        // The ranges the catch clauses cover: the body, without the finally blocks run on jumps out.
        List<Label[]> bodyRanges = new ArrayList<>();
        Finally bodyMarker = new Finally(null, env, start);
        if (fin != null) finallies.add(fin);
        finallies.add(bodyMarker);
        int markerIndex = finallies.size() - 1;
        genAs(t.body(), type);
        if (result >= 0) store(type, result);
        Label bodyEnd = code.newBoundLabel();
        finallies.remove(markerIndex);
        bodyRanges.addAll(bodyMarker.ranges);
        bodyRanges.add(new Label[]{bodyMarker.start, bodyEnd});
        if (fin != null) {
            fin.ranges.add(new Label[]{fin.start, bodyEnd});
            finallies.remove(fin);
            Env e = env;
            env = new Env(fin.env);
            genAs(fin.block, VOID);
            env = e;
        }
        code.goto_(end);
        for (TExpr.CatchClause c : t.catches()) {
            Label handler = code.newBoundLabel();
            ClassDesc exType = mapType(c.param().type());
            for (Label[] r : bodyRanges) code.exceptionCatch(r[0], r[1], handler, exType);
            owner.hasCatches = true;
            if (fin != null) {
                finallies.add(fin);
                fin.start = handler;
            }
            Env saved = env;
            env = new Env(saved);
            Slot ex = new Slot(newSlot(exType), exType, false);
            store(exType, ex.slot());
            env.bind(c.param(), ex);
            genAs(c.body(), type);
            if (result >= 0) store(type, result);
            env = saved;
            if (fin != null) {
                Label catchEnd = code.newBoundLabel();
                fin.ranges.add(new Label[]{fin.start, catchEnd});
                finallies.remove(fin);
                Env e = env;
                env = new Env(fin.env);
                genAs(fin.block, VOID);
                env = e;
            }
            code.goto_(end);
        }
        if (fin != null) {
            Label handler = code.newBoundLabel();
            for (Label[] r : fin.ranges) code.exceptionCatch(r[0], r[1], handler, java.util.Optional.empty());
            owner.hasCatches = true;
            int ex = newSlot(ConstantDescs.CD_Throwable);
            code.astore(ex);
            Env e = env;
            env = new Env(fin.env);
            genAs(fin.block, VOID);
            env = e;
            code.aload(ex);
            code.athrow();
        }
        code.labelBinding(end);
        if (result >= 0) loadSlot(type, result);
        return type;
    }

    // ==== Classes ====

    private ClassDesc classReference(TExpr.ClassReference r) {
        if (r.receiver() != null) {
            genAs(r.receiver(), OBJECT);
            code.invokevirtual(OBJECT, "getClass", MethodTypeDesc.of(ConstantDescs.CD_Class));
        } else {
            javaClass(TypeMapper.substitute(env.reified, r.classType()));
        }
        ClassDesc reflection = ClassDesc.ofInternalName("kotlin/jvm/internal/Reflection");
        ClassDesc kClass = ClassDesc.ofInternalName("kotlin/reflect/KClass");
        code.invokestatic(reflection, "getOrCreateKotlinClass", MethodTypeDesc.of(kClass, ConstantDescs.CD_Class));
        return kClass;
    }

    /** The Class object of a type: of a primitive for a non-null builtin, as Int::class is. */
    void javaClass(KType t) {
        ClassDesc d = types.mapType(t);
        if (d.isPrimitive()) {
            code.getstatic(TypeMapper.box(d), "TYPE", ConstantDescs.CD_Class);
        } else {
            code.ldc(d);
        }
    }

    private ClassDesc objectLiteral(TExpr.ObjectLiteral o) {
        ClassCodegen g = backend.localClass((KotlinClassSymbol) o.cls(), owner, methodName);
        g.setEnclosing(this);
        return g.newInstance(this);
    }
}
