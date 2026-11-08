package kotlinbootstrap.jvm;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.check.Checker;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.syntax.SourceFile;
import kotlinbootstrap.types.KType;

/**
 * The JVM backend: compiles the checked declarations of Kotlin files to
 * class files, as kotlinc's JVM IR backend, though from checked trees
 * directly. Inline functions are inlined from their checked bodies.
 */
public final class JvmBackend {
    final Resolver resolver;
    final Checker checker;
    final TypeMapper types;
    final Signatures signatures;
    final JvmAnnotations annotations = new JvmAnnotations(this);
    final JvmNames names;
    final Closures closures;
    private final ClassHierarchyResolver hierarchy;
    final Map<String, byte[]> output = new LinkedHashMap<>();
    /** The superclasses of the synthetic classes generated here, for stack map frames. */
    final Map<String, ClassDesc> syntheticSupers = new HashMap<>();
    private final Deque<ClassCodegen> pending = new ArrayDeque<>();
    /** The class being generated. */
    private ClassCodegen current;
    /** The classes being generated, by symbol, for local classes generated where they are declared. */
    private final Map<ClassSymbol, ClassCodegen> generators = new IdentityHashMap<>();
    /** The multifile facades, with their parts. */
    private final Map<String, List<FileContext>> multifileParts = new LinkedHashMap<>();
    private final Map<String, Integer> localClassCounters = new HashMap<>();
    final Nests nests = new Nests();
    private boolean keepGoing;
    private final List<RuntimeException> errors = new ArrayList<>();

    /**
     * @param moduleName the name of the module a file is in, for internal names
     * @param classPath  resolves the hierarchy of classes not compiled here, for stack map frames
     */
    public JvmBackend(Resolver resolver, Checker checker, Function<SourceFile, String> moduleName,
                      ClassHierarchyResolver classPath) {
        this.resolver = resolver;
        this.checker = checker;
        this.names = new JvmNames(resolver, moduleName);
        this.types = new TypeMapper(names, resolver);
        types.localNamer = this::reserveLocalName;
        this.signatures = new Signatures(types);
        this.closures = new Closures(cls -> classBodies(cls));
        this.hierarchy = new SourceHierarchy(this).orElse(classPath);
    }

    ClassHierarchyResolver hierarchy() {
        return hierarchy;
    }

    /** Compiles the files; the class files by internal name. */
    public Map<String, byte[]> compile(List<FileContext> files) {
        for (FileContext file : files) {
            if (JvmNames.isBuiltinFile(file.file())) continue;
            // One nest per file: the file's part hosts it if there is one, else its first class.
            String host = null;
            if (hasTopLevelCallables(file)) {
                ClassCodegen facade = new ClassCodegen(this, file, names.filePart(file));
                host = facade.nestHost = facade.internalName;
                pending.add(facade);
                if (JvmNames.isMultifile(file.file())) {
                    multifileParts.computeIfAbsent(names.facade(file), k -> new ArrayList<>()).add(file);
                }
            }
            for (Decl d : file.file().declarations()) {
                if (d instanceof Decl.Class c && !c.modifiers().has(Modifier.EXPECT)
                        && resolver.table().packageScope(file.packageName()).classifier(c.name())
                        instanceof KotlinClassSymbol cls && cls.decl() == c) {
                    if (host == null) host = types.internalName(cls);
                    addClass(cls, host);
                }
            }
        }
        drain();
        nests.addMembers(output);
        for (Map.Entry<String, List<FileContext>> e : multifileParts.entrySet()) {
            output.put(e.getKey(), ClassCodegen.multifileFacade(this, e.getKey(), e.getValue()));
        }
        return output;
    }

    /**
     * A class and its nested classes, which are generated first: a
     * companion declares its static fields in the class it is in.
     */
    private void addClass(KotlinClassSymbol cls, String host) {
        ClassCodegen g = new ClassCodegen(this, cls, types.internalName(cls));
        g.nestHost = host;
        generators.put(cls, g);
        for (KotlinClassSymbol nested : nestedClasses(cls)) addClass(nested, host);
        pending.add(g);
    }

