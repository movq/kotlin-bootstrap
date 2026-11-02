package kotlinbootstrap.jvm;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.Variance;

/**
 * Generic signatures of classes, methods and fields, the Signature
 * attributes Java compilers read, as kotlinc's KotlinTypeMapper with its
 * TypeMappingModes: declaration-site variance becomes wildcards in
 * parameter types, except where no other argument could be passed, and
 * not in return types and supertypes.
 */
final class Signatures {
    /**
     * How type arguments are written, as kotlinc's TypeMappingMode: the
     * modes of nested arguments by their effective variance, null for this.
     */
    private record Mode(boolean skipDeclarationSiteWildcards, boolean skipDeclarationSiteWildcardsIfPossible,
                        Mode covariant, Mode contravariant, Mode invariant) {
        Mode argument(Variance effective) {
            Mode m = switch (effective) {
                case IN -> contravariant;
                case INVARIANT -> invariant;
                case OUT -> covariant;
            };
            return m != null ? m : this;
        }
    }

    private static final Mode GENERIC_ARGUMENT = new Mode(false, false, null, null, null);
    private static final Mode SUPER_TYPE = new Mode(true, false, GENERIC_ARGUMENT, GENERIC_ARGUMENT, GENERIC_ARGUMENT);
    /** As kotlinc's getOptimalModeForReturnType. */
    private static final Mode RETURN_TYPE = new Mode(true, true, null, new Mode(false, true, null, null, null), null);
    /** As kotlinc's getOptimalModeForValueParameter. */
    private static final Mode VALUE_PARAMETER = new Mode(false, true, null, null, RETURN_TYPE);

    private final TypeMapper types;

    Signatures(TypeMapper types) {
        this.types = types;
    }

    /** A class's signature; null where the class is not generic and extends no generic types. */
    String classSignature(KotlinClassSymbol cls, ClassDesc superclass, List<ClassDesc> interfaces) {
        StringBuilder sb = new StringBuilder();
        typeParams(sb, cls.typeParams());
        KType superType = null;
        List<KType> interfaceTypes = new ArrayList<>();
        for (KType t : cls.supertypes()) {
            if (!(t instanceof KType.ClassType c)) continue;
            if (!c.symbol().isInterface()) superType = t;
            else if (interfaceTypes.stream().noneMatch(i -> ((KType.ClassType) i).symbol() == c.symbol())) {
                interfaceTypes.add(t);
            }
        }
        if (cls.kind() == ClassKind.ENUM_CLASS) {
            sb.append("Ljava/lang/Enum<").append(types.classDesc(cls).descriptorString()).append(">;");
        } else if (superType != null && superclass.equals(types.classDesc(((KType.ClassType) superType).symbol()))) {
            type(sb, superType, SUPER_TYPE);
        } else {
            sb.append(superclass.descriptorString());
        }
        int i = 0;
        for (ClassDesc d : interfaces) {
            if (i < interfaceTypes.size() && d.equals(types.classDesc(((KType.ClassType) interfaceTypes.get(i)).symbol()))) {
                type(sb, interfaceTypes.get(i++), SUPER_TYPE);
            } else {
                sb.append(d.descriptorString());
            }
        }
        String s = sb.toString();
        return s.indexOf('<') < 0 ? null : s;
    }

    /**
     * A function's or accessor's signature: its type parameters, the generic
     * forms of the parameters and return type of its descriptor; null where
     * it says no more than the descriptor.
     */
    String methodSignature(CallableSymbol f, MethodTypeDesc desc, boolean setter) {
        if (f.has(Modifier.SUSPEND)) return null;
        List<KType> params = new ArrayList<>();
        List<Boolean> varargs = new ArrayList<>();
        for (KType c : f.signature().contextParams()) {
            params.add(c);
            varargs.add(false);
        }
        if (f.signature().receiver() != null) {
            params.add(f.signature().receiver());
            varargs.add(false);
        }
        if (f instanceof FunctionSymbol) {
            for (ValueParamSymbol p : f.signature().params()) {
                params.add(p.type());
                varargs.add(p.isVararg());
            }
        }
        if (setter) {
            params.add(f.returnType());
            varargs.add(false);
        }
        if (params.size() != desc.parameterCount()) return null;
        StringBuilder sb = new StringBuilder();
        if (!(f instanceof FunctionSymbol fn && fn.isConstructor())) typeParams(sb, f.typeParams());
        sb.append('(');
        for (int i = 0; i < params.size(); i++) {
            ClassDesc d = desc.parameterType(i);
            if (varargs.get(i) && d.isArray()) {
                sb.append('[');
                part(sb, params.get(i), d.componentType(), VALUE_PARAMETER);
            } else {
                part(sb, params.get(i), d, VALUE_PARAMETER);
            }
        }
        sb.append(')');
        part(sb, f.returnType(), desc.returnType(), RETURN_TYPE);
        String s = sb.toString();
        return s.equals(desc.descriptorString()) ? null : s;
    }

