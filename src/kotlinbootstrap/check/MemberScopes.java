package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import kotlinbootstrap.resolve.TypeResolver;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.CapturedTypeConstructor;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Variance;

/**
 * The members of types, as FIR's use-site member scopes: those a class
 * declares and those it inherits without overriding them, with the type
 * parameters of their classes substituted by the receiver's arguments.
 * Members are the original symbols, not substituted copies. Where several
 * supertypes have a member with the same signature, as for kotlinc's
 * intersection overrides, the first is kept.
 */
final class MemberScopes {
    /** A member, with the substitution from the type parameters of its class (and outer classes) for the receiver. */
    record Member(CallableSymbol symbol, Substitution substitution) {
    }

    /** An inner class, with the substitution for the type parameters of its outer classes. */
    record Inner(ClassSymbol symbol, Substitution substitution) {
    }

    private final TypeSystem ts;
    private final TypeResolver types;
    private final JvmMappedScope mapped;
    /** Members of a class by name, with substitutions in terms of the class's own type parameters. */
    private final Map<ClassSymbol, Map<String, List<Member>>> functions = new HashMap<>();
    private final Map<ClassSymbol, Map<String, List<Member>>> properties = new HashMap<>();
    private final Map<CallableSymbol, Set<CallableSymbol>> overridden = new HashMap<>();
    private final Map<ClassSymbol, Map<String, List<CallableSymbol>>> staticFunctions = new HashMap<>();
    private final Map<ClassSymbol, Map<String, List<CallableSymbol>>> staticProperties = new HashMap<>();
    /** Synthetic Java properties by getter and setter, so that each is one symbol. */
    private final Map<FunctionSymbol, Map<Optional<FunctionSymbol>, PropertySymbol>> synthetic = new HashMap<>();

    MemberScopes(TypeSystem ts, TypeResolver types, JvmMappedScope mapped) {
        this.ts = ts;
        this.types = types;
        this.mapped = mapped;
    }

    // ==== Members of types ====

    List<Member> functions(KType receiver, String name) {
        return members(receiver, name, true);
    }

    List<Member> properties(KType receiver, String name) {
        return members(receiver, name, false);
    }

    private List<Member> members(KType receiver, String name, boolean functions) {
        List<Member> result = new ArrayList<>();
        for (KType.ClassType c : classTypes(receiver)) {
            Substitution useSite = useSiteSubstitution(c);
            for (Member m : classMembers(c.symbol(), name, functions)) {
                add(result, new Member(m.symbol(), m.substitution().andThen(useSite)));
            }
        }
        return result;
    }

    // ==== Synthetic Java properties ====

    /**
     * The synthetic properties named so of the receiver, as FIR's
     * FirSyntheticPropertiesScope: Java getters {@code getFoo()} and
     * {@code isFoo()} as {@code foo}, with the setter of the same type if any.
     */
    List<Member> syntheticProperties(KType receiver, String name) {
        List<Member> result = new ArrayList<>();
        for (String getterName : possibleGetterNames(name)) {
            for (Member getter : functions(receiver, getterName)) {
                PropertySymbol p = syntheticProperty(receiver, name, getterName, getter);
                if (p != null) result.add(new Member(p, getter.substitution()));
            }
        }
        return result;
    }

    private PropertySymbol syntheticProperty(KType receiver, String name, String getterName, Member getter) {
        FunctionSymbol f = (FunctionSymbol) getter.symbol();
        if (!f.typeParams().isEmpty() || !f.signature().params().isEmpty() || f.isStatic()) return null;
        if (!hasJavaOrigin(f)) return null;
        // void getters are not properties.
        if (f.returnType() instanceof KType.ClassType c && c.symbol().id().fqName().equals("kotlin.Unit")) return null;
        KType type = getter.substitution().apply(f.returnType());
        FunctionSymbol setter = null;
        for (Member m : functions(receiver, setterName(getterName))) {
            FunctionSymbol s = (FunctionSymbol) m.symbol();
            if (!s.typeParams().isEmpty() || s.isStatic() || s.signature().params().size() != 1) continue;
            ValueParamSymbol param = s.signature().params().getFirst();
            // A setter taking a subtype of the getter's type would need the setter's overrides checked; not done.
            if (!param.isVararg() && ts.equalTypes(type, m.substitution().apply(param.type()))) {
                setter = s;
                break;
            }
        }
        FunctionSymbol matchingSetter = setter;
        return synthetic.computeIfAbsent(f, k -> new HashMap<>()).computeIfAbsent(Optional.ofNullable(setter),
                k -> PropertySymbol.syntheticJavaProperty(name, f, matchingSetter));
    }