    /** The classes declared in a class's body. */
    static List<KotlinClassSymbol> nestedClasses(KotlinClassSymbol cls) {
        List<KotlinClassSymbol> result = new ArrayList<>();
        for (Decl d : cls.decl().members()) {
            if (d instanceof Decl.Class c && cls.members().classifier(nameOf(c)) instanceof KotlinClassSymbol nested
                    && nested.decl() == c) {
                result.add(nested);
            }
        }
        return result;
    }

    private static String nameOf(Decl.Class c) {
        return c.name() != null ? c.name() : "Companion";
    }

    /** A local class, anonymous object or enum entry class, generated once its enclosing code is. */
    ClassCodegen localClass(KotlinClassSymbol cls, ClassCodegen enclosingClass, String methodName) {
        ClassCodegen existing = generators.get(cls);
        if (existing != null) return existing;
        String name = types.assignedLocalName(cls);
        if (name == null) name = localClassName(cls, enclosingClass.internalName, methodName);
        types.setLocalName(cls, name);
        ClassCodegen g = new ClassCodegen(this, cls, name);
        g.nestHost = enclosingClass.nestHost;
        generators.put(cls, g);
        localNestedClasses(g);
        pending.add(g);
        return g;
    }

    private String localClassName(KotlinClassSymbol cls, String enclosing, String methodName) {
        String base = enclosing + "$" + (methodName == null || methodName.startsWith("<") ? "" : methodName + "$");
        if (cls.decl().name() == null) return enclosing + "$" + localClassCounters.merge(enclosing, 1, Integer::sum);
        String name = base + cls.decl().name();
        int n = localClassCounters.merge(name, 1, Integer::sum);
        return n > 1 ? name + "$" + n : name;
    }

    private void localNestedClasses(ClassCodegen g) {
        for (KotlinClassSymbol nested : nestedClasses(g.cls)) {
            types.setLocalName(nested, g.internalName + "$" + nested.name());
            ClassCodegen ng = new ClassCodegen(this, nested, g.internalName + "$" + nested.name());
            ng.nestHost = g.nestHost;
            generators.put(nested, ng);
            pending.add(ng);
            localNestedClasses(ng);
        }
    }

    /**
     * Names a local class, or one nested in it, whose type the code of
     * the class being generated uses before the code declaring it, as a
     * private property's initializer does in its field's type; null if
     * no class is being generated.
     */
    String reserveLocalName(KotlinClassSymbol cls) {
        String name;
        if (!cls.isLocal() && cls.outer() != null) {
            name = types.internalName(cls.outer()) + "$" + cls.name();
        } else if (current != null) {
            name = localClassName(cls, current.internalName, null);
        } else {
            return null;
        }
        types.setLocalName(cls, name);
        return name;
    }

