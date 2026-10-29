package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.resolve.TypeResolver;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.PackageSymbol;
import kotlinbootstrap.symbols.Symbol;
import kotlinbootstrap.symbols.TypeAliasSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;
import kotlinbootstrap.types.Variance;

/**
 * Functional interfaces, as FIR's FirSamResolver: Java interfaces and
 * Kotlin {@code fun interface}s with a single abstract method. A function
 * value converts to one (SAM conversion), and each has a synthetic SAM
 * constructor {@code fun <T> Foo(function: (A) -> R): Foo<T>}.
 */
final class SamResolver {
    private final TypeSystem ts;
    private final TypeResolver types;
    private final MemberScopes scopes;
    private final Map<ClassSymbol, Optional<KType.ClassType>> functionTypes = new HashMap<>();
    private final Map<ClassifierSymbol, Optional<FunctionSymbol>> constructors = new HashMap<>();

    /**
     * A SAM conversion: the function type a value is given for the SAM type.
     *
     * @param functionType the type of the function value, with the SAM type's arguments
     */
    record SamConversion(KType functionType, KType samType) {
    }

    SamResolver(TypeSystem ts, TypeResolver types, MemberScopes scopes) {
        this.ts = ts;
        this.types = types;
        this.scopes = scopes;
    }

    boolean isSamType(KType type) {
        return switch (type) {
            case KType.ClassType c -> functionType(c.symbol()) != null;
            case KType.Flexible f -> isSamType(f.lower()) && isSamType(f.upper());
            default -> false;
        };
    }

    /** The conversion to a SAM type, or null if the type is not one. */
    SamConversion conversion(KType type) {
        KType functionType = functionTypeOf(type);
        return functionType == null ? null : new SamConversion(functionType, type);
    }

    private KType functionTypeOf(KType type) {
        return switch (type) {
            case KType.ClassType c -> {
                KType.ClassType unsubstituted = functionType(c.symbol());
                if (unsubstituted == null) yield null;
                yield substitutorWithUpperBounds(c.symbol().typeParams(), c.args()).apply(unsubstituted)
                        .withNullable(c.nullable());
            }
            case KType.Flexible f -> {
                KType lower = functionTypeOf(f.lower());
                KType upper = lower == null ? null : functionTypeOf(f.upper());
                yield upper == null ? null : Types.flexible(Types.lowerBound(lower), Types.upperBound(upper));
            }
            case KType.Captured c when c.lowerType() != null -> functionTypeOf(c.lowerType());
            default -> null;
        };
    }

    // ==== SAM constructors ====

    /** The SAM constructor of a functional interface or a type alias for one; null if it is not one. */
    FunctionSymbol samConstructor(ClassifierSymbol classifier) {
        Optional<FunctionSymbol> cached = constructors.get(classifier);
        if (cached != null) return cached.orElse(null);
        FunctionSymbol result = switch (classifier) {
            case ClassSymbol cls -> samConstructorForClass(cls);
            case TypeAliasSymbol alias -> samConstructorForTypeAlias(alias);
            default -> null;
        };
        constructors.put(classifier, Optional.ofNullable(result));
        return result;
    }

    /** Whether a function is the SAM constructor of some class. */
    static boolean isSamConstructor(FunctionSymbol f) {
        return f.origin() == Origin.SYNTHETIC && f.source() instanceof ClassifierSymbol;
    }

    private FunctionSymbol samConstructorForClass(ClassSymbol cls) {
        KType.ClassType functionType = functionType(cls);
        if (functionType == null) return null;
        List<TypeParamSymbol> typeParams = new ArrayList<>();
        Map<Object, TypeArg> toNew = new HashMap<>();
        for (TypeParamSymbol tp : cls.typeParams()) {
            // The bounds are substituted once all the parameters are there.
            TypeParamSymbol fresh = new TypeParamSymbol(tp.name(), Origin.SYNTHETIC, Variance.INVARIANT, false,
                    () -> new Substitution(toNew).applyAll(tp.bounds()));
            typeParams.add(fresh);
            toNew.put(tp, TypeArg.of(new KType.TypeParamType(fresh, false)));
        }
        Substitution substitution = new Substitution(toNew);
        List<TypeArg> args = new ArrayList<>();
        for (TypeParamSymbol tp : typeParams) args.add(TypeArg.of(new KType.TypeParamType(tp, false)));
        KType returnType = new KType.ClassType(cls, args, false);
        return constructor(cls, cls.visibility(), typeParams, substitution.apply(functionType), returnType);
    }

