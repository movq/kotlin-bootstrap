package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlinbootstrap.resolve.TypeResolver;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.TypeParamSymbol;
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
    /** Members of a class by name, with substitutions in terms of the class's own type parameters. */
    private final Map<ClassSymbol, Map<String, List<Member>>> functions = new HashMap<>();
    private final Map<ClassSymbol, Map<String, List<Member>>> properties = new HashMap<>();
    private final Map<CallableSymbol, Set<CallableSymbol>> overridden = new HashMap<>();
    private final Map<ClassSymbol, Map<String, List<CallableSymbol>>> staticFunctions = new HashMap<>();
    private final Map<ClassSymbol, Map<String, List<CallableSymbol>>> staticProperties = new HashMap<>();

    MemberScopes(TypeSystem ts, TypeResolver types) {
        this.ts = ts;
        this.types = types;
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
    private List<Member> classMembers(ClassSymbol cls, String name, boolean functions) {
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
    private Set<CallableSymbol> overridden(CallableSymbol member) {
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
