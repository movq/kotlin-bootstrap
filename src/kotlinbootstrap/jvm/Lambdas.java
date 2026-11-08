package kotlinbootstrap.jvm;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kotlinbootstrap.check.CheckedFunction;
import kotlinbootstrap.check.LocalVariable;
import kotlinbootstrap.check.TExpr;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;

/**
 * Functions as values, as kotlinc with {@code -Xlambdas=indy}: lambdas,
 * references and SAM conversions are invokedynamic calls of
 * LambdaMetafactory, implemented by synthetic static methods of the class
 * that takes what they capture first, then the erased parameters of the
 * interface's method. Local functions are static methods too, called with
 * what they capture.
 */
final class Lambdas {
    private static final DirectMethodHandleDesc METAFACTORY = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC, ClassDesc.of("java.lang.invoke.LambdaMetafactory"), "metafactory",
            MethodTypeDesc.of(ConstantDescs.CD_CallSite, ConstantDescs.CD_MethodHandles_Lookup,
                    ConstantDescs.CD_String, ConstantDescs.CD_MethodType, ConstantDescs.CD_MethodType,
                    ConstantDescs.CD_MethodHandle, ConstantDescs.CD_MethodType));

    /** A local function compiled to a static method, with what it captures. */
    record LocalFunction(ClassDesc owner, String name, MethodTypeDesc type, boolean ownerIsInterface,
                         List<ExprCodegen.Capture> captures, CheckedFunction body) {
    }

    /** The interface a function value implements, with its method's name and erased descriptor. */
    private record Sam(ClassDesc type, String method, MethodTypeDesc erased) {
    }

    private final ExprCodegen gen;
    private final CodeBuilder code;
    private final JvmBackend backend;

    Lambdas(ExprCodegen gen) {
        this.gen = gen;
        this.code = gen.code;
        this.backend = gen.backend;
    }

    // ==== Interfaces ====

    /** FunctionN for a function type of N parameters, receiver and context included. */
    private static Sam functionN(int arity) {
        List<ClassDesc> params = Collections.nCopies(arity, TypeMapper.OBJECT);
        return new Sam(ClassDesc.ofInternalName("kotlin/jvm/functions/Function" + arity), "invoke",
                MethodTypeDesc.of(TypeMapper.OBJECT, params));
    }

    private Sam sam(ClassSymbol cls) {
        FunctionSymbol method = backend.checker.samMethod(cls);
        if (method == null) throw new CodegenException(-1, "Not a functional interface: " + cls);
        return new Sam(gen.types.classDesc(cls), backend.names.functionName(method), gen.types.methodType(method));
    }

    private Sam samOf(KType type) {
        if (kotlinbootstrap.types.Types.lowerBound(type) instanceof KType.ClassType c) return sam(c.symbol());
        throw new CodegenException(-1, "Not a functional interface type: " + type);
    }

    // ==== Lambdas ====

    /** A lambda as a function object, or as an instance of the SAM interface type if given. */
    ClassDesc lambda(TExpr.Lambda l, KType samType) {
        CheckedFunction fn = l.function();
        List<KType> paramTypes = ExprCodegen.lambdaParameterTypes(fn);
        // A suspend lambda takes a continuation last, and its method is a suspend function's.
        boolean suspend = samType == null && FunctionTypes.kind(l.type()) instanceof FunctionTypes.Kind k
                && k.isSuspend();
        Sam sam = samType != null ? samOf(samType) : functionN(paramTypes.size() + (suspend ? 1 : 0));
        List<ExprCodegen.Capture> captures = captures(backend.closures.of(fn));
        ExprCodegen.Env enclosing = gen.env;
        String name = gen.owner.syntheticName(gen.methodName + "$lambda");
        MethodTypeDesc implType = implType(captures, sam.erased());
        gen.owner.addMethod(name, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, implType,
                fn, enclosing, g -> {
                    g.bindCaptures(captures);
                    List<ExprCodegen.Slot> args = g.erasedParameters(sam.erased().parameterList(), paramTypes);
                    int k = 0;
                    if (fn.receiverType() != null) g.env.bind(fn, args.get(k++));
                    for (LocalVariable p : fn.contextParams()) g.env.bind(p, args.get(k++));
                    for (LocalVariable p : fn.params()) g.env.bind(p, args.get(k++));
                    if (suspend) g.beginSuspend(args.getLast().slot());
                    g.body(fn.body());
                });
        return indy(sam, captures, name, implType);
    }

    private List<ExprCodegen.Capture> captures(Closures.Captures captured) {
        List<ExprCodegen.Capture> result = new ArrayList<>();
        for (Object r : captured.receivers()) result.add(gen.capture(r));
        for (LocalVariable v : captured.variables()) result.add(gen.capture(v));
        return result;
    }

    private static MethodTypeDesc implType(List<ExprCodegen.Capture> captures, MethodTypeDesc erased) {
        List<ClassDesc> params = new ArrayList<>();
        for (ExprCodegen.Capture c : captures) params.add(c.jvmType());
        params.addAll(erased.parameterList());
        return MethodTypeDesc.of(erased.returnType(), params);
    }

    /** Loads the captured values and creates the function object. */
    private ClassDesc indy(Sam sam, List<ExprCodegen.Capture> captures, String implName, MethodTypeDesc implType) {
        List<ClassDesc> captureTypes = new ArrayList<>();
        for (ExprCodegen.Capture c : captures) {
            gen.coerce(gen.loadForCapture(c.key()), c.jvmType());
            captureTypes.add(c.jvmType());
        }
        indyOnStack(sam, captureTypes, implName, implType);
        return sam.type();
    }

    private void indyOnStack(Sam sam, List<ClassDesc> captureTypes, String implName, MethodTypeDesc implType) {
        DirectMethodHandleDesc impl = MethodHandleDesc.ofMethod(gen.owner.isInterface
                ? DirectMethodHandleDesc.Kind.INTERFACE_STATIC : DirectMethodHandleDesc.Kind.STATIC,
                gen.owner.desc, implName, implType);
        code.invokedynamic(DynamicCallSiteDesc.of(METAFACTORY, sam.method(),
                MethodTypeDesc.of(sam.type(), captureTypes), sam.erased(), impl, sam.erased()));
    }

    // ==== SAM conversions ====

    ClassDesc samConversion(TExpr.SamConversion s) {
        return samValue(s.expr(), s.type());
    }

    /** A function value as an instance of a functional interface: a lambda or reference directly, else a wrapper. */
    ClassDesc samValue(TExpr value, KType samType) {
        TExpr e = ExprCodegen.unwrap(value);
        if (e instanceof TExpr.Lambda l) return lambda(l, samType);
        if (e instanceof TExpr.CallableReference r) return reference(r, samType);
        Sam sam = samOf(samType);
        ClassDesc valueType = gen.mapType(value.type());
        if (!valueType.isPrimitive() && !valueType.equals(TypeMapper.OBJECT) && valueType.equals(sam.type())) {
            gen.genAs(value, valueType);
            return valueType;
        }
        // Wraps the function value: a method calling its invoke with the interface method's arguments.
        int arity = sam.erased().parameterCount();
        Sam function = functionN(arity);
        String name = gen.owner.syntheticName(gen.methodName + "$sam");
        List<ClassDesc> params = new ArrayList<>();
        params.add(function.type());
        params.addAll(sam.erased().parameterList());
        MethodTypeDesc implType = MethodTypeDesc.of(sam.erased().returnType(), params);
        gen.owner.addMethod(name, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, implType,
                null, null, g -> {
                    CodeBuilder c = g.code;
                    c.aload(0);
                    int slot = 1;
                    for (ClassDesc p : sam.erased().parameterList()) {
                        c.loadLocal(java.lang.classfile.TypeKind.from(p), slot);
                        slot += java.lang.classfile.TypeKind.from(p).slotSize();
                        g.coerce(p, TypeMapper.OBJECT);
                    }
                    c.invokeinterface(function.type(), "invoke", function.erased());
                    g.coerce(TypeMapper.OBJECT, sam.erased().returnType());
                    g.returnValue(sam.erased().returnType());
                });
        gen.genAs(value, function.type());
        indyOnStack(sam, List.of(function.type()), name, implType);
        return sam.type();
    }

    /**
     * A function value as a suspend function: a FunctionN+1 whose invoke
     * calls the FunctionN's, ignoring the continuation.
     */
    ClassDesc suspendConversion(TExpr.SuspendConversion s) {
        int arity = ((KType.ClassType) kotlinbootstrap.types.Types.lowerBound(s.type())).args().size() - 1;
        Sam function = functionN(arity);
        Sam suspend = functionN(arity + 1);
        String name = gen.owner.syntheticName(gen.methodName + "$suspend");
        List<ClassDesc> params = new ArrayList<>();
        params.add(function.type());
        params.addAll(suspend.erased().parameterList());
        MethodTypeDesc implType = MethodTypeDesc.of(TypeMapper.OBJECT, params);
        gen.owner.addMethod(name, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, implType,
                null, null, g -> {
                    for (int i = 0; i <= arity; i++) g.code.aload(i);
                    g.code.invokeinterface(function.type(), "invoke", function.erased());
                    g.code.areturn();
                });
        gen.genAs(s.expr(), function.type());
        indyOnStack(suspend, List.of(function.type()), name, implType);
        return suspend.type();
    }

    // ==== References ====

    /**
     * A callable reference as a function object: a method taking the bound
     * receivers and what the target captures, then the function type's
     * parameters, calling the target with them as adapted.
     */
    ClassDesc reference(TExpr.CallableReference r, KType samType) {
        CallableSymbol target = r.target();
        if (!(kotlinbootstrap.types.Types.lowerBound(r.type()) instanceof KType.ClassType type)) {
            throw new CodegenException(r.pos(), "Unexpected reference type " + r.type());
        }
        if (!kotlinbootstrap.types.FunctionTypes.isFunctionType(type)) {
            return propertyReference(r);
        }
        List<KType> paramTypes = new ArrayList<>();
        for (int i = 0; i < type.args().size() - 1; i++) paramTypes.add(argType(type.args().get(i)));
        KType returnType = argType(type.args().getLast());
        boolean suspend = samType == null && FunctionTypes.kind(type) instanceof FunctionTypes.Kind k && k.isSuspend();
        Sam sam = samType != null ? samOf(samType) : functionN(paramTypes.size() + (suspend ? 1 : 0));
        // The bound receivers are evaluated now, into variables the method takes.
        List<ExprCodegen.Capture> captures = new ArrayList<>();
        List<ExprCodegen.Slot> boundValues = new ArrayList<>();
        LocalVariable boundDispatch = null;
        LocalVariable boundExtension = null;
        if (r.dispatchReceiver() != null && !(r.dispatchReceiver() instanceof TExpr.ObjectValue)) {
            boundDispatch = new LocalVariable("$receiver", r.dispatchReceiver().type(),
                    LocalVariable.Kind.TEMPORARY, false, null);
            ExprCodegen.Slot s = gen.spill(r.dispatchReceiver());
            boundValues.add(s);
            captures.add(new ExprCodegen.Capture(boundDispatch, s.type(), false));
        }
        if (r.extensionReceiver() != null) {
            boundExtension = new LocalVariable("$receiver", r.extensionReceiver().type(),
                    LocalVariable.Kind.TEMPORARY, false, null);
            ExprCodegen.Slot s = gen.spill(r.extensionReceiver());
            boundValues.add(s);
            captures.add(new ExprCodegen.Capture(boundExtension, s.type(), false));
        }
        // What a local function or local class constructor captures, passed on.
        List<ExprCodegen.Capture> targetCaptures = new ArrayList<>();
        if (target instanceof FunctionSymbol f && f.owner() == null
                && gen.env.lookup(f) instanceof LocalFunction lf) {
            targetCaptures.addAll(lf.captures());
        } else if (target instanceof FunctionSymbol f && f.isConstructor() && f.owner() instanceof ClassSymbol cls
                && cls.isLocal() && backend.generator(cls) != null) {
            targetCaptures.addAll(backend.generator(cls).captures());
        }
        for (ExprCodegen.Capture c : targetCaptures) captures.add(gen.capture(c.key()));
        List<LocalVariable> params = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            params.add(new LocalVariable("p" + i, paramTypes.get(i), LocalVariable.Kind.PARAMETER, false, null));
        }
        TExpr call = referenceCall(r, params, boundDispatch, boundExtension, returnType);
        boolean toUnit = r.adaptation() != null && r.adaptation().coercionToUnit();
        String name = gen.owner.syntheticName(gen.methodName + "$ref");
        MethodTypeDesc implType = implType(captures, sam.erased());
        ExprCodegen.Env enclosing = gen.env;
        gen.owner.addMethod(name, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, implType,
                null, enclosing, g -> {
                    g.bindCaptures(captures);
                    List<ExprCodegen.Slot> args = g.erasedParameters(sam.erased().parameterList(), paramTypes);
                    for (int i = 0; i < params.size(); i++) g.env.bind(params.get(i), args.get(i));
                    if (suspend) g.beginSuspend(args.getLast().slot());
                    ClassDesc ret = sam.erased().returnType();
                    if (toUnit) {
                        g.genAs(call, TypeMapper.VOID);
                        g.coerce(TypeMapper.VOID, ret);
                    } else {
                        g.genAs(call, ret);
                    }
                    g.returnValue(ret);
                });
        // The bound values, then the target's captures.
        List<ClassDesc> captureTypes = new ArrayList<>();
        int i = 0;
        for (ExprCodegen.Capture c : captures) {
            if (i < boundValues.size()) {
                gen.loadSlotValue(boundValues.get(i++));
            } else {
                gen.coerce(gen.loadForCapture(c.key()), c.jvmType());
            }
            captureTypes.add(c.jvmType());
        }
        indyOnStack(sam, captureTypes, name, implType);
        return sam.type();
    }

    private KType argType(TypeArg a) {
        return a.isStar() || a.type() == null ? backend.resolver.types().nullableAny() : a.type();
    }

    /** The call a reference makes, of its parameters as variables. */
    private TExpr referenceCall(TExpr.CallableReference r, List<LocalVariable> params, LocalVariable boundDispatch,
                                LocalVariable boundExtension, KType returnType) {
        CallableSymbol target = r.target();
        int pos = r.pos();
        int next = 0;
        TExpr dispatch = null;
        TExpr extension = null;
        boolean member = target.owner() instanceof ClassSymbol && !Calls.isStatic(target)
                && !(target instanceof FunctionSymbol f && f.isConstructor());
        if (boundDispatch != null) {
            dispatch = new TExpr.LocalGet(pos, boundDispatch.type(), boundDispatch);
        } else if (r.dispatchReceiver() instanceof TExpr.ObjectValue o) {
            dispatch = o;
        } else if (member && !isObject(target.owner())) {
            dispatch = get(params.get(next++), pos);
        } else if (member) {
            ClassSymbol object = (ClassSymbol) target.owner();
            dispatch = new TExpr.ObjectValue(pos, backend.resolver.types().defaultType(object), object);
        }
        if (target.signature().receiver() != null) {
            extension = boundExtension != null ? new TExpr.LocalGet(pos, boundExtension.type(), boundExtension)
                    : get(params.get(next++), pos);
        }
        if (target instanceof PropertySymbol p) {
            return new TExpr.PropertyGet(pos, returnType, p, r.typeArgs(), dispatch, extension, r.contextArgs(),
                    false);
        }
        FunctionSymbol f = (FunctionSymbol) target;
        List<ValueParamSymbol> fParams = f.signature().params();
        List<TExpr.Argument> args = new ArrayList<>();
        if (r.adaptation() == null) {
            for (int i = 0; i < fParams.size() && next < params.size(); i++) {
                args.add(new TExpr.Argument(i, get(params.get(next++), pos)));
            }
        } else {
            List<Integer> to = r.adaptation().argumentParams();
            // Several arguments may go to the vararg parameter, as its elements.
            List<TExpr.VarargElement> varargElements = new ArrayList<>();
            int varargIndex = -1;
            for (int i = 0; i < to.size() && next < params.size(); i++) {
                int param = to.get(i);
                TExpr value = get(params.get(next++), pos);
                if (fParams.get(param).isVararg()) {
                    varargIndex = param;
                    varargElements.add(new TExpr.VarargElement(value, r.adaptation().varargAsArray()));
                } else {
                    args.add(new TExpr.Argument(param, value));
                }
            }
            if (varargIndex >= 0) {
                KType arrayType = backend.resolver.types().varargArrayType(fParams.get(varargIndex).type());
                args.add(new TExpr.Argument(varargIndex, new TExpr.Vararg(pos, arrayType, varargElements)));
            }
        }
        return new TExpr.Call(pos, returnType, f, r.typeArgs(), dispatch, extension, r.contextArgs(), args, false,
                TExpr.CallOrigin.PLAIN);
    }

    private static boolean isObject(Object owner) {
        return owner instanceof ClassSymbol cls && cls.isObject();
    }

    private static TExpr get(LocalVariable v, int pos) {
        return new TExpr.LocalGet(pos, v.type(), v);
    }

    /**
     * A property reference as a reflection object, as kotlinc's: a subclass
     * of kotlin.jvm.internal's PropertyReferenceNImpl (MutablePropertyReferenceNImpl
     * for a var) whose get and set call static synthetic methods of this
     * class, which access the property; a bound receiver is kept by the
     * reference. Only its name and accessors work without kotlin-reflect.
     */
    private ClassDesc propertyReference(TExpr.CallableReference r) {
        PropertySymbol p = (PropertySymbol) r.target();
        KType.ClassType type = (KType.ClassType) kotlinbootstrap.types.Types.lowerBound(r.type());
        List<KType> paramTypes = new ArrayList<>();
        for (int i = 0; i < type.args().size() - 1; i++) paramTypes.add(argType(type.args().get(i)));
        KType valueType = argType(type.args().getLast());
        int arity = paramTypes.size();
        boolean mutable = p.isVar() && type.symbol().name().startsWith("KMutableProperty");
        ClassDesc impl = ClassDesc.ofInternalName("kotlin/jvm/internal/"
                + (mutable ? "MutablePropertyReference" : "PropertyReference") + arity + "Impl");
        TExpr bound = r.dispatchReceiver() != null && !(r.dispatchReceiver() instanceof TExpr.ObjectValue)
                ? r.dispatchReceiver() : r.extensionReceiver();
        LocalVariable boundVar = bound == null ? null
                : new LocalVariable("$receiver", bound.type(), LocalVariable.Kind.TEMPORARY, false, null);
        // The accessors: static methods taking the bound receiver, the receivers, and for set, the value.
        List<LocalVariable> params = new ArrayList<>();
        for (int i = 0; i < arity; i++) {
            params.add(new LocalVariable("p" + i, paramTypes.get(i), LocalVariable.Kind.PARAMETER, false, null));
        }
        LocalVariable boundDispatch = bound != null && bound == r.dispatchReceiver() ? boundVar : null;
        LocalVariable boundExtension = bound != null && bound == r.extensionReceiver() ? boundVar : null;
        TExpr.PropertyGet get = (TExpr.PropertyGet) referenceCall(r, params, boundDispatch, boundExtension, valueType);
        List<ClassDesc> accessorParams = new ArrayList<>();
        if (bound != null) accessorParams.add(TypeMapper.OBJECT);
        for (int i = 0; i < arity; i++) accessorParams.add(TypeMapper.OBJECT);
        String getName = gen.owner.syntheticName(gen.methodName + "$get");
        MethodTypeDesc getType = MethodTypeDesc.of(TypeMapper.OBJECT, accessorParams);
        int flags = ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC;
        gen.owner.addMethod(getName, flags, getType, null, gen.env, g -> {
            bindAccessorParams(g, getType, boundVar, params);
            g.genAs(get, TypeMapper.OBJECT);
            g.returnValue(TypeMapper.OBJECT);
        });
        String setName = null;
        MethodTypeDesc setType = null;
        if (mutable) {
            setName = gen.owner.syntheticName(gen.methodName + "$set");
            List<ClassDesc> setParams = new ArrayList<>(accessorParams);
            setParams.add(TypeMapper.OBJECT);
            setType = MethodTypeDesc.of(TypeMapper.VOID, setParams);
            LocalVariable value = new LocalVariable("value", valueType, LocalVariable.Kind.PARAMETER, false, null);
            List<LocalVariable> setVars = new ArrayList<>(params);
            setVars.add(value);
            TExpr.PropertySet set = new TExpr.PropertySet(r.pos(), backend.resolver.types().unit(), p,
                    get.typeArgs(), get.dispatchReceiver(), get.extensionReceiver(), get.contextArgs(), false,
                    get(value, r.pos()));
            MethodTypeDesc st = setType;
            gen.owner.addMethod(setName, flags, setType, null, gen.env, g -> {
                bindAccessorParams(g, st, boundVar, setVars);
                g.genAs(set, TypeMapper.VOID);
                g.returnValue(TypeMapper.VOID);
            });
        }
        ClassDesc owner = p.owner() instanceof ClassSymbol cls ? gen.types.classDesc(cls) : gen.owner.desc;
        String signature = backend.names.getterName(p) + gen.types.getterType(p).descriptorString();
        ClassDesc ref = backend.propertyReferenceClass(gen.owner, impl, arity, bound != null, owner, p.name(),
                signature, getName, getType, setName, setType);
        code.new_(ref).dup();
        if (bound != null) gen.genAs(bound, TypeMapper.OBJECT);
        code.invokespecial(ref, "<init>", bound != null ? MethodTypeDesc.of(TypeMapper.VOID, TypeMapper.OBJECT)
                : MethodTypeDesc.of(TypeMapper.VOID));
        return impl;
    }

    /** Binds an accessor's parameters, Objects, to the variables, as their types. */
    private static void bindAccessorParams(ExprCodegen g, MethodTypeDesc type, LocalVariable bound,
                                           List<LocalVariable> vars) {
        List<KType> types = new ArrayList<>();
        if (bound != null) types.add(bound.type());
        for (LocalVariable v : vars) types.add(v.type());
        List<ExprCodegen.Slot> slots = g.erasedParameters(type.parameterList(), types);
        int k = 0;
        if (bound != null) g.env.bind(bound, slots.get(k++));
        for (LocalVariable v : vars) g.env.bind(v, slots.get(k++));
    }

    // ==== Local functions ====

    /** Declares a local function: compiled to a static method of the class, taking what it captures first. */
    void localFunction(TExpr.LocalFunctionDecl d) {
        FunctionSymbol f = d.function();
        CheckedFunction body = d.body();
        backend.closures.registerLocalFunction(f, body);
        List<ExprCodegen.Capture> captures = captures(backend.closures.of(body));
        String name = gen.owner.syntheticName(gen.methodName + "$" + f.name());
        List<ClassDesc> params = new ArrayList<>();
        for (ExprCodegen.Capture c : captures) params.add(c.jvmType());
        params.addAll(gen.types.parameterTypes(f));
        ClassDesc returnType = gen.types.mapReturnType(f);
        MethodTypeDesc type = MethodTypeDesc.of(returnType, params);
        LocalFunction lf = new LocalFunction(gen.owner.desc, name, type, gen.owner.isInterface, captures, body);
        gen.env.bind(f, lf);
        gen.owner.addMethod(name, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, type,
                body, gen.env, g -> {
                    g.bindCaptures(captures);
                    for (LocalVariable p : body.contextParams()) g.parameter(p, g.mapType(p.type()));
                    if (body.receiverType() != null) g.parameter(f, g.mapType(body.receiverType()));
                    List<ValueParamSymbol> symbols = f.signature().params();
                    for (int i = 0; i < body.params().size(); i++) {
                        LocalVariable p = body.params().get(i);
                        g.parameter(p, symbols.get(i).isVararg() ? g.mapType(p.type()) : g.mapType(p.type()));
                    }
                    g.body(body.body());
                });
    }

    /** Calls a local function: its captures, then receivers and arguments, with defaults evaluated here. */
    ClassDesc callLocalFunction(TExpr.Call c) {
        FunctionSymbol f = c.function();
        if (!(gen.env.lookup(f) instanceof LocalFunction lf)) {
            throw new CodegenException(c.pos(), "Local function not declared: " + f);
        }
        CheckedFunction body = lf.body();
        // The arguments to slots, in source order, bound to the parameters for the defaults to read.
        ExprCodegen.Env saved = gen.env;
        gen.env = new ExprCodegen.Env(saved);
        List<ExprCodegen.Slot> receivers = new ArrayList<>();
        for (TExpr a : c.contextArgs()) receivers.add(gen.spill(a));
        if (c.extensionReceiver() != null) receivers.add(gen.spill(c.extensionReceiver()));
        List<LocalVariable> params = body.params();
        ExprCodegen.Slot[] values = new ExprCodegen.Slot[params.size()];
        for (TExpr.Argument a : c.args()) {
            ClassDesc type = gen.mapType(params.get(a.param()).type());
            values[a.param()] = gen.spillAs(a.value(), type);
            gen.env.bind(params.get(a.param()), values[a.param()]);
        }
        for (int i = 0; i < params.size(); i++) {
            if (values[i] != null) continue;
            TExpr dv = i < body.defaultValues().size() ? body.defaultValues().get(i) : null;
            ClassDesc type = gen.mapType(params.get(i).type());
            if (dv == null) {
                gen.emptyArray(type);
                values[i] = new ExprCodegen.Slot(gen.newSlot(type), type, false);
                code.astore(values[i].slot());
            } else {
                values[i] = gen.spillAs(dv, type);
            }
            gen.env.bind(params.get(i), values[i]);
        }
        gen.env = saved;
        for (ExprCodegen.Capture capture : lf.captures()) {
            gen.coerce(gen.loadForCapture(capture.key()), capture.jvmType());
        }
        List<ClassDesc> jvmParams = lf.type().parameterList();
        int k = lf.captures().size();
        for (ExprCodegen.Slot s : receivers) {
            gen.loadSlotValue(s);
            gen.coerce(s.type(), jvmParams.get(k++));
        }
        for (ExprCodegen.Slot s : values) {
            gen.loadSlotValue(s);
            gen.coerce(s.type(), jvmParams.get(k++));
        }
        code.invokestatic(lf.owner(), lf.name(), lf.type(), lf.ownerIsInterface());
        return lf.type().returnType();
    }

    static boolean isLocalClass(ClassSymbol cls) {
        return cls instanceof KotlinClassSymbol k && k.isLocal();
    }
}