    /**
     * Whether the getter is, or overrides, a Java method that overrides
     * nothing: since Kotlin 2.4, a getter only from Kotlin makes no property.
     */
    private boolean hasJavaOrigin(FunctionSymbol getter) {
        if (isJavaRoot(getter)) return true;
        for (CallableSymbol o : overridden(getter)) {
            if (isJavaRoot(o)) return true;
        }
        return false;
    }

    private static boolean isJavaRoot(CallableSymbol c) {
        return (c.origin() == Origin.JAVA_SOURCE || c.origin() == Origin.JAVA_BINARY) && Overrides.overridden(c).isEmpty();
    }

    /** As kotlinc's possibleGetMethodNames: for {@code fooBar}, {@code getFooBar}; for {@code isFoo}, also itself. */
    static List<String> possibleGetterNames(String property) {
        if (property.isEmpty()) return List.of();
        char first = property.charAt(0);
        if (!Character.isJavaIdentifierStart(first) || first >= 'A' && first <= 'Z') return List.of();
        List<String> result = new ArrayList<>();
        if (startsWithIsPrefix(property)) result.add(property);
        String capitalized = capitalizeAsciiOnly(property);
        String firstWord = capitalizeFirstWord(property);
        result.add("get" + capitalized);
        if (!firstWord.equals(capitalized)) result.add("get" + firstWord);
        // Not "uRL" for getURL.
        result.removeIf(getter -> !property.equals(propertyNameByGetterName(getter)));
        return result;
    }

    static String propertyNameByGetterName(String getter) {
        String name = propertyNameFromAccessorName(getter, "get", true);
        return name != null ? name : propertyNameFromAccessorName(getter, "is", false);
    }

    private static String propertyNameFromAccessorName(String method, String prefix, boolean removePrefix) {
        if (!method.startsWith(prefix) || method.length() == prefix.length()) return null;
        char c = method.charAt(prefix.length());
        if (c >= 'a' && c <= 'z') return null;
        if (!removePrefix) return method;
        String name = decapitalizeSmart(method.substring(prefix.length()));
        return Character.isJavaIdentifierStart(name.charAt(0)) ? name : null;
    }

    private static boolean startsWithIsPrefix(String name) {
        if (!name.startsWith("is") || name.length() == 2) return false;
        char c = name.charAt(2);
        return !(c >= 'a' && c <= 'z');
    }

    private static String setterName(String getterName) {
        return "set" + getterName.substring(getterName.startsWith("get") ? 3 : 2);
    }

    private static boolean isAsciiUpper(String s, int i) {
        char c = s.charAt(i);
        return c >= 'A' && c <= 'Z';
    }

    private static boolean isAsciiLower(String s, int i) {
        char c = s.charAt(i);
        return c >= 'a' && c <= 'z';
    }

    private static String capitalizeAsciiOnly(String s) {
        return isAsciiLower(s, 0) ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s;
    }

    /** "fooBar" is "FOOBar": the first word upper case. */
    private static String capitalizeFirstWord(String s) {
        int second = 1;
        while (second < s.length() && isAsciiLower(s, second)) second++;
        return s.substring(0, second).toUpperCase(Locale.ROOT) + s.substring(second);
    }

    /** "FooBar" is "fooBar", "URLConnection" is "urlConnection", "URL" is "url". */
    private static String decapitalizeSmart(String s) {
        if (s.isEmpty() || !isAsciiUpper(s, 0)) return s;
        if (s.length() == 1 || !isAsciiUpper(s, 1)) return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        int i = 0;
        while (i < s.length() && isAsciiUpper(s, i)) i++;
        if (i == s.length()) return s.toLowerCase(Locale.ROOT);
        return s.substring(0, i - 1).toLowerCase(Locale.ROOT) + s.substring(i - 1);
    }

    /** The inner classes named so of the receiver's class and its superclasses. */
    List<Inner> innerClasses(KType receiver, String name) {
        List<Inner> result = new ArrayList<>();
        for (KType.ClassType c : classTypes(receiver)) {
            ClassifierSymbol found = nestedClassifier(c.symbol(), name, new HashSet<>());
            if (!(found instanceof ClassSymbol cls) || !cls.isInner()) continue;
            if (result.stream().anyMatch(i -> i.symbol() == cls)) continue;
            Substitution toOwner = supertypeSubstitution(c.symbol(), cls.outer());
            result.add(new Inner(cls, toOwner.andThen(useSiteSubstitution(c))));
        }
        return result;
    }

