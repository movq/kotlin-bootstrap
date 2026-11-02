package kotlinbootstrap.jvm;

import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

import javax.lang.model.element.VariableElement;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.check.CheckedFunction;
import kotlinbootstrap.check.ConstantValue;
import kotlinbootstrap.check.TExpr;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.PackageSymbol;
import kotlinbootstrap.symbols.PropertySymbol;

/**
 * Property accesses: through accessors, or fields where kotlinc reads
 * them directly: Java fields, {@code @JvmField} and const properties,
 * backing fields from accessors and initializers, and enum entries.
 */
final class Members {
    /** A field: its class, name and type, and whether static. */
    record FieldRef(ClassDesc owner, String name, ClassDesc type, boolean isStatic) {
    }

    private final ExprCodegen gen;
    private final CodeBuilder code;

    Members(ExprCodegen gen) {
        this.gen = gen;
        this.code = gen.code;
    }

    // ==== Fields ====

    /** Whether a property is kept in a static field: top-level, or @JvmField or const in an object. */
    static boolean hasStaticField(PropertySymbol p) {
        if (p.owner() instanceof PackageSymbol || p.isStatic()) return true;
        if (p.owner() instanceof ClassSymbol cls && cls.isObject()) {
            return p.has(Modifier.CONST) || JvmNames.has(JvmNames.modifiers(p), "JvmField");
        }
        return false;
    }

    /**
     * The backing field of a property: in the file part for a top-level
     * one, in the outer class for a static one of a companion, otherwise
     * in its class.
     */
    static FieldRef field(JvmBackend backend, PropertySymbol p) {
        ClassDesc type = backend.types.fieldType(p);
        if (p.isJavaField()) {
            return new FieldRef(backend.types.classDesc((ClassSymbol) p.owner()), p.name(), type, p.isStatic());
        }
        if (p.owner() instanceof PackageSymbol) {
            FileContext file = backend.names.file(p);
            return new FieldRef(ClassDesc.ofInternalName(backend.names.filePart(file)), p.name(), type, true);
        }
        ClassSymbol cls = (ClassSymbol) p.owner();
        boolean isStatic = hasStaticField(p);
        if (isStatic && cls.has(Modifier.COMPANION) && cls.outer() != null && !cls.outer().isInterface()) {
            return new FieldRef(backend.types.classDesc(cls.outer()), p.name(), type, true);
        }
        return new FieldRef(backend.types.classDesc(cls), p.name(), type, isStatic);
    }

    /** The field a delegated property's delegate is kept in. */
    static FieldRef delegateField(JvmBackend backend, PropertySymbol p) {
        FieldRef f = field(backend, p);
        ClassDesc type = backend.types.mapType(backend.checker.checkedDelegate(p).delegate().type());
        return new FieldRef(f.owner(), p.name() + "$delegate", type.isPrimitive() ? TypeMapper.box(type) : type,
                f.isStatic());
    }

    ClassDesc fieldGet(PropertySymbol p, TExpr receiver) {
        FieldRef f = field(gen.backend, p);
        return fieldGet(f, receiver);
    }

    private ClassDesc fieldGet(FieldRef f, TExpr receiver) {
        if (f.isStatic()) {
            code.getstatic(f.owner(), f.name(), f.type());
        } else {
            loadReceiver(receiver, f.owner());
            code.getfield(f.owner(), f.name(), f.type());
        }
        return f.type();
    }

    void fieldSet(PropertySymbol p, TExpr receiver, TExpr value) {
        FieldRef f = field(gen.backend, p);
        if (f.isStatic()) {
            gen.genAs(value, f.type());
            code.putstatic(f.owner(), f.name(), f.type());
        } else {
            List<Object> spilled = gen.spillIfNeeded(java.util.Arrays.asList(receiver, value));
            if (spilled != null && receiver == null) spilled.set(0, null);
            if (spilled != null && spilled.get(0) instanceof ExprCodegen.Slot s) gen.loadSlotValue(s);
            else loadReceiver(receiver, f.owner());
            gen.coerce(gen.operand(value, spilled, 1, f.type()), f.type());
            code.putfield(f.owner(), f.name(), f.type());
        }
    }

    /** The receiver of a field: the given one, or this of the class in its own code. */
    private void loadReceiver(TExpr receiver, ClassDesc owner) {
        if (receiver != null) {
            gen.genAs(receiver, owner);
        } else {
            gen.coerce(gen.thisValue(gen.owner.cls), owner);
        }
    }

    ClassDesc delegateGet(PropertySymbol p, TExpr receiver) {
        if (p.owner() == null) {
            // A local delegated property: its delegate is in a slot.
            if (gen.env.lookup(p) instanceof ExprCodegen.Slot s) {
                gen.loadSlotValue(s);
                return s.type();
            }
        }
        return fieldGet(delegateField(gen.backend, p), receiver);
    }

    // ==== Accessors ====