    private FunctionSymbol samConstructorForTypeAlias(TypeAliasSymbol alias) {
        if (!(Types.lowerBound(alias.expansion()) instanceof KType.ClassType expanded)) return null;
        FunctionSymbol forClass = samConstructor(expanded.symbol());
        if (forClass == null) return null;
        Substitution substitution = substitutorWithUpperBounds(forClass.typeParams(), expanded.args());
        KType parameterType = substitution.apply(forClass.signature().params().getFirst().type());
        return constructor(alias, alias.visibility(), alias.typeParams(), parameterType, expanded);
    }

    private static FunctionSymbol constructor(ClassifierSymbol classifier, Visibility visibility,
                                              List<TypeParamSymbol> typeParams, KType functionType, KType returnType) {
        ClassSymbol outer = switch (classifier) {
            case ClassSymbol c -> c.outer();
            default -> null;
        };
        String packageName = switch (classifier) {
            case ClassSymbol c -> c.id().packageName();
            case TypeAliasSymbol a -> a.id().packageName();
            default -> "";
        };
        Symbol owner = outer != null ? outer : new PackageSymbol(packageName);
        CallableSymbol.Signature signature = new CallableSymbol.Signature(null, List.of(),
                List.of(new ValueParamSymbol("function", functionType, false, Set.of(), null)));
        return new FunctionSymbol(classifier.name(), Origin.SYNTHETIC, owner, visibility, Modality.FINAL, Set.of(),
                outer != null, classifier, () -> typeParams, () -> signature, () -> returnType);
    }

    /**
     * From the type parameters to the arguments, as FIR's
     * buildSubstitutorWithUpperBounds: a projection gives its type, a star
     * the first bound of the parameter.
     */
    private Substitution substitutorWithUpperBounds(List<TypeParamSymbol> typeParams, List<TypeArg> args) {
        if (typeParams.isEmpty() || typeParams.size() != args.size()) return Substitution.EMPTY;
        Map<Object, TypeArg> map = new HashMap<>();
        for (int i = 0; i < typeParams.size(); i++) {
            TypeArg a = args.get(i);
            KType type = a.isStar() ? typeParams.get(i).bounds().getFirst() : a.type();
            map.put(typeParams.get(i), TypeArg.of(type));
        }
        return new Substitution(map);
    }

    // ==== Single abstract methods ====

    /**
     * The function type of the single abstract method of a functional
     * interface, in terms of its type parameters, as FIR's
     * resolveFunctionTypeIfSamInterface; null if it is not one.
     */
    private KType.ClassType functionType(ClassSymbol cls) {
        Optional<KType.ClassType> cached = functionTypes.get(cls);
        if (cached != null) return cached.orElse(null);
        functionTypes.put(cls, Optional.empty()); // in case of a cycle in the supertypes
        KType.ClassType result = null;
        if (cls.kind() == ClassKind.INTERFACE && (isJava(cls) || cls.has(Modifier.FUN))) {
            MemberScopes.Member method = singleAbstractMethod(cls);
            if (method != null) result = functionType(method);
        }
        functionTypes.put(cls, Optional.ofNullable(result));
        return result;
    }

    private KType.ClassType functionType(MemberScopes.Member method) {
        FunctionSymbol f = (FunctionSymbol) method.symbol();
        Substitution s = method.substitution();
        List<KType> params = new ArrayList<>();
        for (ValueParamSymbol p : f.signature().params()) {
            params.add(s.apply(p.isVararg() ? types.varargArrayType(p.type()) : p.type()));
        }
        KType receiver = f.signature().receiver() == null ? null : s.apply(f.signature().receiver());
        FunctionTypes.Kind kind = f.has(Modifier.SUSPEND) ? FunctionTypes.Kind.SUSPEND_FUNCTION
                : FunctionTypes.Kind.FUNCTION;
        return FunctionTypes.create(ts.table(), kind, s.applyAll(f.signature().contextParams()), receiver, params,
                s.apply(f.returnType()));
    }

