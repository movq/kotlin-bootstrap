package kotlinbootstrap.jvm;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.check.CheckedDelegate;
import kotlinbootstrap.check.CheckedFunction;
import kotlinbootstrap.check.ConstantValue;
import kotlinbootstrap.check.LocalVariable;
import kotlinbootstrap.check.TExpr;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.KType;

/**
 * Generates a class file: of a class, object or interface with its
 * members, or of a file's top-level declarations, as kotlinc's
 * ClassCodegen. Methods are generated in turn from a queue, to which
 * generating code adds the synthetic methods of its lambdas and local
 * functions.
 */
final class ClassCodegen {
    private static final ClassDesc ENUM = ClassDesc.ofInternalName("java/lang/Enum");
    private static final ClassDesc ARRAYS = ClassDesc.of("java.util.Arrays");

    /** A method to generate, with the environment its code is in if it is a closure's. */
    private record MethodJob(String name, int flags, MethodTypeDesc type, CheckedFunction function,
                             ExprCodegen.Env env, Consumer<ExprCodegen> body) {
    }

    /** A field a local class keeps a captured value in. */
    private record CapturedField(String name, ExprCodegen.Capture capture) {
    }

    final JvmBackend backend;
    /** The class; null for a file's part. */
    final KotlinClassSymbol cls;
    final FileContext file;
    final String internalName;
    final ClassDesc desc;
    final boolean isInterface;
    /** The class whose nest this one is in; itself for the host. */
    String nestHost;
    private final Deque<MethodJob> methods = new ArrayDeque<>();
    private final Set<String> declaredMethods = new HashSet<>();
    private final Map<String, Integer> syntheticNames = new HashMap<>();
    private final List<String[]> fields = new ArrayList<>();
    private List<CapturedField> captured;
    private ClassBuilder builder;
    /** Whether a method has exception handlers, whose empty ranges are dropped. */
    boolean hasCatches;

    /** A file's part: its top-level functions and properties. */
    ClassCodegen(JvmBackend backend, FileContext file, String partName) {
        this.backend = backend;
        this.cls = null;
        this.file = file;
        this.internalName = partName;
        this.desc = ClassDesc.ofInternalName(partName);
        this.isInterface = false;
    }

    ClassCodegen(JvmBackend backend, KotlinClassSymbol cls, String internalName) {
        this.backend = backend;
        this.cls = cls;
        this.file = cls.file();
        this.internalName = internalName;
        this.desc = ClassDesc.ofInternalName(internalName);
        this.isInterface = cls.isInterface();
    }

    // ==== Methods ====

    /** A name for a synthetic method, unique in the class. */
    String syntheticName(String base) {
        base = base.replace('<', '_').replace('>', '_');
        int n = syntheticNames.merge(base, 1, Integer::sum) - 1;
        return base + "$" + n;
    }

    /**
     * Queues a method to generate.
     *
     * @param function the function whose returns return from the method, if any
     * @param env      for a closure, the environment of the code around it
     * @param body     generates the code, binding the parameters first; null for an abstract method
     */
    void addMethod(String name, int flags, MethodTypeDesc type, CheckedFunction function, ExprCodegen.Env env,
                   Consumer<ExprCodegen> body) {
        declaredMethods.add(name + type.descriptorString());
        methods.add(new MethodJob(name, flags, type, function, env, body));
    }

    private void generateMethod(MethodJob job) {
        builder.withMethod(job.name(), job.type(), job.flags(), mb -> {
            if (job.body() == null) return;
            mb.withCode(code -> {
                boolean isStatic = (job.flags() & ClassFile.ACC_STATIC) != 0;
                ExprCodegen g = new ExprCodegen(this, code, isStatic, job.function(), job.name(),
                        job.type().returnType());
                if (job.env() != null) g.env = new ExprCodegen.Env(job.env());
                try {
                    job.body().accept(g);
                } catch (CodegenException e) {
                    throw e.in(file != null ? file.file().source() : null);
                } catch (RuntimeException e) {
                    CodegenException ce = new CodegenException(-1, "In " + internalName + "." + job.name() + ": " + e);
                    ce.initCause(e);
                    throw ce.in(file != null ? file.file().source() : null);
                }
            });
        });
    }

    // ==== Generation ====

