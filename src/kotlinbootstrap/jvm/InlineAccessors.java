package kotlinbootstrap.jvm;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
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
import kotlinbootstrap.check.TExpr;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.Visibility;

/**
 * Synthetic accessors of the private members that inline functions use,
 * as kotlinc's SyntheticAccessorLowering: an inline function's body is
 * copied into classes of other files, outside the nest of its own, where
 * those members are inaccessible. Each class with such members has static
 * {@code access$} methods for them, which the copies call instead; which
 * members those are follows from the file alone, so code compiled
 * separately agrees on it.
 */
final class InlineAccessors {
    private final JvmBackend backend;
    private final Map<FileContext, Set<CallableSymbol>> used = new IdentityHashMap<>();

    InlineAccessors(JvmBackend backend) {
        this.backend = backend;
    }

    /** The private members of a file that its inline functions use. */
    private Set<CallableSymbol> used(FileContext file) {
        Set<CallableSymbol> result = used.get(file);
        if (result != null) return result;
        Set<CallableSymbol> found = Collections.newSetFromMap(new IdentityHashMap<>());
        used.put(file, found);
        for (CallableSymbol s : backend.topLevelCallables(file)) scanCallable(s, found);
        for (Decl d : file.file().declarations()) {
            if (d instanceof Decl.Class c && backend.resolver.table().packageScope(file.packageName())
                    .classifier(c.name()) instanceof KotlinClassSymbol cls && cls.decl() == c) {
                scanClass(cls, found);
            }
        }
        return found;
    }

    private void scanClass(KotlinClassSymbol cls, Set<CallableSymbol> found) {
        for (List<FunctionSymbol> fs : cls.members().functions().values()) for (FunctionSymbol f : fs) scanCallable(f, found);
        for (KotlinClassSymbol nested : JvmBackend.nestedClasses(cls)) scanClass(nested, found);
    }

    private void scanCallable(CallableSymbol s, Set<CallableSymbol> found) {
        if (!(s instanceof FunctionSymbol f) || !f.has(Modifier.INLINE) || f.has(Modifier.EXPECT)
                || !(f.source() instanceof Decl.Function d) || d.body() == null) {
            return;
        }
        CheckedFunction body = backend.checker.checkedFunction(f);
        scan(body.body(), found);
        for (TExpr dv : body.defaultValues()) scan(dv, found);
    }

    private void scan(TExpr e, Set<CallableSymbol> found) {
        if (e == null) return;
        switch (e) {
            case TExpr.Call c when isPrivateMember(c.function()) && !c.function().isConstructor() ->
                    found.add(c.function());
            case TExpr.PropertyGet g when isPrivateMember(g.property()) -> found.add(g.property());
            case TExpr.PropertySet s when isPrivateMember(s.property()) -> found.add(s.property());
            case TExpr.Lambda l -> scan(l.function().body(), found);
            case TExpr.LocalFunctionDecl d -> scan(d.body().body(), found);
            default -> {
            }
        }
        Closures.children(e, c -> scan(c, found));
    }

    private static boolean isPrivateMember(CallableSymbol s) {
        if (s.visibility() != Visibility.PRIVATE || s.owner() == null) return false;
        return !(s instanceof PropertySymbol p) || !p.has(Modifier.CONST) && !p.isJavaField();
    }

    // ==== Uses ====

    /** Whether code of the class being generated reaches a private member through its accessor. */
    boolean needed(ClassCodegen from, CallableSymbol member) {
        if (member.visibility() != Visibility.PRIVATE || member.owner() == null) return false;
        FileContext file = backend.names.file(member);
        return file != null && file != from.file && used(file).contains(member);
    }

    /** The class an accessor of a member is in: the member's, or its file's part. */
    private ClassDesc host(CallableSymbol member) {
        if (member.owner() instanceof KotlinClassSymbol cls) return backend.types.classDesc(cls);
        return ClassDesc.ofInternalName(backend.names.filePart(backend.names.file(member)));
    }