    /**
     * A property reference's class, as kotlinc's: a subclass of the
     * PropertyReferenceNImpl given, in the nest of the class creating it,
     * whose get and set call that class's static accessor methods, with
     * the bound receiver first if there is one.
     *
     * @param setName null for a read-only reference
     */
    ClassDesc propertyReferenceClass(ClassCodegen enclosing, ClassDesc impl, int arity, boolean bound,
                                     ClassDesc owner, String name, String signature, String getName,
                                     MethodTypeDesc getType, String setName, MethodTypeDesc setType) {
        String internalName = enclosing.internalName + "$" + localClassCounters.merge(enclosing.internalName, 1,
                Integer::sum);
        ClassDesc desc = ClassDesc.ofInternalName(internalName);
        syntheticSupers.put(internalName, impl);
        ClassDesc callableReference = ClassDesc.ofInternalName("kotlin/jvm/internal/CallableReference");
        MethodTypeDesc boundReceiver = MethodTypeDesc.of(TypeMapper.OBJECT);
        byte[] bytes = ClassFile.of().build(desc, cb -> {
            cb.withVersion(ClassFile.JAVA_17_VERSION, 0);
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_SYNTHETIC);
            cb.withSuperclass(impl);
            MethodTypeDesc init = bound ? MethodTypeDesc.of(TypeMapper.VOID, TypeMapper.OBJECT)
                    : MethodTypeDesc.of(TypeMapper.VOID);
            cb.withMethodBody("<init>", init, ClassFile.ACC_PUBLIC, code -> {
                code.aload(0);
                if (bound) code.aload(1);
                code.ldc(owner).ldc(name).ldc(signature).iconst_0();
                List<ClassDesc> params = new ArrayList<>();
                if (bound) params.add(TypeMapper.OBJECT);
                params.addAll(List.of(ConstantDescs.CD_Class, TypeMapper.STRING, TypeMapper.STRING,
                        ConstantDescs.CD_int));
                code.invokespecial(impl, "<init>", MethodTypeDesc.of(TypeMapper.VOID, params));
                code.return_();
            });
            List<ClassDesc> objects = Collections.nCopies(arity, TypeMapper.OBJECT);
            cb.withMethodBody("get", MethodTypeDesc.of(TypeMapper.OBJECT, objects), ClassFile.ACC_PUBLIC, code -> {
                if (bound) code.aload(0).invokevirtual(callableReference, "getBoundReceiver", boundReceiver);
                for (int i = 0; i < arity; i++) code.aload(i + 1);
                code.invokestatic(enclosing.desc, getName, getType, enclosing.isInterface);
                code.areturn();
            });
            if (setName != null) {
                List<ClassDesc> setParams = new ArrayList<>(objects);
                setParams.add(TypeMapper.OBJECT);
                cb.withMethodBody("set", MethodTypeDesc.of(TypeMapper.VOID, setParams), ClassFile.ACC_PUBLIC,
                        code -> {
                            if (bound) code.aload(0).invokevirtual(callableReference, "getBoundReceiver", boundReceiver);
                            for (int i = 0; i <= arity; i++) code.aload(i + 1);
                            code.invokestatic(enclosing.desc, setName, setType, enclosing.isInterface);
                            code.return_();
                        });
            }
            if (enclosing.nestHost != null) {
                cb.with(NestHostAttribute.of(ClassDesc.ofInternalName(enclosing.nestHost)));
                nests.members.computeIfAbsent(enclosing.nestHost, k -> new ArrayList<>()).add(desc);
            }
        });
        output.put(internalName, bytes);
        return desc;
    }

    /** The name of the continuation class of a suspend function, as kotlinc's: Outer$function$1. */
    String continuationClassName(ClassCodegen enclosing, String methodName) {
        String base = enclosing.internalName + "$" + methodName.replace('<', '_').replace('>', '_') + "$";
        return base + localClassCounters.merge(base, 1, Integer::sum);
    }

    ClassCodegen generator(ClassSymbol cls) {
        return generators.get(cls);
    }

    private void drain() {
        while (!pending.isEmpty()) {
            ClassCodegen g = pending.removeFirst();
            current = g;
            if (!keepGoing) {
                output.put(g.internalName, g.generate());
                continue;
            }
            try {
                output.put(g.internalName, g.generate());
            } catch (RuntimeException e) {
                errors.add(e);
            }
        }
    }

    /** Whether to go on after a class fails, collecting the errors. */
    public void keepGoing() {
        keepGoing = true;
    }

    /** The errors of the classes that failed, if going on. */
    public List<RuntimeException> errors() {
        return errors;
    }

    private boolean hasTopLevelCallables(FileContext file) {
        for (Decl d : file.file().declarations()) {
            if (d instanceof Decl.Function f && !f.modifiers().has(Modifier.EXPECT)
                    || d instanceof Decl.Property p && !p.modifiers().has(Modifier.EXPECT)) {
                return true;
            }
        }
        return false;
    }

    /** The top-level functions and properties declared in a file. */
    List<CallableSymbol> topLevelCallables(FileContext file) {
        List<CallableSymbol> result = new ArrayList<>();
        MemberScope scope = resolver.table().packageScope(file.packageName());
        for (Decl d : file.file().declarations()) {
            if (d instanceof Decl.Function f && !f.modifiers().has(Modifier.EXPECT)) {
                for (FunctionSymbol s : scope.functions(f.name())) if (s.source() == d) result.add(s);
            } else if (d instanceof Decl.Property p && !p.modifiers().has(Modifier.EXPECT)) {
                for (PropertySymbol s : scope.properties(p.name())) if (s.source() == d) result.add(s);
            }
        }
        return result;
    }

    /** The checked bodies of a local class, for what it captures. */
    private Closures.Bodies classBodies(ClassSymbol cls) {
        return cls instanceof KotlinClassSymbol k ? ClassCodegen.bodies(this, k) : Closures.Bodies.NONE;
    }

    /**
     * The nests of the classes compiled here, one per file, so that the
     * classes of a file can use each other's private members as Kotlin
     * allows, without kotlinc's synthetic accessors.
     */
    static final class Nests {
        private final Map<String, List<ClassDesc>> members = new LinkedHashMap<>();

        /** Records a class as in its nest, which it names if it is not the host. */
        void member(ClassCodegen g, ClassBuilder cb) {
            if (g.nestHost == null || g.nestHost.equals(g.internalName)) return;
            cb.with(NestHostAttribute.of(ClassDesc.ofInternalName(g.nestHost)));
            members.computeIfAbsent(g.nestHost, k -> new ArrayList<>()).add(g.desc);
        }

        /** Records a synthetic class as in a nest. */
        void member(String host, ClassDesc member, ClassBuilder cb) {
            cb.with(NestHostAttribute.of(ClassDesc.ofInternalName(host)));
            members.computeIfAbsent(host, k -> new ArrayList<>()).add(member);
        }

        /** Names the members of each nest in its host, once all are generated. */
        void addMembers(Map<String, byte[]> output) {
            for (Map.Entry<String, List<ClassDesc>> e : members.entrySet()) {
                byte[] host = output.get(e.getKey());
                if (host == null) continue;
                ClassFile cf = ClassFile.of();
                output.put(e.getKey(), cf.transformClass(cf.parse(host),
                        ClassTransform.endHandler(cb -> cb.with(NestMembersAttribute.ofSymbols(e.getValue())))));
            }
        }
    }

    /** The superclass and interfaces of the classes compiled here, for stack map frames. */
    private record SourceHierarchy(JvmBackend backend) implements ClassHierarchyResolver {
        @Override
        public ClassHierarchyInfo getClassInfo(ClassDesc desc) {
            String name = desc.descriptorString();
            name = name.substring(1, name.length() - 1);
            ClassDesc synthetic = backend.syntheticSupers.get(name);
            if (synthetic != null) return ClassHierarchyInfo.ofClass(synthetic);
            ClassSymbol cls = backend.types.classByInternalName(name);
            if (cls == null) return null;
            if (cls.isInterface()) return ClassHierarchyInfo.ofInterface();
            ClassDesc superclass = TypeMapper.OBJECT;
            for (KType t : cls.supertypes()) {
                if (t instanceof KType.ClassType c && !c.symbol().isInterface()) {
                    superclass = backend.types.classDesc(c.symbol());
                    break;
                }
            }
            if (cls.kind() == kotlinbootstrap.symbols.ClassKind.ENUM_CLASS) {
                superclass = ClassDesc.ofInternalName("java/lang/Enum");
            }
            return ClassHierarchyInfo.ofClass(superclass);
        }
    }

    static boolean isLocalOrigin(ClassifierSymbol c) {
        return c instanceof ClassSymbol cls && cls.isLocal();
    }
}