    byte[] generate() {
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(backend.hierarchy()));
        byte[] bytes = cf.build(desc, cb -> {
            builder = cb;
            cb.withVersion(ClassFile.JAVA_17_VERSION, 0);
            if (file != null) {
                String path = file.file().source().path();
                cb.with(SourceFileAttribute.of(path.substring(path.lastIndexOf('/') + 1)));
            }
            if (cls == null) {
                cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
                cb.withSuperclass(TypeMapper.OBJECT);
                filePart();
            } else {
                classHeader(cb);
                classMembers();
            }
            while (!methods.isEmpty()) generateMethod(methods.removeFirst());
            backend.nests.member(this, cb);
        });
        return hasCatches ? dropEmptyCatches(cf, bytes) : bytes;
    }

    /**
     * Drops the ranges of exception handlers that cover no code, which the
     * JVM rejects: a try's ranges are split around jumps out through its
     * finally block, and may end where they start.
     */
    private static byte[] dropEmptyCatches(ClassFile cf, byte[] bytes) {
        return cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethods((mb, me) -> {
            if (!(me instanceof CodeAttribute code)) {
                mb.with(me);
                return;
            }
            mb.transformCode(code, (cob, coe) -> {
                if (coe instanceof ExceptionCatch c && code.labelToBci(c.tryStart()) == code.labelToBci(c.tryEnd())) {
                    return;
                }
                cob.with(coe);
            });
        }));
    }

    private void field(int flags, String name, ClassDesc type, ConstantDesc constant) {
        for (String[] f : fields) if (f[0].equals(name)) return;
        fields.add(new String[]{name});
        builder.withField(name, type, fb -> {
            fb.withFlags(flags);
            if (constant != null) fb.with(ConstantValueAttribute.of(constant));
        });
    }

    // ==== File parts ====

    private void filePart() {
        List<CallableSymbol> callables = backend.topLevelCallables(file);
        List<PropertySymbol> initialized = new ArrayList<>();
        for (CallableSymbol s : callables) {
            if (s instanceof FunctionSymbol f) function(f);
            else if (s instanceof PropertySymbol p) {
                property(p);
                initialized.add(p);
            }
        }
        if (initialized.stream().anyMatch(this::hasInitializer)) {
            addMethod("<clinit>", ClassFile.ACC_STATIC, MethodTypeDesc.of(TypeMapper.VOID), null, null, g -> {
                for (PropertySymbol p : initialized) initializeProperty(g, p);
                g.returnValue(TypeMapper.VOID);
            });
        }
        mainBridge(callables);
    }

    /** {@code fun main()} gets a {@code main(String[])} calling it, as kotlinc's MainFunctionDetector. */
    private void mainBridge(List<CallableSymbol> callables) {
        boolean hasMainWithArgs = false;
        FunctionSymbol main = null;
        for (CallableSymbol s : callables) {
            if (s instanceof FunctionSymbol f && f.name().equals("main") && f.signature().receiver() == null
                    && f.typeParams().isEmpty()) {
                if (f.signature().params().isEmpty()) main = f;
                else hasMainWithArgs = true;
            }
        }
        if (main == null || hasMainWithArgs) return;
        MethodTypeDesc type = MethodTypeDesc.of(TypeMapper.VOID, TypeMapper.STRING.arrayType());
        FunctionSymbol target = main;
        addMethod("main", ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, type, null, null,
                g -> {
                    MethodTypeDesc t = backend.types.methodType(target);
                    g.code.invokestatic(desc, backend.names.functionName(target), t);
                    g.pop(t.returnType());
                    g.returnValue(TypeMapper.VOID);
                });
    }

    /** A facade of a multifile class: methods calling those of its parts. */
    static byte[] multifileFacade(JvmBackend backend, String name, List<FileContext> parts) {
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(backend.hierarchy()));
        return cf.build(ClassDesc.ofInternalName(name), cb -> {
            cb.withVersion(ClassFile.JAVA_17_VERSION, 0);
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            cb.withSuperclass(TypeMapper.OBJECT);
            Set<String> seen = new HashSet<>();
            for (FileContext file : parts) {
                ClassDesc part = ClassDesc.ofInternalName(backend.names.filePart(file));
                for (CallableSymbol s : backend.topLevelCallables(file)) {
                    if (s.visibility() == Visibility.PRIVATE) continue;
                    if (s instanceof FunctionSymbol f) {
                        delegate(cb, seen, part, backend.names.functionName(f), backend.types.methodType(f));
                        if (hasOwnDefaults(f)) {
                            delegate(cb, seen, part, backend.names.functionName(f) + "$default",
                                    Calls.defaultMethodType(backend, f));
                        }
                    } else if (s instanceof PropertySymbol p && !Calls.isDirectFieldAccess(p)
                            && !p.has(Modifier.CONST) && !JvmNames.has(JvmNames.modifiers(p), "JvmField")) {
                        delegate(cb, seen, part, backend.names.getterName(p), backend.types.getterType(p));
                        if (p.isVar()) delegate(cb, seen, part, backend.names.setterName(p), backend.types.setterType(p));
                    }
                }
            }
        });
    }

    private static void delegate(ClassBuilder cb, Set<String> seen, ClassDesc part, String name, MethodTypeDesc type) {
        if (!seen.add(name + type.descriptorString())) return;
        cb.withMethodBody(name, type, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
            int slot = 0;
            for (ClassDesc p : type.parameterList()) {
                code.loadLocal(TypeKind.from(p), slot);
                slot += TypeKind.from(p).slotSize();
            }
            code.invokestatic(part, name, type);
            if (type.returnType().equals(TypeMapper.VOID)) code.return_();
            else code.return_(TypeKind.from(type.returnType()));
        });
    }

    // ==== Class header ====

    private void classHeader(ClassBuilder cb) {
        int flags = ClassFile.ACC_SUPER;
        if (!cls.isLocal() && cls.visibility() != Visibility.PRIVATE) flags |= ClassFile.ACC_PUBLIC;
        if (cls.outer() != null && !cls.isLocal()) flags |= ClassFile.ACC_PUBLIC;
        switch (cls.kind()) {
            case INTERFACE -> flags = flags & ~ClassFile.ACC_SUPER | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT;
            case ANNOTATION_CLASS -> flags = flags & ~ClassFile.ACC_SUPER | ClassFile.ACC_INTERFACE
                    | ClassFile.ACC_ABSTRACT | ClassFile.ACC_ANNOTATION;
            case ENUM_CLASS -> {
                flags |= ClassFile.ACC_ENUM;
                if (cls.modality() == Modality.ABSTRACT) flags |= ClassFile.ACC_ABSTRACT;
                else if (cls.modality() == Modality.FINAL) flags |= ClassFile.ACC_FINAL;
            }
            default -> {
                if (cls.modality() == Modality.ABSTRACT || cls.modality() == Modality.SEALED) {
                    flags |= ClassFile.ACC_ABSTRACT;
                } else if (cls.modality() == Modality.FINAL) {
                    flags |= ClassFile.ACC_FINAL;
                }
            }
        }
        cb.withFlags(flags);
        cb.withSuperclass(superclass());
        List<ClassDesc> interfaces = new ArrayList<>();
        for (KType t : cls.supertypes()) {
            if (t instanceof KType.ClassType c && c.symbol().isInterface()) {
                ClassDesc d = backend.types.classDesc(c.symbol());
                if (!interfaces.contains(d)) interfaces.add(d);
            }
        }
        if (cls.kind() == ClassKind.ANNOTATION_CLASS) interfaces.add(ClassDesc.of("java.lang.annotation.Annotation"));
        cb.withInterfaceSymbols(interfaces);
    }

    /** The superclass: the first class among the supertypes, java.lang.Enum for an enum. */
    ClassDesc superclass() {
        if (cls.kind() == ClassKind.ENUM_CLASS) return ENUM;
        ClassSymbol s = superclassSymbol();
        return s == null || TypeMapper.isClass(s, "kotlin.Any") ? TypeMapper.OBJECT : backend.types.classDesc(s);
    }

    private ClassSymbol superclassSymbol() {
        for (KType t : cls.supertypes()) {
            if (t instanceof KType.ClassType c && !c.symbol().isInterface()) return c.symbol();
        }
        return null;
    }

    /** An enum entry's class, whose constructor takes the name and ordinal for the enum's. */
    private boolean isEnumEntryClass() {
        ClassSymbol s = superclassSymbol();
        return s != null && s.kind() == ClassKind.ENUM_CLASS && cls.isLocal();
    }

    // ==== Class members ====

    private void classMembers() {
        if (cls.kind() == ClassKind.ANNOTATION_CLASS) {
            annotationMembers();
            return;
        }
        List<PropertySymbol> properties = new ArrayList<>();
        if (cls.decl().primaryConstructor() != null) {
            for (Decl.Param param : cls.decl().primaryConstructor().params()) {
                if (param.valOrVar() == Decl.ValOrVar.NONE) continue;
                for (PropertySymbol p : cls.members().properties(param.name())) {
                    if (p.source() == param) properties.add(p);
                }
            }
        }
        for (Decl d : allMembers()) {
            if (d instanceof Decl.Property pd) {
                for (PropertySymbol p : cls.members().properties(pd.name())) if (p.source() == d) properties.add(p);
            }
        }
        for (PropertySymbol p : properties) property(p);
        for (Decl d : allMembers()) {
            if (d instanceof Decl.Function fd) {
                for (FunctionSymbol f : cls.members().functions(fd.name())) if (f.source() == d) function(f);
            }
        }
        if (captured != null) {
            for (CapturedField f : captured) {
                field(ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC, f.name(), f.capture().jvmType(), null);
            }
        }
        if (cls.isInner() && !cls.isLocal() && cls.outer() != null) {
            field(ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC, "this$0", backend.types.classDesc(cls.outer()), null);
        }
        if (!cls.isInterface()) constructors();
        synthesized();
        companionAndObject();
        bridges();
    }

    /** The members, with those of companion blocks. */
    private List<Decl> allMembers() {
        List<Decl> result = new ArrayList<>();
        for (Decl d : cls.decl().members()) {
            if (d instanceof Decl.CompanionBlock b) result.addAll(b.members());
            else result.add(d);
        }
        return result;
    }

    /** An annotation class's parameters, as abstract methods. */
    private void annotationMembers() {
        if (cls.decl().primaryConstructor() == null) return;
        for (Decl.Param param : cls.decl().primaryConstructor().params()) {
            for (PropertySymbol p : cls.members().properties(param.name())) {
                addMethod(p.name(), ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                        MethodTypeDesc.of(backend.types.mapType(p.returnType())), null, null, null);
            }
        }
    }

    private static int visibilityFlags(Visibility v) {
        return switch (v) {
            case PRIVATE -> ClassFile.ACC_PRIVATE;
            case PROTECTED -> ClassFile.ACC_PROTECTED;
            case PACKAGE -> 0;
            default -> ClassFile.ACC_PUBLIC;
        };
    }

    // ==== Functions ====

    private boolean isAbstract(FunctionSymbol f) {
        return f.modality() == Modality.ABSTRACT
                || f.source() instanceof Decl.Function d && d.body() == null && !f.has(Modifier.EXTERNAL);
    }

    private void function(FunctionSymbol f) {
        if (f.has(Modifier.EXPECT)) return;
        boolean isStatic = Calls.isStatic(f);
        int flags = visibilityFlags(f.visibility()) | (isStatic ? ClassFile.ACC_STATIC : 0);
        // Private members of interfaces are callable only as interface methods, so public ones here.
        if (isInterface && !isStatic) flags = ClassFile.ACC_PUBLIC;
        if (f.source() instanceof Decl.Function d && d.params().stream().anyMatch(p -> p.modifiers().has(Modifier.VARARG))
                && d.params().getLast().modifiers().has(Modifier.VARARG)) {
            flags |= ClassFile.ACC_VARARGS;
        }
        MethodTypeDesc type = backend.types.methodType(f);
        String name = backend.names.functionName(f);
        if (isAbstract(f)) {
            addMethod(name, flags | ClassFile.ACC_ABSTRACT, type, null, null, null);
            return;
        }
        if (f.has(Modifier.EXTERNAL)) {
            addMethod(name, flags | ClassFile.ACC_NATIVE, type, null, null, null);
            return;
        }
        CheckedFunction body = backend.checker.checkedFunction(f);
        addMethod(name, flags, type, body, null, g -> {
            bindParameters(g, f, body, type.parameterList(), 0);
            g.body(body.body());
        });
        if (hasOwnDefaults(f)) defaultMethod(f, body, isStatic);
    }

    static boolean hasOwnDefaults(FunctionSymbol f) {
        for (ValueParamSymbol p : f.signature().params()) if (p.hasDefault()) return true;
        return false;
    }

    /**
     * Binds the parameters of a function in their slots: context
     * parameters, the extension receiver, then the value parameters.
     *
     * @param types the JVM parameter types, from the index of the first
     */
    private void bindParameters(ExprCodegen g, CallableSymbol f, CheckedFunction body, List<ClassDesc> types,
                                int first) {
        int i = first;
        for (LocalVariable p : body.contextParams()) g.parameter(p, types.get(i++));
        if (f.signature().receiver() != null) {
            ExprCodegen.Slot s = g.parameter(f, types.get(i++));
            g.env.bind(body, s);
        }
        for (LocalVariable p : body.params()) {
            ExprCodegen.Slot s = g.parameter(p, types.get(i++));
            if (p.param() != null) g.env.bind(p.param(), s);
        }
    }

    /**
     * The {@code $default} method of a function with default values: the
     * parameters masked are given theirs, then the function is called.
     */
    private void defaultMethod(FunctionSymbol f, CheckedFunction body, boolean isStatic) {
        MethodTypeDesc type = Calls.defaultMethodType(backend, f);
        MethodTypeDesc target = backend.types.methodType(f);
        String name = backend.names.functionName(f);
        int flags = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC;
        addMethod(name + "$default", flags, type, null, null, g -> {
            List<ClassDesc> params = type.parameterList();
            int first = 0;
            if (!isStatic) {
                ExprCodegen.Slot self = g.parameter(cls, params.getFirst());
                g.env.bind(cls, self);
                first = 1;
            }
            List<ExprCodegen.Slot> slots = new ArrayList<>();
            int contextCount = body.contextParams().size() + (f.signature().receiver() != null ? 1 : 0);
            bindParameters(g, f, body, params, first);
            for (int i = first; i < first + contextCount + body.params().size(); i++) {
                slots.add(null);
            }
            int maskSlot = 0;
            int slot = 0;
            for (int i = 0; i < first + contextCount + body.params().size(); i++) {
                slot += TypeKind.from(params.get(i)).slotSize();
            }
            maskSlot = slot;
            int valueSlot = 0;
            for (int i = 0; i < first + contextCount; i++) valueSlot += TypeKind.from(params.get(i)).slotSize();
            for (int i = 0; i < body.params().size(); i++) {
                ClassDesc t = params.get(first + contextCount + i);
                TExpr dv = i < body.defaultValues().size() ? body.defaultValues().get(i) : null;
                if (dv != null) {
                    Label skip = g.code.newLabel();
                    g.code.iload(maskSlot + (i / 32)).loadConstant(1 << (i % 32)).iand().ifeq(skip);
                    g.genAs(dv, t);
                    g.code.storeLocal(TypeKind.from(t), valueSlot);
                    g.code.labelBinding(skip);
                }
                valueSlot += TypeKind.from(t).slotSize();
            }
            int s = 0;
            for (int i = 0; i < first + contextCount + body.params().size(); i++) {
                g.code.loadLocal(TypeKind.from(params.get(i)), s);
                s += TypeKind.from(params.get(i)).slotSize();
            }
            if (isStatic) {
                g.code.invokestatic(f.owner() instanceof ClassSymbol ? desc : desc, name, target, isInterface);
            } else if (isInterface) {
                g.code.invokeinterface(desc, name, target);
            } else {
                g.code.invokevirtual(desc, name, target);
            }
            g.returnValue(target.returnType());
        });
    }

    // ==== Properties ====

    private boolean hasInitializer(PropertySymbol p) {
        if (p.has(Modifier.CONST)) return false;
        return p.source() instanceof Decl.Property d && (d.initializer() != null || d.delegate() != null);
    }

    /** Whether a property has a backing field, as kotlinc's: one its default accessors or {@code field} use. */
    boolean hasBackingField(PropertySymbol p) {
        if (p.source() instanceof Decl.Param) return true;
        if (!(p.source() instanceof Decl.Property d)) return false;
        if (d.delegate() != null || p.modality() == Modality.ABSTRACT || p.has(Modifier.EXPECT)
                || p.signature().receiver() != null || isInterface) {
            return false;
        }
        if (d.initializer() != null || p.has(Modifier.LATEINIT)) return true;
        if (d.getter() == null || d.getter().body() == null) return true;
        if (p.isVar() && (d.setter() == null || d.setter().body() == null)) return true;
        return usesField(backend.checker.checkedAccessor(p, true), p)
                || p.isVar() && usesField(backend.checker.checkedAccessor(p, false), p);
    }

    private static boolean usesField(CheckedFunction accessor, PropertySymbol p) {
        if (accessor == null) return false;
        boolean[] found = new boolean[1];
        scanField(accessor.body(), p, found);
        return found[0];
    }

    private static void scanField(TExpr e, PropertySymbol p, boolean[] found) {
        if (e == null || found[0]) return;
        switch (e) {
            case TExpr.FieldGet g when g.property() == p -> found[0] = true;
            case TExpr.FieldSet s when s.property() == p -> found[0] = true;
            case TExpr.Lambda l -> scanField(l.function().body(), p, found);
            default -> Closures.children(e, c -> scanField(c, p, found));
        }
    }

    private void property(PropertySymbol p) {
        if (p.has(Modifier.EXPECT)) return;
        Members.FieldRef f = Members.field(backend, p);
        boolean jvmField = JvmNames.has(JvmNames.modifiers(p), "JvmField");
        if (p.has(Modifier.CONST)) {
            ConstantValue v = backend.checker.constValue(p);
            ConstantDesc constant = v instanceof ConstantValue.Primitive prim ? constantDesc(prim.value()) : null;
            fieldIn(f, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL, constant);
            return;
        }
        if (hasBackingField(p)) {
            int flags = jvmField || p.has(Modifier.LATEINIT) ? visibilityFlags(p.visibility()) : ClassFile.ACC_PRIVATE;
            if (f.isStatic()) flags |= ClassFile.ACC_STATIC;
            if (!p.isVar() && !f.isStatic() && !jvmField) flags |= ClassFile.ACC_FINAL;
            fieldIn(f, flags, null);
        }
        if (p.source() instanceof Decl.Property d && d.delegate() != null) {
            Members.FieldRef df = Members.delegateField(backend, p);
            builderField(df, ClassFile.ACC_PRIVATE | (df.isStatic() ? ClassFile.ACC_STATIC : 0));
        }
        if (jvmField || Calls.isDirectFieldAccess(p)) return;
        accessor(p, true);
        if (p.isVar()) accessor(p, false);
    }

    /** A field, declared here, or in the outer class for a companion's static one. */
    private void fieldIn(Members.FieldRef f, int flags, ConstantDesc constant) {
        if (f.owner().equals(desc)) {
            field(flags, f.name(), f.type(), constant);
        } else {
            ClassCodegen outer = backend.generator(cls.outer());
            if (outer != null) outer.pendingFields.add(new PendingField(flags, f.name(), f.type(), constant));
        }
    }

    private void builderField(Members.FieldRef f, int flags) {
        fieldIn(f, flags, null);
    }

    /** Fields the companion declares in its outer class, added when that is generated. */
    record PendingField(int flags, String name, ClassDesc type, ConstantDesc constant) {
    }

    final List<PendingField> pendingFields = new ArrayList<>();

    private static ConstantDesc constantDesc(Object value) {
        return switch (value) {
            case Boolean b -> b ? 1 : 0;
            case Character c -> (int) c;
            case Byte b -> (int) b;
            case Short s -> (int) s;
            case Integer i -> i;
            case Long l -> l;
            case Float f -> f;
            case Double d -> d;
            case String s -> s;
            case null, default -> null;
        };
    }

    private void accessor(PropertySymbol p, boolean getter) {
        boolean isStatic = Calls.isStatic(p);
        Visibility visibility = getter ? p.visibility() : p.setterVisibility();
        int flags = visibilityFlags(visibility == null ? p.visibility() : visibility)
                | (isStatic ? ClassFile.ACC_STATIC : 0);
        if (isInterface && !isStatic) flags = ClassFile.ACC_PUBLIC;
        String name = getter ? backend.names.getterName(p) : backend.names.setterName(p);
        MethodTypeDesc type = getter ? backend.types.getterType(p) : backend.types.setterType(p);
        Decl.Property d = p.source() instanceof Decl.Property dp ? dp : null;
        Decl.Accessor declared = d == null ? null : getter ? d.getter() : d.setter();
        boolean isAbstract = p.modality() == Modality.ABSTRACT
                || isInterface && (declared == null || declared.body() == null) && (d == null || d.delegate() == null);
        if (isAbstract) {
            addMethod(name, flags | ClassFile.ACC_ABSTRACT, type, null, null, null);
            return;
        }
        if (d != null && d.modifiers().has(Modifier.EXTERNAL)) {
            addMethod(name, flags | ClassFile.ACC_NATIVE, type, null, null, null);
            return;
        }
        CheckedFunction body = declared != null && declared.body() != null
                ? backend.checker.checkedAccessor(p, getter) : null;
        if (body == null && d != null && d.delegate() != null) {
            CheckedDelegate delegate = backend.checker.checkedDelegate(p);
            body = getter ? delegate.getter() : delegate.setter();
        }
        CheckedFunction checked = body;
        addMethod(name, flags, type, checked, null, g -> {
            List<ClassDesc> params = type.parameterList();
            if (checked != null) {
                bindParameters(g, p, checked, params, 0);
                g.body(checked.body());
                return;
            }
            // The default accessors read and write the field.
            int i = 0;
            int receiverCount = p.signature().contextParams().size() + (p.signature().receiver() != null ? 1 : 0);
            int slot = isStatic ? 0 : 1;
            for (; i < receiverCount; i++) slot += TypeKind.from(params.get(i)).slotSize();
            Members.FieldRef f = Members.field(backend, p);
            CodeBuilder code = g.code;
            if (!f.isStatic()) code.aload(0);
            if (getter) {
                if (f.isStatic()) code.getstatic(f.owner(), f.name(), f.type());
                else code.getfield(f.owner(), f.name(), f.type());
                if (p.has(Modifier.LATEINIT)) {
                    Label ok = code.newLabel();
                    code.dup().ifnonnull(ok);
                    code.ldc(p.name());
                    code.invokestatic(ExprCodegen.INTRINSICS, "throwUninitializedPropertyAccessException",
                            MethodTypeDesc.of(TypeMapper.VOID, TypeMapper.STRING));
                    code.labelBinding(ok);
                }
                g.coerce(f.type(), type.returnType());
                g.returnValue(type.returnType());
            } else {
                ClassDesc valueType = params.getLast();
                code.loadLocal(TypeKind.from(valueType), slot);
                g.coerce(valueType, f.type());
                if (f.isStatic()) code.putstatic(f.owner(), f.name(), f.type());
                else code.putfield(f.owner(), f.name(), f.type());
                g.returnValue(TypeMapper.VOID);
            }
        });
    }

    /** Stores a property's initial value or delegate in its field. */
    private void initializeProperty(ExprCodegen g, PropertySymbol p) {
        if (!(p.source() instanceof Decl.Property d) || p.has(Modifier.CONST)) return;
        if (d.delegate() != null) {
            CheckedDelegate delegate = backend.checker.checkedDelegate(p);
            storeField(g, Members.delegateField(backend, p), delegate.delegate());
        } else if (d.initializer() != null && hasBackingField(p)) {
            storeField(g, Members.field(backend, p), backend.checker.checkedInitializer(p));
        }
    }

    private void storeField(ExprCodegen g, Members.FieldRef f, TExpr value) {
        g.scanBoxed(value);
        g.line(value.pos());
        if (f.isStatic()) {
            g.genAs(value, f.type());
            g.code.putstatic(f.owner(), f.name(), f.type());
        } else {
            g.code.aload(0);
            g.genAs(value, f.type());
            g.code.putfield(f.owner(), f.name(), f.type());
        }
    }

    // ==== Constructors ====

    /** The parameters a constructor takes before the declared ones: an enum's name and ordinal, an outer instance, captures. */
    List<ClassDesc> constructorPrefix() {
        List<ClassDesc> prefix = new ArrayList<>();
        if (cls.kind() == ClassKind.ENUM_CLASS || isEnumEntryClass()) {
            prefix.add(TypeMapper.STRING);
            prefix.add(ConstantDescs.CD_int);
        }
        if (cls.isInner() && !cls.isLocal() && cls.outer() != null) prefix.add(backend.types.classDesc(cls.outer()));
        for (ExprCodegen.Capture c : captures()) prefix.add(c.jvmType());
        return prefix;
    }

    /** Binds the prefix parameters of a constructor: for captures and the outer instance, stored in their fields. */
    private void bindPrefix(ExprCodegen g) {
        if (cls.kind() == ClassKind.ENUM_CLASS || isEnumEntryClass()) {
            g.parameter(null, TypeMapper.STRING);
            g.parameter(null, ConstantDescs.CD_int);
        }
        if (cls.isInner() && !cls.isLocal() && cls.outer() != null) {
            ClassDesc outer = backend.types.classDesc(cls.outer());
            ExprCodegen.Slot s = g.parameter(cls.outer(), outer);
            g.code.aload(0).aload(s.slot()).putfield(desc, "this$0", outer);
        }
        for (CapturedField f : captured()) {
            ExprCodegen.Capture c = f.capture();
            ExprCodegen.Slot s = g.parameter(null, c.jvmType());
            g.env.bind(c.key(), new ExprCodegen.Slot(s.slot(), c.type(), c.ref()));
            g.code.aload(0).loadLocal(TypeKind.from(c.jvmType()), s.slot()).putfield(desc, f.name(), c.jvmType());
        }
    }

    private void constructors() {
        List<FunctionSymbol> constructors = cls.members().constructors();
        if (constructors.isEmpty()) {
            // An object: a constructor calling the superclass's, then initializing.
            int flags = cls.isLocal() ? 0 : ClassFile.ACC_PRIVATE;
            MethodTypeDesc type = MethodTypeDesc.of(TypeMapper.VOID, constructorPrefix());
            addMethod("<init>", flags, type, null, null, g -> {
                bindPrefix(g);
                TExpr superCall = backend.checker.checkedSuperCall(cls);
                if (superCall != null) {
                    g.scanBoxed(superCall);
                    g.genAs(superCall, TypeMapper.VOID);
                } else {
                    implicitSuperCall(g);
                }
                // The instance is reachable before it is initialized, as kotlinc's are from <clinit>.
                if (!cls.isLocal() && cls.isObject()) {
                    g.code.aload(0);
                    if (cls.has(Modifier.COMPANION)) {
                        g.code.putstatic(backend.types.classDesc(cls.outer()), cls.name(), desc);
                    } else {
                        g.code.putstatic(desc, "INSTANCE", desc);
                    }
                }
                initializers(g);
                g.returnValue(TypeMapper.VOID);
            });
            return;
        }
        for (FunctionSymbol k : constructors) constructor(k);
    }

    private void constructor(FunctionSymbol k) {
        CheckedFunction body = backend.checker.checkedConstructor(k);
        List<ClassDesc> prefix = constructorPrefix();
        MethodTypeDesc declared = backend.types.methodType(k);
        MethodTypeDesc type = declared.insertParameterTypes(0, prefix.toArray(ClassDesc[]::new));
        int flags = cls.kind() == ClassKind.ENUM_CLASS ? ClassFile.ACC_PRIVATE : visibilityFlags(k.visibility());
        if (cls.modality() == Modality.SEALED) flags = ClassFile.ACC_PROTECTED;
        addMethod("<init>", flags, type, body, null, g -> {
            bindPrefix(g);
            bindParameters(g, k, body, type.parameterList(), prefix.size());
            List<TExpr> statements = body.body() instanceof TExpr.Block b ? b.statements() : List.of(body.body());
            int next = 0;
            boolean delegatesToThis = false;
            if (!statements.isEmpty() && statements.getFirst() instanceof TExpr.DelegatingConstructorCall dc) {
                g.scanBoxed(dc);
                g.genAs(dc, TypeMapper.VOID);
                delegatesToThis = dc.constructor().owner() == cls;
                next = 1;
            } else {
                implicitSuperCall(g);
            }
            if (!delegatesToThis) initializers(g);
            for (int i = next; i < statements.size(); i++) {
                g.scanBoxed(statements.get(i));
                g.genAs(statements.get(i), TypeMapper.VOID);
            }
            g.returnValue(TypeMapper.VOID);
        });
        if (hasOwnDefaults(k)) defaultConstructor(k, body, prefix, type);
    }

    /** The constructor taking a mask of the parameters left to their defaults, which it gives them. */
    private void defaultConstructor(FunctionSymbol k, CheckedFunction body, List<ClassDesc> prefix,
                                    MethodTypeDesc target) {
        MethodTypeDesc type = Calls.defaultMethodType(backend, k).insertParameterTypes(0,
                prefix.toArray(ClassDesc[]::new));
        addMethod("<init>", ClassFile.ACC_PUBLIC | ClassFile.ACC_SYNTHETIC, type, null, null, g -> {
            List<ClassDesc> params = type.parameterList();
            List<Integer> slots = new ArrayList<>();
            int slot = 1;
            for (ClassDesc p : params) {
                slots.add(slot);
                slot += TypeKind.from(p).slotSize();
            }
            // Parameter slots, for the default values to read; captures are bound to theirs.
            for (int i = 0; i < prefix.size(); i++) g.parameter(null, params.get(i));
            List<CapturedField> fields = captured();
            int captureStart = prefix.size() - fields.size();
            for (int i = 0; i < fields.size(); i++) {
                ExprCodegen.Capture c = fields.get(i).capture();
                g.env.bind(c.key(), new ExprCodegen.Slot(slots.get(captureStart + i), c.type(), c.ref()));
            }
            bindParameters(g, k, body, params, prefix.size());
            int maskSlot = slots.get(prefix.size() + body.params().size());
            for (int i = 0; i < body.params().size(); i++) {
                TExpr dv = i < body.defaultValues().size() ? body.defaultValues().get(i) : null;
                if (dv == null) continue;
                ClassDesc t = params.get(prefix.size() + i);
                Label skip = g.code.newLabel();
                g.code.iload(maskSlot + i / 32).loadConstant(1 << (i % 32)).iand().ifeq(skip);
                g.genAs(dv, t);
                g.code.storeLocal(TypeKind.from(t), slots.get(prefix.size() + i));
                g.code.labelBinding(skip);
            }
            g.code.aload(0);
            for (int i = 0; i < prefix.size() + body.params().size(); i++) {
                g.code.loadLocal(TypeKind.from(params.get(i)), slots.get(i));
            }
            g.code.invokespecial(desc, "<init>", target);
            g.returnValue(TypeMapper.VOID);
        });
    }

    /** The call of the superclass's constructor without arguments, or of Enum's with the name and ordinal. */
    private void implicitSuperCall(ExprCodegen g) {
        g.code.aload(0);
        if (cls.kind() == ClassKind.ENUM_CLASS) {
            g.code.aload(1).iload(2);
            g.code.invokespecial(ENUM, "<init>", MethodTypeDesc.of(TypeMapper.VOID, TypeMapper.STRING,
                    ConstantDescs.CD_int));
            return;
        }
        g.code.invokespecial(superclass(), "<init>", MethodTypeDesc.of(TypeMapper.VOID));
    }

    /**
     * The initializers of properties and init blocks, in order, after the
     * superclass's constructor; first, the properties of the primary
     * constructor's parameters.
     */
    private void initializers(ExprCodegen g) {
        Decl.PrimaryConstructor primary = cls.decl().primaryConstructor();
        FunctionSymbol k = primary == null ? null : cls.members().constructors().stream()
                .filter(c -> c.source() == primary).findFirst().orElse(null);
        for (int i = 0; k != null && i < primary.params().size(); i++) {
            Decl.Param param = primary.params().get(i);
            if (param.valOrVar() == Decl.ValOrVar.NONE) continue;
            for (PropertySymbol p : cls.members().properties(param.name())) {
                if (p.source() != param || !hasBackingField(p)) continue;
                Members.FieldRef f = Members.field(backend, p);
                ExprCodegen.Slot s = (ExprCodegen.Slot) g.env.lookup(k.signature().params().get(i));
                g.code.aload(0);
                g.loadSlotValue(s);
                g.coerce(s.type(), f.type());
                g.code.putfield(f.owner(), f.name(), f.type());
            }
        }
        for (Decl d : allMembers()) {
            switch (d) {
                case Decl.Property pd -> {
                    for (PropertySymbol p : cls.members().properties(pd.name())) {
                        if (p.source() == d) initializeProperty(g, p);
                    }
                }
                case Decl.Initializer i -> {
                    TExpr block = backend.checker.checkedInitBlock(cls, i);
                    g.scanBoxed(block);
                    g.genAs(block, TypeMapper.VOID);
                }
                default -> {
                }
            }
        }
    }

    /** {@code this(...)} or {@code super(...)}: the constructor with the prefix it takes. */
    void delegatingConstructorCall(ExprCodegen g, TExpr.DelegatingConstructorCall d) {
        new Calls(g).delegatingConstructorCall(d, this);
    }

    // ==== Objects, companions and enums ====

    private void companionAndObject() {
        List<Consumer<ExprCodegen>> clinit = new ArrayList<>();
        // Not final: the constructor stores the instance, before initializing it.
        if (cls.isObject() && !cls.has(Modifier.COMPANION) && !cls.isLocal()) {
            field(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, "INSTANCE", desc, null);
            clinit.add(g -> g.code.new_(desc).invokespecial(desc, "<init>", MethodTypeDesc.of(TypeMapper.VOID)));
        }
        ClassSymbol companion = cls.companion();
        if (companion instanceof KotlinClassSymbol c) {
            ClassDesc cd = backend.types.classDesc(c);
            field(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, c.name(), cd, null);
            clinit.add(g -> g.code.new_(cd).invokespecial(cd, "<init>", MethodTypeDesc.of(TypeMapper.VOID)));
        }
        if (cls.kind() == ClassKind.ENUM_CLASS) clinit.add(this::enumEntries);
        // Static fields of the companion, initialized by it.
        for (PendingField f : pendingFields) field(f.flags(), f.name(), f.type(), f.constant());
        if (clinit.isEmpty()) return;
        addMethod("<clinit>", ClassFile.ACC_STATIC, MethodTypeDesc.of(TypeMapper.VOID), null, null, g -> {
            for (Consumer<ExprCodegen> c : clinit) c.accept(g);
            g.returnValue(TypeMapper.VOID);
        });
    }

    private void enumEntries(ExprCodegen g) {
        List<Decl.EnumEntry> entries = cls.decl().enumEntries();
        CodeBuilder code = g.code;
        int ordinal = 0;
        for (Decl.EnumEntry e : entries) {
            field(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_ENUM, e.name(),
                    desc, null);
            TExpr entry = backend.checker.checkedEnumEntry(cls, e);
            g.scanBoxed(entry);
            if (entry instanceof TExpr.ObjectLiteral o) {
                ClassCodegen entryClass = backend.localClass((KotlinClassSymbol) o.cls(), this, "<clinit>");
                entryClass.setEnclosing(g);
                code.new_(entryClass.desc).dup().ldc(e.name()).loadConstant(ordinal);
                code.invokespecial(entryClass.desc, "<init>", MethodTypeDesc.of(TypeMapper.VOID, TypeMapper.STRING,
                        ConstantDescs.CD_int));
            } else {
                new Calls(g).enumEntry((TExpr.DelegatingConstructorCall) entry, this, e.name(), ordinal);
            }
            code.putstatic(desc, e.name(), desc);
            ordinal++;
        }
        ClassDesc array = desc.arrayType();
        field(ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC,
                "$VALUES", array, null);
        code.loadConstant(entries.size()).anewarray(desc);
        for (int i = 0; i < entries.size(); i++) {
            code.dup().loadConstant(i).getstatic(desc, entries.get(i).name(), desc).aastore();
        }
        code.putstatic(desc, "$VALUES", array);
        ClassDesc enumEntries = ClassDesc.ofInternalName("kotlin/enums/EnumEntries");
        field(ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC,
                "$ENTRIES", enumEntries, null);
        code.getstatic(desc, "$VALUES", array).checkcast(ENUM.arrayType());
        code.invokestatic(ClassDesc.ofInternalName("kotlin/enums/EnumEntriesKt"), "enumEntries",
                MethodTypeDesc.of(enumEntries, ENUM.arrayType()));
        code.putstatic(desc, "$ENTRIES", enumEntries);
    }

    // ==== Synthesized members ====

    private void synthesized() {
        for (List<FunctionSymbol> fs : cls.members().functions().values()) {
            for (FunctionSymbol f : fs) {
                if (f.origin() != Origin.SYNTHETIC || f.source() != cls.decl()) continue;
                switch (f.name()) {
                    case "values" -> enumValues(f);
                    case "valueOf" -> enumValueOf(f);
                    case "copy" -> dataCopy(f);
                    case "equals" -> dataEquals(f);
                    case "hashCode" -> dataHashCode(f);
                    case "toString" -> dataToString(f);
                    default -> {
                        if (f.name().startsWith("component")) dataComponent(f);
                    }
                }
            }
        }
        for (PropertySymbol p : cls.members().properties("entries")) {
            if (p.origin() == Origin.SYNTHETIC && cls.kind() == ClassKind.ENUM_CLASS) {
                ClassDesc type = ClassDesc.ofInternalName("kotlin/enums/EnumEntries");
                addMethod("getEntries", ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, MethodTypeDesc.of(type), null,
                        null, g -> {
                            g.code.getstatic(desc, "$ENTRIES", type);
                            g.returnValue(type);
                        });
            }
        }
    }

    private void enumValues(FunctionSymbol f) {
        ClassDesc array = desc.arrayType();
        addMethod("values", ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, MethodTypeDesc.of(array), null, null, g -> {
            g.code.getstatic(desc, "$VALUES", array).invokevirtual(array, "clone", MethodTypeDesc.of(TypeMapper.OBJECT))
                    .checkcast(array);
            g.returnValue(array);
        });
    }

    private void enumValueOf(FunctionSymbol f) {
        addMethod("valueOf", ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, MethodTypeDesc.of(desc, TypeMapper.STRING),
                null, null, g -> {
                    g.code.ldc(desc).aload(0).invokestatic(ENUM, "valueOf",
                            MethodTypeDesc.of(ENUM, ConstantDescs.CD_Class, TypeMapper.STRING)).checkcast(desc);
                    g.returnValue(desc);
                });
    }

    /** The properties of a data class's primary constructor. */
    private List<PropertySymbol> dataProperties() {
        List<PropertySymbol> result = new ArrayList<>();
        for (Decl.Param param : cls.decl().primaryConstructor().params()) {
            if (param.valOrVar() == Decl.ValOrVar.NONE) continue;
            for (PropertySymbol p : cls.members().properties(param.name())) if (p.source() == param) result.add(p);
        }
        return result;
    }

    private void loadField(CodeBuilder code, PropertySymbol p, int receiverSlot) {
        Members.FieldRef f = Members.field(backend, p);
        code.aload(receiverSlot).getfield(f.owner(), f.name(), f.type());
    }

    private void dataComponent(FunctionSymbol f) {
        int n = Integer.parseInt(f.name().substring("component".length()));
        PropertySymbol p = dataProperties().get(n - 1);
        MethodTypeDesc type = backend.types.methodType(f);
        addMethod(f.name(), ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, type, null, null, g -> {
            loadField(g.code, p, 0);
            g.coerce(Members.field(backend, p).type(), type.returnType());
            g.returnValue(type.returnType());
        });
    }

    private void dataCopy(FunctionSymbol f) {
        MethodTypeDesc type = backend.types.methodType(f);
        FunctionSymbol primary = primaryConstructor();
        MethodTypeDesc constructorType = backend.types.methodType(primary);
        addMethod("copy", ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, type, null, null, g -> {
            g.code.new_(desc).dup();
            int slot = 1;
            for (ClassDesc p : type.parameterList()) {
                g.code.loadLocal(TypeKind.from(p), slot);
                slot += TypeKind.from(p).slotSize();
            }
            g.code.invokespecial(desc, "<init>", constructorType);
            g.returnValue(desc);
        });
        // copy$default takes the current values for the parameters left out.
        MethodTypeDesc defaultType = Calls.defaultMethodType(backend, f);
        List<PropertySymbol> properties = dataProperties();
        addMethod("copy$default", ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, defaultType,
                null, null, g -> {
                    List<ClassDesc> params = defaultType.parameterList();
                    int[] slots = new int[params.size()];
                    int slot = 0;
                    for (int i = 0; i < params.size(); i++) {
                        slots[i] = slot;
                        slot += TypeKind.from(params.get(i)).slotSize();
                    }
                    int mask = slots[1 + properties.size()];
                    for (int i = 0; i < properties.size(); i++) {
                        Label skip = g.code.newLabel();
                        g.code.iload(mask).loadConstant(1 << i).iand().ifeq(skip);
                        loadField(g.code, properties.get(i), 0);
                        g.coerce(Members.field(backend, properties.get(i)).type(), params.get(1 + i));
                        g.code.storeLocal(TypeKind.from(params.get(1 + i)), slots[1 + i]);
                        g.code.labelBinding(skip);
                    }
                    for (int i = 0; i <= properties.size(); i++) {
                        g.code.loadLocal(TypeKind.from(params.get(i)), slots[i]);
                    }
                    g.code.invokevirtual(desc, "copy", type);
                    g.returnValue(desc);
                });
    }

    private FunctionSymbol primaryConstructor() {
        for (FunctionSymbol k : cls.members().constructors()) {
            if (k.source() == cls.decl().primaryConstructor()) return k;
        }
        throw new CodegenException(cls.decl().pos(), "No primary constructor: " + cls);
    }

    private void dataToString(FunctionSymbol f) {
        ClassDesc sb = ClassDesc.of("java.lang.StringBuilder");
        addMethod("toString", ClassFile.ACC_PUBLIC, MethodTypeDesc.of(TypeMapper.STRING), null, null, g -> {
            CodeBuilder code = g.code;
            code.new_(sb).dup().invokespecial(sb, "<init>", MethodTypeDesc.of(TypeMapper.VOID));
            List<PropertySymbol> properties = dataProperties();
            String prefix = cls.name() + "(";
            for (int i = 0; i < properties.size(); i++) {
                PropertySymbol p = properties.get(i);
                code.ldc(prefix + p.name() + "=");
                g.append(sb, TypeMapper.STRING);
                loadField(code, p, 0);
                ClassDesc t = Members.field(backend, p).type();
                if (t.isArray()) {
                    ClassDesc param = t.componentType().isPrimitive() ? t : TypeMapper.OBJECT.arrayType();
                    code.invokestatic(ARRAYS, "toString", MethodTypeDesc.of(TypeMapper.STRING, param));
                    t = TypeMapper.STRING;
                }
                g.append(sb, t);
                prefix = ", ";
            }
            code.ldc(properties.isEmpty() ? prefix + ")" : ")");
            g.append(sb, TypeMapper.STRING);
            code.invokevirtual(sb, "toString", MethodTypeDesc.of(TypeMapper.STRING));
            g.returnValue(TypeMapper.STRING);
        });
    }

    private void dataHashCode(FunctionSymbol f) {
        addMethod("hashCode", ClassFile.ACC_PUBLIC, MethodTypeDesc.of(ConstantDescs.CD_int), null, null, g -> {
            CodeBuilder code = g.code;
            List<PropertySymbol> properties = dataProperties();
            if (properties.isEmpty()) code.iconst_0();
            for (int i = 0; i < properties.size(); i++) {
                PropertySymbol p = properties.get(i);
                if (i > 0) code.bipush(31).imul();
                loadField(code, p, 0);
                hashOf(code, Members.field(backend, p).type());
                if (i > 0) code.iadd();
            }
            g.returnValue(ConstantDescs.CD_int);
        });
    }

    /** Replaces a value by its hash code: of its box for a primitive, 0 for null. */
    private static void hashOf(CodeBuilder code, ClassDesc t) {
        if (t.isPrimitive()) {
            code.invokestatic(TypeMapper.box(t), "hashCode", MethodTypeDesc.of(ConstantDescs.CD_int, t));
            return;
        }
        if (t.isArray()) {
            ClassDesc param = t.componentType().isPrimitive() ? t : TypeMapper.OBJECT.arrayType();
            code.invokestatic(ARRAYS, "hashCode", MethodTypeDesc.of(ConstantDescs.CD_int, param));
            return;
        }
        Label isNull = code.newLabel();
        Label end = code.newLabel();
        code.dup().ifnull(isNull);
        code.invokevirtual(TypeMapper.OBJECT, "hashCode", MethodTypeDesc.of(ConstantDescs.CD_int)).goto_(end);
        code.labelBinding(isNull);
        code.pop().iconst_0();
        code.labelBinding(end);
    }

    private void dataEquals(FunctionSymbol f) {
        addMethod("equals", ClassFile.ACC_PUBLIC, MethodTypeDesc.of(ConstantDescs.CD_boolean, TypeMapper.OBJECT),
                null, null, g -> {
                    CodeBuilder code = g.code;
                    Label isTrue = code.newLabel();
                    Label isFalse = code.newLabel();
                    code.aload(0).aload(1).if_acmpeq(isTrue);
                    code.aload(1).instanceOf(desc).ifeq(isFalse);
                    code.aload(1).checkcast(desc).astore(2);
                    for (PropertySymbol p : dataProperties()) {
                        ClassDesc t = Members.field(backend, p).type();
                        loadField(code, p, 0);
                        loadField(code, p, 2);
                        switch (t.isPrimitive() ? TypeKind.from(t).asLoadable() : TypeKind.REFERENCE) {
                            case INT -> code.if_icmpne(isFalse);
                            case LONG -> code.lcmp().ifne(isFalse);
                            case FLOAT -> code.invokestatic(ConstantDescs.CD_Float, "compare",
                                    MethodTypeDesc.of(ConstantDescs.CD_int, t, t)).ifne(isFalse);
                            case DOUBLE -> code.invokestatic(ConstantDescs.CD_Double, "compare",
                                    MethodTypeDesc.of(ConstantDescs.CD_int, t, t)).ifne(isFalse);
                            default -> code.invokestatic(ExprCodegen.INTRINSICS, "areEqual",
                                    MethodTypeDesc.of(ConstantDescs.CD_boolean, TypeMapper.OBJECT, TypeMapper.OBJECT))
                                    .ifeq(isFalse);
                        }
                    }
                    code.labelBinding(isTrue);
                    code.iconst_1().ireturn();
                    code.labelBinding(isFalse);
                    code.iconst_0().ireturn();
                });
    }

    // ==== Bridges ====

    /**
     * Bridges for the members whose JVM signatures differ from those of
     * members they override, as for a generic one's erasure: calling them
     * with their parameters cast.
     */
    private void bridges() {
        if (isInterface) return;
        Map<String, Object[]> bridges = new LinkedHashMap<>();
        for (List<FunctionSymbol> fs : cls.members().functions().values()) {
            for (FunctionSymbol f : fs) {
                if (f.isStatic() || f.has(Modifier.EXPECT) || f.visibility() == Visibility.PRIVATE) continue;
                String name = backend.names.functionName(f);
                MethodTypeDesc own = backend.types.methodType(f);
                for (CallableSymbol o : allOverridden(f)) {
                    if (!(o instanceof FunctionSymbol of)) continue;
                    addBridge(bridges, backend.names.functionName(of), backend.types.methodType(of), name, own);
                }
            }
        }
        for (List<PropertySymbol> ps : cls.members().properties().values()) {
            for (PropertySymbol p : ps) {
                if (p.isStatic() || p.visibility() == Visibility.PRIVATE || p.has(Modifier.CONST)
                        || JvmNames.has(JvmNames.modifiers(p), "JvmField")) {
                    continue;
                }
                String getter = backend.names.getterName(p);
                MethodTypeDesc own = backend.types.getterType(p);
                for (CallableSymbol o : allOverridden(p)) {
                    if (!(o instanceof PropertySymbol op) || op.isJavaField()) continue;
                    addBridge(bridges, backend.names.getterName(op), backend.types.getterType(op), getter, own);
                    if (p.isVar() && op.isVar()) {
                        addBridge(bridges, backend.names.setterName(op), backend.types.setterType(op),
                                backend.names.setterName(p), backend.types.setterType(p));
                    }
                }
            }
        }
        for (Map.Entry<String, Object[]> e : bridges.entrySet()) {
            Object[] b = e.getValue();
            bridge((String) b[0], (MethodTypeDesc) b[1], (String) b[2], (MethodTypeDesc) b[3]);
        }
    }

    private static List<CallableSymbol> allOverridden(CallableSymbol s) {
        List<CallableSymbol> result = new ArrayList<>();
        Deque<CallableSymbol> work = new ArrayDeque<>(Overrides.overridden(s));
        Set<CallableSymbol> seen = new HashSet<>();
        while (!work.isEmpty()) {
            CallableSymbol o = work.removeFirst();
            if (!seen.add(o)) continue;
            result.add(o);
            work.addAll(Overrides.overridden(o));
        }
        return result;
    }

    private void addBridge(Map<String, Object[]> bridges, String name, MethodTypeDesc type, String targetName,
                           MethodTypeDesc target) {
        String key = name + type.descriptorString();
        if (name.equals(targetName) && type.equals(target)) return;
        if (bridges.containsKey(key) || declaredMethods.contains(key)) return;
        if (type.parameterCount() != target.parameterCount()) return;
        bridges.put(key, new Object[]{name, type, targetName, target});
    }

    private void bridge(String name, MethodTypeDesc type, String targetName, MethodTypeDesc target) {
        addMethod(name, ClassFile.ACC_PUBLIC | ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE, type, null, null, g -> {
            CodeBuilder code = g.code;
            code.aload(0);
            int slot = 1;
            for (int i = 0; i < type.parameterCount(); i++) {
                ClassDesc p = type.parameterType(i);
                code.loadLocal(TypeKind.from(p), slot);
                slot += TypeKind.from(p).slotSize();
                g.coerce(p, target.parameterType(i));
            }
            code.invokevirtual(desc, targetName, target);
            g.coerce(target.returnType(), type.returnType());
            g.returnValue(type.returnType());
        });
    }

    // ==== Local classes ====

    /** The checked bodies of the class, for what it captures. */
    Closures.Bodies bodies() {
        List<CheckedFunction> functions = new ArrayList<>();
        List<TExpr> expressions = new ArrayList<>();
        for (Decl d : allMembers()) {
            switch (d) {
                case Decl.Function fd -> {
                    for (FunctionSymbol f : cls.members().functions(fd.name())) {
                        if (f.source() == d && fd.body() != null) functions.add(backend.checker.checkedFunction(f));
                    }
                }
                case Decl.Property pd -> {
                    for (PropertySymbol p : cls.members().properties(pd.name())) {
                        if (p.source() != d) continue;
                        if (pd.initializer() != null && !p.has(Modifier.CONST)) {
                            expressions.add(backend.checker.checkedInitializer(p));
                        }
                        for (boolean getter : new boolean[]{true, false}) {
                            CheckedFunction a = backend.checker.checkedAccessor(p, getter);
                            if (a != null) functions.add(a);
                        }
                        if (pd.delegate() != null) {
                            CheckedDelegate delegate = backend.checker.checkedDelegate(p);
                            expressions.add(delegate.delegate());
                            functions.add(delegate.getter());
                            if (delegate.setter() != null) functions.add(delegate.setter());
                        }
                    }
                }
                case Decl.Initializer i -> expressions.add(backend.checker.checkedInitBlock(cls, i));
                default -> {
                }
            }
        }
        for (FunctionSymbol k : cls.members().constructors()) functions.add(backend.checker.checkedConstructor(k));
        if (cls.members().constructors().isEmpty() && !cls.isInterface()) {
            TExpr superCall = backend.checker.checkedSuperCall(cls);
            if (superCall != null) expressions.add(superCall);
        }
        return new Closures.Bodies(functions, expressions);
    }

    /** Records what the class captures, from the code it is declared in. */
    void setEnclosing(ExprCodegen g) {
        if (captured != null) return;
        Closures.Captures c = backend.closures.of(cls);
        Set<Object> constructorParams = new HashSet<>();
        for (FunctionSymbol k : cls.members().constructors()) constructorParams.addAll(k.signature().params());
        List<CapturedField> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object r : c.receivers()) {
            if (r == cls) continue;
            String name = r instanceof ClassSymbol ? "this$0" : "$this";
            result.add(new CapturedField(unique(names, name), g.capture(r)));
        }
        for (LocalVariable v : c.variables()) {
            if (v.param() != null && constructorParams.contains(v.param())) continue;
            result.add(new CapturedField(unique(names, "$" + v.name()), g.capture(v)));
        }
        captured = result;
    }

    private static String unique(Set<String> names, String name) {
        String result = name;
        for (int i = 1; !names.add(result); i++) result = name + "$" + i;
        return result;
    }

    private List<CapturedField> captured() {
        return captured != null ? captured : List.of();
    }

    /** What the class captures, its constructors' first parameters. */
    List<ExprCodegen.Capture> captures() {
        List<ExprCodegen.Capture> result = new ArrayList<>();
        for (CapturedField f : captured()) result.add(f.capture());
        return result;
    }

    /** How a value captured by the class is kept, or for the outer instance of an inner class; null if neither. */
    ExprCodegen.Capture capturedField(Object key) {
        for (CapturedField f : captured()) if (f.capture().key() == key) return f.capture();
        if (cls != null && cls.isInner() && !cls.isLocal() && key instanceof ClassSymbol outer) {
            for (ClassSymbol o = cls.outer(); o != null; o = o.isInner() ? o.outer() : null) {
                if (o == outer) return new ExprCodegen.Capture(key, backend.types.classDesc(outer), false);
            }
        }
        return null;
    }

    /**
     * Loads a captured value from its field: the value, or a variable's Ref
     * itself if raw; the outer instance of an inner class. Null if not
     * captured.
     */
    ClassDesc loadCaptured(ExprCodegen g, Object key, boolean raw) {
        if (g.isStatic || cls == null) return null;
        for (CapturedField f : captured()) {
            if (f.capture().key() != key) continue;
            ExprCodegen.Capture c = f.capture();
            g.code.aload(0).getfield(desc, f.name(), c.jvmType());
            if (c.ref() && !raw) {
                g.derefLoad(c.jvmType(), c.type());
                return c.type();
            }
            return c.jvmType();
        }
        if (cls.isInner() && !cls.isLocal() && key instanceof ClassSymbol outer) {
            g.code.aload(0);
            ClassSymbol current = cls;
            while (current.isInner() && current.outer() != null) {
                ClassDesc o = backend.types.classDesc(current.outer());
                g.code.getfield(backend.types.classDesc(current), "this$0", o);
                if (current.outer() == outer) return o;
                current = current.outer();
            }
            g.code.pop();
        }
        return null;
    }

    /** Assigns a captured variable, through its Ref. */
    boolean storeCaptured(ExprCodegen g, LocalVariable v, TExpr value) {
        if (g.isStatic || cls == null) return false;
        for (CapturedField f : captured()) {
            if (f.capture().key() != v || !f.capture().ref()) continue;
            ExprCodegen.Capture c = f.capture();
            g.code.aload(0).getfield(desc, f.name(), c.jvmType());
            g.genAs(value, c.type());
            g.coerce(c.type(), Refs.elementType(c.type()));
            g.code.putfield(c.jvmType(), "element", Refs.elementType(c.type()));
            return true;
        }
        return false;
    }

    /** Creates an instance of an object literal, with what it captures. */
    ClassDesc newInstance(ExprCodegen g) {
        List<ClassDesc> prefix = new ArrayList<>();
        g.code.new_(desc).dup();
        for (ExprCodegen.Capture c : captures()) {
            g.coerce(g.loadForCapture(c.key()), c.jvmType());
            prefix.add(c.jvmType());
        }
        g.code.invokespecial(desc, "<init>", MethodTypeDesc.of(TypeMapper.VOID, prefix));
        return desc;
    }
}