    private static ClassifierSymbol nestedClassifier(ClassSymbol cls, String name, Set<ClassSymbol> seen) {
        if (!seen.add(cls)) return null;
        ClassifierSymbol declared = cls.members().classifier(name);
        if (declared != null) return declared;
        for (KType st : cls.supertypes()) {
            if (!(st instanceof KType.ClassType c)) continue;
            ClassifierSymbol found = nestedClassifier(c.symbol(), name, seen);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The class types whose members a value of the type has: the bounds of
     * a type parameter, the components of an intersection, the lower bound
     * of a flexible type and the supertypes of a captured type.
     */
    private List<KType.ClassType> classTypes(KType type) {
        List<KType.ClassType> result = new ArrayList<>();
        classTypes(type, result);
        return result;
    }

    private void classTypes(KType type, List<KType.ClassType> out) {
        switch (type) {
            case KType.ClassType c -> out.add(c);
            case KType.Flexible f -> classTypes(f.lower(), out);
            case KType.DefinitelyNotNull d -> classTypes(d.inner(), out);
            case KType.TypeParamType p -> p.symbol().bounds().forEach(b -> classTypes(b, out));
            case KType.Intersection i -> i.components().forEach(c -> classTypes(c, out));
            case KType.Captured c -> {
                List<KType> supertypes = c.constructor().supertypes();
                if (supertypes == null || supertypes.isEmpty()) {
                    out.add(ts.any());
                } else {
                    supertypes.forEach(s -> classTypes(s, out));
                }
            }
            case KType.TypeVariableType v -> {
            }
            case KType.IntegerLiteral l -> throw new IllegalArgumentException("Integer literal type as receiver: " + l);
        }
    }

    /**
     * From the class's type parameters to the receiver's arguments, captured
     * as FIR's createSubstitutionForScope. For an {@code out} parameter, an
     * {@code out X} argument gives X and a star the approximated captured type.
     */
    private Substitution useSiteSubstitution(KType.ClassType type) {
        List<TypeParamSymbol> params = Overrides.allTypeParams(type.symbol());
        if (params.isEmpty() || type.args().size() != params.size()) return Substitution.EMPTY;
        KType.ClassType captured = ts.captureFromArguments(type, CapturedTypeConstructor.Status.FROM_EXPRESSION);
        if (captured == null) captured = type;
        Map<Object, TypeArg> map = new HashMap<>();
        for (int i = 0; i < params.size(); i++) {
            TypeArg original = type.args().get(i);
            KType arg = captured.args().get(i).type();
            if (params.get(i).variance() == Variance.OUT) {
                if (original.isStar()) {
                    arg = TypeApproximator.approximateToSuperTypeOrSelf(ts, arg,
                            TypeApproximator.Config.FINAL_APPROXIMATION_AFTER_RESOLUTION);
                } else if (original.variance() == Variance.OUT) {
                    arg = original.type();
                }
            }
            map.put(params.get(i), TypeArg.of(arg));
        }
        return new Substitution(map);
    }

    // ==== Members of classes ====

    /** The declared and inherited instance members named so, substituted in terms of the class's type parameters. */
    List<Member> classMembers(ClassSymbol cls, String name, boolean functions) {
        Map<String, List<Member>> byName = (functions ? this.functions : this.properties)
                .computeIfAbsent(cls, k -> new HashMap<>());
        List<Member> cached = byName.get(name);
        if (cached != null) return cached;
        byName.put(name, List.of()); // in case of a cycle in the supertypes
        List<Member> result = new ArrayList<>();
        List<? extends CallableSymbol> declared = functions
                ? cls.members().functions(name) : cls.members().properties(name);
        for (CallableSymbol m : declared) {
            if (!m.isStatic()) result.add(new Member(m, Substitution.EMPTY));
        }
        if (functions) {
            for (FunctionSymbol m : mapped.functions(cls, name)) result.add(new Member(m, Substitution.EMPTY));
        }
        for (KType st : cls.supertypes()) {
            if (!(st instanceof KType.ClassType c)) continue;
            Substitution toSupertype = classSubstitution(c);
            for (Member m : classMembers(c.symbol(), name, functions)) {
                if (m.symbol().visibility() == Visibility.PRIVATE) continue;
                add(result, new Member(m.symbol(), m.substitution().andThen(toSupertype)));
            }
        }
        byName.put(name, result);
        return result;
    }

    /**
     * Adds a member unless one of the others is it or overrides it, removing
     * those it overrides. Of members with the same signature, the first stays.
     */
    private void add(List<Member> members, Member m) {
        for (Member e : members) {
            if (e.symbol() == m.symbol() || overridden(e.symbol()).contains(m.symbol())) return;
        }
        Set<CallableSymbol> overriddenByM = overridden(m.symbol());
        members.removeIf(e -> overriddenByM.contains(e.symbol()));
        for (Member e : members) {
            if (Overrides.sameSignature(e.symbol(), e.substitution(), m.symbol(), m.substitution())) return;
        }
        members.add(m);
    }

    /** All the members a member overrides, directly or not. */
    Set<CallableSymbol> overridden(CallableSymbol member) {
        Set<CallableSymbol> cached = overridden.get(member);
        if (cached != null) return cached;
        Set<CallableSymbol> result = new HashSet<>();
        for (CallableSymbol o : Overrides.overridden(member)) {
            if (result.add(o)) result.addAll(overridden(o));
        }
        overridden.put(member, result);
        return result;
    }

    /** From the supertype's class's type parameters to its arguments; empty for a raw type. */
    private static Substitution classSubstitution(KType.ClassType supertype) {
        List<TypeParamSymbol> params = Overrides.allTypeParams(supertype.symbol());
        return params.size() == supertype.args().size()
                ? Substitution.of(params, supertype.args()) : Substitution.EMPTY;
    }

    /** From the type parameters of a superclass (or outer class) to the arguments it has as one. */
    private Substitution supertypeSubstitution(ClassSymbol cls, ClassSymbol superclass) {
        if (cls == superclass) return Substitution.EMPTY;
        KType.ClassType as = ts.asSupertype(types.defaultType(cls), superclass);
        return as == null ? Substitution.EMPTY : classSubstitution(as);
    }

    // ==== Statics ====

    /**
     * The static members named so that are reached through the class's
     * name: those it declares; for a Java class, also the static methods of
     * its Java superclasses unless hidden, and the static fields of its
     * nearest Java supertypes if it declares none (JavaClassStaticUseSiteScope).
     */
    List<CallableSymbol> statics(ClassSymbol cls, String name, boolean functions) {
        Map<String, List<CallableSymbol>> byName = (functions ? staticFunctions : staticProperties)
                .computeIfAbsent(cls, k -> new HashMap<>());
        List<CallableSymbol> cached = byName.get(name);
        if (cached != null) return cached;
        byName.put(name, List.of());
        List<CallableSymbol> result = new ArrayList<>(declaredStatics(cls, name, functions));
        if (isJava(cls)) {
            if (functions) {
                ClassSymbol superclass = javaSuperclass(cls);
                if (superclass != null) {
                    for (CallableSymbol inherited : statics(superclass, name, true)) {
                        boolean hidden = false;
                        for (int i = 0; i < result.size() && !hidden; i++) {
                            hidden = Overrides.sameSignature(result.get(i), Substitution.EMPTY,
                                    inherited, Substitution.EMPTY);
                        }
                        if (!hidden) result.add(inherited);
                    }
                }
            } else if (result.isEmpty()) {
                Set<CallableSymbol> inherited = new LinkedHashSet<>();
                for (ClassSymbol sup : nearestJavaSupertypes(cls)) inherited.addAll(statics(sup, name, false));
                result.addAll(inherited);
            }
        }
        byName.put(name, result);
        return result;
    }

    private static List<CallableSymbol> declaredStatics(ClassSymbol cls, String name, boolean functions) {
        List<? extends CallableSymbol> declared = functions
                ? cls.members().functions(name) : cls.members().properties(name);
        return declared.stream().filter(CallableSymbol::isStatic).map(m -> (CallableSymbol) m).toList();
    }

    private static boolean isJava(ClassSymbol cls) {
        return cls.origin() == Origin.JAVA_SOURCE || cls.origin() == Origin.JAVA_BINARY;
    }

    /** The nearest Java class among the superclasses, other than Any. */
    private static ClassSymbol javaSuperclass(ClassSymbol cls) {
        for (ClassSymbol c = cls; ; ) {
            ClassSymbol superclass = null;
            for (KType st : c.supertypes()) {
                if (st instanceof KType.ClassType t && t.symbol().kind() == ClassKind.CLASS
                        && !t.symbol().id().fqName().equals("kotlin.Any")) {
                    superclass = t.symbol();
                    break;
                }
            }
            if (superclass == null || isJava(superclass)) return superclass;
            c = superclass;
        }
    }

    /** The Java supertypes reached first on each path through the supertypes. */
    private static List<ClassSymbol> nearestJavaSupertypes(ClassSymbol cls) {
        List<ClassSymbol> result = new ArrayList<>();
        collectJavaSupertypes(cls, result, new HashSet<>());
        return result;
    }

    private static void collectJavaSupertypes(ClassSymbol cls, List<ClassSymbol> out, Set<ClassSymbol> seen) {
        for (KType st : cls.supertypes()) {
            if (!(st instanceof KType.ClassType t) || !seen.add(t.symbol())) continue;
            if (isJava(t.symbol())) {
                out.add(t.symbol());
            } else {
                collectJavaSupertypes(t.symbol(), out, seen);
            }
        }
    }
}