    /** As FIR's getSingleAbstractMethodOrNull, with the substitution for the interface's own type parameters. */
    private MemberScopes.Member singleAbstractMethod(ClassSymbol cls) {
        if (hasMoreThanOneAbstractFunctionOrHasAbstractProperty(cls)) return null;
        Set<String> names = new LinkedHashSet<>();
        collectAbstractMemberNames(cls, names, new LinkedHashSet<>());
        MemberScopes.Member result = null;
        for (String name : names) {
            for (MemberScopes.Member p : scopes.classMembers(cls, name, false)) {
                if (p.symbol().modality() == Modality.ABSTRACT) return null;
            }
            for (MemberScopes.Member f : scopes.classMembers(cls, name, true)) {
                if (f.symbol().modality() != Modality.ABSTRACT || isPublicInObject(f.symbol(), false)) continue;
                if (result != null) return null;
                result = f;
            }
        }
        return result == null || !result.symbol().typeParams().isEmpty() ? null : result;
    }

    private static boolean hasMoreThanOneAbstractFunctionOrHasAbstractProperty(ClassSymbol cls) {
        for (var ps : cls.members().properties().values()) {
            for (var p : ps) {
                if (p.modality() == Modality.ABSTRACT) return true;
            }
        }
        boolean abstractFunction = false;
        for (var fs : cls.members().functions().values()) {
            for (var f : fs) {
                if (f.isStatic() || f.modality() != Modality.ABSTRACT || isPublicInObject(f, true)) continue;
                if (abstractFunction) return true;
                abstractFunction = true;
            }
        }
        return false;
    }

    /** The names of the abstract members of the class and its supertypes, as FIR's computeSamCandidateNames. */
    private static void collectAbstractMemberNames(ClassSymbol cls, Set<String> names, Set<ClassSymbol> seen) {
        if (!seen.add(cls)) return;
        cls.members().properties().forEach((name, ps) -> {
            if (ps.stream().anyMatch(p -> p.modality() == Modality.ABSTRACT)) names.add(name);
        });
        cls.members().functions().forEach((name, fs) -> {
            if (fs.stream().anyMatch(f -> !f.isStatic() && f.modality() == Modality.ABSTRACT)) names.add(name);
        });
        for (KType st : cls.supertypes()) {
            if (st instanceof KType.ClassType c) collectAbstractMemberNames(c.symbol(), names, seen);
        }
    }

    /**
     * Whether a Java method has the signature of a public method of
     * {@code Object}, so that it does not count as abstract (JLS 9.8).
     */
    private static boolean isPublicInObject(CallableSymbol f, boolean checkOnlyName) {
        if (f.origin() != Origin.JAVA_SOURCE && f.origin() != Origin.JAVA_BINARY) return false;
        List<ValueParamSymbol> params = f.signature().params();
        return switch (f.name()) {
            case "hashCode", "getClass", "notify", "notifyAll", "toString" -> checkOnlyName || params.isEmpty();
            case "equals" -> checkOnlyName || params.size() == 1 && isClass(params.getFirst().type(), "kotlin.Any");
            case "wait" -> checkOnlyName || switch (params.size()) {
                case 0 -> true;
                case 1 -> isClass(params.getFirst().type(), "kotlin.Long");
                case 2 -> isClass(params.get(0).type(), "kotlin.Long") && isClass(params.get(1).type(), "kotlin.Int");
                default -> false;
            };
            default -> false;
        };
    }

    private static boolean isClass(KType type, String fqName) {
        return Types.lowerBound(type) instanceof KType.ClassType c && c.symbol().id().fqName().equals(fqName);
    }

    private static boolean isJava(ClassSymbol cls) {
        return cls.origin() == Origin.JAVA_SOURCE || cls.origin() == Origin.JAVA_BINARY;
    }
}