    /** A property's field's signature; null where it says no more than its type. */
    String fieldSignature(PropertySymbol p, ClassDesc desc) {
        StringBuilder sb = new StringBuilder();
        part(sb, p.returnType(), desc, p.isVar() ? VALUE_PARAMETER : RETURN_TYPE);
        String s = sb.toString();
        return s.equals(desc.descriptorString()) ? null : s;
    }

    /** A type in a signature: as its descriptor where that is primitive or does not erase it. */
    private void part(StringBuilder sb, KType t, ClassDesc desc, Mode mode) {
        if (desc.isPrimitive() || desc.isArray() && desc.componentType().isPrimitive()
                || !types.mapBoxedType(t).equals(desc) && !(t instanceof KType.TypeParamType)) {
            sb.append(desc.descriptorString());
            return;
        }
        type(sb, t, hasArguments(t) ? mode : GENERIC_ARGUMENT);
    }

    private static boolean hasArguments(KType t) {
        return t instanceof KType.ClassType c && !c.args().isEmpty();
    }

    private void typeParams(StringBuilder sb, List<TypeParamSymbol> params) {
        if (params.isEmpty()) return;
        sb.append('<');
        for (TypeParamSymbol p : params) {
            sb.append(p.name()).append(':');
            boolean classBound = false;
            for (KType b : p.bounds()) {
                if (!(b instanceof KType.TypeParamType) && !isInterface(b)) {
                    type(sb, b, GENERIC_ARGUMENT);
                    classBound = true;
                    break;
                }
            }
            boolean interfaceBound = false;
            for (KType b : p.bounds()) {
                if (b instanceof KType.TypeParamType || isInterface(b)) {
                    sb.append(':');
                    type(sb, b, GENERIC_ARGUMENT);
                    interfaceBound = true;
                }
            }
            if (!classBound && !interfaceBound) sb.append("Ljava/lang/Object;");
        }
        sb.append('>');
    }

    private static boolean isInterface(KType t) {
        return lower(t) instanceof KType.ClassType c && c.symbol().isInterface();
    }

    private static KType lower(KType t) {
        return switch (t) {
            case KType.Flexible f -> lower(f.lower());
            case KType.DefinitelyNotNull d -> lower(d.inner());
            default -> t;
        };
    }

    /** A reference type in generic form, as kotlinc's writeGenericType. */
    private void type(StringBuilder sb, KType t, Mode mode) {
        switch (t) {
            case KType.ClassType c -> classType(sb, c, mode);
            case KType.TypeParamType p -> sb.append('T').append(p.symbol().name()).append(';');
            case KType.Flexible f -> type(sb, f.upper(), mode);
            case KType.DefinitelyNotNull d -> type(sb, d.inner(), mode);
            case KType.IntegerLiteral l -> type(sb, l.possibleTypes().getFirst().withNullable(true), mode);
            case KType.Intersection i -> type(sb, i.components().getFirst(), mode);
            default -> sb.append(types.mapBoxedType(t).descriptorString());
        }
    }

    private void classType(StringBuilder sb, KType.ClassType c, Mode mode) {
        ClassSymbol cls = c.symbol();
        ClassDesc desc = types.mapBoxedType(c);
        if (TypeMapper.isClass(cls, "kotlin.Array")) {
            TypeArg element = c.args().isEmpty() ? TypeArg.STAR : c.args().getFirst();
            if (element.isStar()) {
                sb.append("[Ljava/lang/Object;");
            } else {
                sb.append('[');
                type(sb, element.type(), mode.argument(element.variance()));
            }
            return;
        }
        List<TypeParamSymbol> params = cls.typeParams();
        if (desc.isArray() || c.args().isEmpty() || c.args().size() < params.size()
                || hasNothingInNonContravariantPosition(c) || isSpecialFunction(cls, c.args().size())) {
            sb.append(desc.descriptorString());
            return;
        }
        String name = desc.descriptorString();
        name = name.substring(1, name.length() - 1);
        int own = params.size();
        if (c.args().size() > own && cls.isInner() && cls.outer() != null) {
            // An inner class of a generic class: Outer<A>.Inner<B>, its arguments its own then its outer's.
            ClassSymbol outer = cls.outer();
            String outerName = types.internalName(outer);
            sb.append('L').append(outerName);
            arguments(sb, c.args().subList(own, c.args().size()), outer.typeParams(), mode);
            sb.append('.').append(name.substring(outerName.length() + 1));
            arguments(sb, c.args().subList(0, own), params, mode);
            sb.append(';');
            return;
        }
        sb.append('L').append(name);
        arguments(sb, c.args().subList(0, Math.min(own, c.args().size())), params, mode);
        sb.append(';');
    }