    ClassDesc propertyGet(TExpr.PropertyGet g) {
        PropertySymbol p = g.property();
        if (p.has(Modifier.CONST) && p.source() instanceof Decl.Property) {
            return constant(gen.backend.checker.constValue(p));
        }
        if (p.isEnumEntry()) {
            ClassDesc owner = gen.types.classDesc((ClassSymbol) p.owner());
            code.getstatic(owner, p.name(), owner);
            return owner;
        }
        if (p.isJavaField()) {
            if (p.source() instanceof VariableElement e && e.getConstantValue() != null && p.isStatic()) {
                return javaConstant(e.getConstantValue(), gen.types.fieldType(p));
            }
            return fieldGet(field(gen.backend, p), p.isStatic() ? null : g.dispatchReceiver());
        }
        if (p.javaGetter() != null) {
            return new Calls(gen).invoke(p.javaGetter(), g.dispatchReceiver(), List.of(), null, List.of(), false);
        }
        if (Intrinsics.isBuiltin(gen.backend, p) && Intrinsics.arrayOwner(p) != null && p.name().equals("size")) {
            ClassDesc array = gen.mapType(g.dispatchReceiver().type());
            gen.genAs(g.dispatchReceiver(), array.isArray() ? array : TypeMapper.OBJECT.arrayType());
            code.arraylength();
            return ConstantDescs.CD_int;
        }
        if (JvmNames.has(JvmNames.modifiers(p), "JvmField")) {
            return fieldGet(field(gen.backend, p), g.dispatchReceiver());
        }
        CheckedFunction inline = inlineAccessor(p, true);
        if (inline != null) {
            return gen.inlineFunction(p, inline, g.typeArgs(), g.dispatchReceiver(), g.contextArgs(),
                    g.extensionReceiver(), List.of(), g.pos());
        }
        return new Calls(gen).invokeAccessor(p, true, g.dispatchReceiver(), g.contextArgs(), g.extensionReceiver(),
                null, g.superCall());
    }

    void propertySet(TExpr.PropertySet s) {
        PropertySymbol p = s.property();
        if (p.isJavaField()) {
            fieldSet(p, p.isStatic() ? null : s.dispatchReceiver(), s.value());
            return;
        }
        if (p.javaSetter() != null) {
            ClassDesc t = new Calls(gen).invoke(p.javaSetter(), s.dispatchReceiver(), List.of(), null,
                    List.of(new TExpr.Argument(0, s.value())), false);
            gen.pop(t);
            return;
        }
        if (JvmNames.has(JvmNames.modifiers(p), "JvmField")) {
            fieldSet(p, s.dispatchReceiver(), s.value());
            return;
        }
        CheckedFunction inline = inlineAccessor(p, false);
        if (inline != null) {
            gen.pop(gen.inlineFunction(p, inline, s.typeArgs(), s.dispatchReceiver(), s.contextArgs(),
                    s.extensionReceiver(), List.of(new TExpr.Argument(0, s.value())), s.pos()));
            return;
        }
        gen.pop(new Calls(gen).invokeAccessor(p, false, s.dispatchReceiver(), s.contextArgs(), s.extensionReceiver(),
                s.value(), s.superCall()));
    }

    /** The accessor of an inline property, or with an inline accessor, to inline; null if not inline. */
    private CheckedFunction inlineAccessor(PropertySymbol p, boolean getter) {
        if (!(p.source() instanceof Decl.Property d)) return null;
        Decl.Accessor a = getter ? d.getter() : d.setter();
        boolean inline = p.has(Modifier.INLINE) || a != null && a.modifiers().has(Modifier.INLINE);
        if (!inline || a == null || a.body() == null) return null;
        return gen.backend.checker.checkedAccessor(p, getter);
    }

    // ==== Constants ====

    private ClassDesc constant(ConstantValue v) {
        if (v instanceof ConstantValue.Primitive p) {
            ClassDesc t = gen.mapType(p.type());
            return javaConstant(p.value(), t);
        }
        throw new CodegenException(-1, "Not a primitive constant: " + v);
    }

    /** Pushes a constant of a primitive type or String. */
    ClassDesc javaConstant(Object value, ClassDesc type) {
        switch (value) {
            case null -> {
                code.aconst_null();
                return type;
            }
            case String s -> {
                code.ldc(s);
                return TypeMapper.STRING;
            }
            case Boolean b -> code.loadConstant(b ? 1 : 0);
            case Character c -> code.loadConstant((int) c);
            case Long l -> code.loadConstant(l);
            case Float f -> code.loadConstant(f);
            case Double d -> code.loadConstant(d);
            case Number n -> code.loadConstant(n.intValue());
            default -> throw new CodegenException(-1, "Unknown constant " + value);
        }
        ClassDesc primitive = type.isPrimitive() ? type : TypeMapper.unbox(type);
        if (primitive == null) {
            primitive = switch (value) {
                case Long l -> ConstantDescs.CD_long;
                case Float f -> ConstantDescs.CD_float;
                case Double d -> ConstantDescs.CD_double;
                case Boolean b -> ConstantDescs.CD_boolean;
                case Character c -> ConstantDescs.CD_char;
                default -> ConstantDescs.CD_int;
            };
        }
        return primitive;
    }
}