    /** The accessor calling a method of a member: static, taking the receiver first if any. */
    Calls.Target method(CallableSymbol member, Calls.Target target) {
        MethodTypeDesc type = target.opcode() == Opcode.INVOKESTATIC ? target.type()
                : target.type().insertParameterTypes(0, target.owner());
        return new Calls.Target(Opcode.INVOKESTATIC, host(member), "access$" + target.name(), type, false);
    }

    /** The accessor reading or writing a property's field. */
    Calls.Target field(PropertySymbol p, boolean getter) {
        Members.FieldRef f = Members.field(backend, p);
        List<ClassDesc> params = new ArrayList<>();
        if (!f.isStatic()) params.add(f.owner());
        if (!getter) params.add(f.type());
        MethodTypeDesc type = MethodTypeDesc.of(getter ? f.type() : TypeMapper.VOID, params);
        String name = "access$" + (getter ? "get" : "set") + JvmNames.capitalize(p.name()) + "$p";
        return new Calls.Target(Opcode.INVOKESTATIC, host(p), name, type, false);
    }

    // ==== Accessors ====

    /** Adds the accessors of the private members of a class, or of a file's part, its file's inline functions use. */
    void generate(ClassCodegen g) {
        for (CallableSymbol m : used(g.file)) {
            boolean ours = g.cls != null ? m.owner() == g.cls : !(m.owner() instanceof KotlinClassSymbol);
            if (!ours) continue;
            switch (m) {
                case FunctionSymbol f -> {
                    MethodTypeDesc type = backend.types.methodType(f);
                    String name = backend.names.functionName(f);
                    forward(g, new Calls.Target(opcode(g, f), g.desc, name, type, g.isInterface));
                }
                case PropertySymbol p when Calls.isDirectFieldAccess(p) -> {
                    fieldAccessor(g, p, true);
                    if (p.isVar()) fieldAccessor(g, p, false);
                }
                case PropertySymbol p -> {
                    Opcode op = opcode(g, p);
                    forward(g, new Calls.Target(op, g.desc, backend.names.getterName(p), backend.types.getterType(p),
                            g.isInterface));
                    if (p.isVar()) {
                        forward(g, new Calls.Target(op, g.desc, backend.names.setterName(p),
                                backend.types.setterType(p), g.isInterface));
                    }
                }
                default -> {
                }
            }
        }
    }

    private static Opcode opcode(ClassCodegen g, CallableSymbol member) {
        if (Calls.isStatic(member)) return Opcode.INVOKESTATIC;
        return g.isInterface ? Opcode.INVOKEINTERFACE : Opcode.INVOKEVIRTUAL;
    }

    private void forward(ClassCodegen g, Calls.Target target) {
        Calls.Target accessor = new Calls.Target(Opcode.INVOKESTATIC, g.desc, "access$" + target.name(),
                target.opcode() == Opcode.INVOKESTATIC ? target.type()
                        : target.type().insertParameterTypes(0, g.desc), false);
        g.addMethod(accessor.name(), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                accessor.type(), null, null, e -> {
                    loadAll(e, accessor.type());
                    new Calls(e).emit(target);
                    e.returnValue(target.type().returnType());
                });
    }

    private void fieldAccessor(ClassCodegen g, PropertySymbol p, boolean getter) {
        Calls.Target accessor = field(p, getter);
        Members.FieldRef f = Members.field(backend, p);
        g.addMethod(accessor.name(), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                accessor.type(), null, null, e -> {
                    loadAll(e, accessor.type());
                    if (getter && f.isStatic()) e.code.getstatic(f.owner(), f.name(), f.type());
                    else if (getter) e.code.getfield(f.owner(), f.name(), f.type());
                    else if (f.isStatic()) e.code.putstatic(f.owner(), f.name(), f.type());
                    else e.code.putfield(f.owner(), f.name(), f.type());
                    e.returnValue(accessor.type().returnType());
                });
    }

    private static void loadAll(ExprCodegen e, MethodTypeDesc type) {
        int slot = 0;
        for (ClassDesc p : type.parameterList()) {
            e.code.loadLocal(TypeKind.from(p), slot);
            slot += TypeKind.from(p).slotSize();
        }
    }
}