    /** Suspend function types, which take a continuation too, and big-arity ones, as raw types. */
    private static boolean isSpecialFunction(ClassSymbol cls, int arity) {
        String pkg = cls.id().packageName();
        String name = cls.id().relativeName();
        if (pkg.equals("kotlin.coroutines") && name.startsWith("SuspendFunction")) return true;
        if (pkg.equals("kotlin.reflect") && name.startsWith("KSuspendFunction")) return true;
        return (pkg.equals("kotlin") && name.startsWith("Function") || pkg.equals("kotlin.reflect")
                && name.startsWith("KFunction")) && arity > 23;
    }

    private void arguments(StringBuilder sb, List<TypeArg> args, List<TypeParamSymbol> params, Mode mode) {
        if (args.isEmpty()) return;
        sb.append('<');
        for (int i = 0; i < args.size(); i++) {
            TypeArg a = args.get(i);
            TypeParamSymbol p = i < params.size() ? params.get(i) : null;
            Variance parameterVariance = p != null ? p.variance() : Variance.INVARIANT;
            if (a.isStar() || TypeMapper.isNothing(a.type()) && parameterVariance == Variance.IN) {
                sb.append('*');
                continue;
            }
            switch (wildcard(parameterVariance, a, mode)) {
                case OUT -> sb.append('+');
                case IN -> sb.append('-');
                case INVARIANT -> {
                }
            }
            type(sb, a.type(), mode.argument(effectiveVariance(parameterVariance, a.variance())));
        }
        sb.append('>');
    }

    /** As kotlinc's getVarianceForWildcard. */
    private static Variance wildcard(Variance parameterVariance, TypeArg a, Mode mode) {
        if (parameterVariance == Variance.INVARIANT) return a.variance();
        if (mode.skipDeclarationSiteWildcards()) return Variance.INVARIANT;
        if (a.variance() == Variance.INVARIANT || a.variance() == parameterVariance) {
            if (mode.skipDeclarationSiteWildcardsIfPossible()) {
                if (parameterVariance == Variance.OUT && isMostPreciseCovariantArgument(a.type())) {
                    return Variance.INVARIANT;
                }
                if (parameterVariance == Variance.IN && TypeMapper.isClass(a.type(), "kotlin.Any")) {
                    return Variance.INVARIANT;
                }
            }
            return parameterVariance;
        }
        return Variance.OUT;
    }

    private static Variance effectiveVariance(Variance parameterVariance, Variance projection) {
        if (parameterVariance == Variance.INVARIANT) return projection;
        if (projection == Variance.INVARIANT || projection == parameterVariance) return parameterVariance;
        return Variance.OUT;
    }

    /** Whether no subtype of a type could be passed for it, as kotlinc's: a final class with such arguments. */
    private static boolean isMostPreciseCovariantArgument(KType t) {
        if (!(lower(t) instanceof KType.ClassType c)) return false;
        ClassSymbol cls = c.symbol();
        boolean isFinal = cls.modality() == Modality.FINAL && cls.kind() != ClassKind.INTERFACE
                || cls.kind() == ClassKind.ENUM_ENTRY || cls.kind() == ClassKind.ANNOTATION_CLASS;
        if (!isFinal) return false;
        List<TypeParamSymbol> params = cls.typeParams();
        for (int i = 0; i < params.size() && i < c.args().size(); i++) {
            TypeArg a = c.args().get(i);
            if (a.isStar()) return false;
            Variance v = effectiveVariance(params.get(i).variance(), a.variance());
            if (v == Variance.OUT && !isMostPreciseCovariantArgument(a.type())) return false;
            if (v == Variance.IN && !TypeMapper.isClass(a.type(), "kotlin.Any")) return false;
        }
        return true;
    }

    /** As kotlinc's: such types are raw, Nothing having no Java form. */
    private static boolean hasNothingInNonContravariantPosition(KType.ClassType c) {
        List<TypeParamSymbol> params = c.symbol().typeParams();
        for (int i = 0; i < c.args().size(); i++) {
            TypeArg a = c.args().get(i);
            if (a.isStar() || !(a.type() instanceof KType.ClassType t)
                    || !TypeMapper.isClass(t.symbol(), "kotlin.Nothing")) {
                continue;
            }
            if (t.nullable() || i >= params.size() || params.get(i).variance() != Variance.IN) return true;
        }
        return false;
    }
}
