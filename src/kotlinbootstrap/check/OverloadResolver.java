package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Overrides;
import kotlinbootstrap.symbols.PackageSymbol;
import kotlinbootstrap.symbols.TypeAliasSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Substitution;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;

/**
 * Chooses the most specific of the applicable candidates of a call, as
 * FIR's ConeOverloadConflictResolver without eager lambda analysis (not
 * enabled in Kotlin 2.4): a candidate is more specific than another if its
 * parameters' types, for the arguments given, are subtypes of the other's.
 */
final class OverloadResolver {
    private final TypeSystem ts;
    private final MemberScopes scopes;
    private final ResolutionStages stages;

    OverloadResolver(TypeSystem ts, MemberScopes scopes, ResolutionStages stages) {
        this.ts = ts;
        this.scopes = scopes;
        this.stages = stages;
    }

    /** The most specific candidates; more than one if the call is ambiguous. */
    Set<Candidate> chooseMaximallySpecific(Set<Candidate> candidates) {
        // No type arguments can be given for a reference.
        Candidate first = candidates.iterator().next();
        return chooseMaximallySpecific(candidates, first.call.kind() != CallInfo.Kind.CALLABLE_REFERENCE);
    }

    private Set<Candidate> chooseMaximallySpecific(Set<Candidate> candidates, boolean discriminateGenerics) {
        if (candidates.size() == 1) return candidates;
        candidates = filterOutEquivalentCalls(candidates);
        if (candidates.size() == 1) return candidates;
        Candidate first = candidates.iterator().next();
        Set<Candidate> fixed = first.isSuccessful() && first.invokeReceiver != null
                ? withMostSpecificInvokeReceiver(candidates) : candidates;
        return chooseMaximallySpecificCandidates(filterOverrides(fixed),
                new Flags(true, true, discriminateGenerics, true));
    }

    /** As FIR's DiscriminationFlags, of those that apply. */
    private record Flags(boolean lowPrioritySams, boolean adaptationsInPostponedAtoms, boolean generics,
                         boolean sams) {
    }

    /** As FIR's chooseMaximallySpecificCandidates with discrimination flags. */
    private Set<Candidate> chooseMaximallySpecificCandidates(Set<Candidate> candidates, Flags flags) {
        if (flags.lowPrioritySams()) {
            Set<Candidate> r = filterByDiscriminationFlag(candidates, c -> !stages.shouldHaveLowPriorityDueToSam(c),
                    new Flags(false, flags.adaptationsInPostponedAtoms(), flags.generics(), flags.sams()));
            if (r != null) return r;
        }
        if (flags.adaptationsInPostponedAtoms()) {
            Set<Candidate> r = filterByDiscriminationFlag(candidates, c -> !hasPostponedAtomWithAdaptation(c),
                    new Flags(flags.lowPrioritySams(), false, flags.generics(), flags.sams()));
            if (r != null) return r;
        }
        Candidate best = findMaximallySpecificCall(candidates, false, false);
        if (best == null && flags.generics()) best = findMaximallySpecificCall(candidates, true, false);
        if (best != null) return Set.of(best);
        if (flags.sams()) {
            Set<Candidate> r = filterByDiscriminationFlag(candidates, c -> !usesSamConversionOrSamConstructor(c),
                    new Flags(flags.lowPrioritySams(), flags.adaptationsInPostponedAtoms(), flags.generics(), false));
            if (r != null) return r;
        }
        if (candidates.stream().anyMatch(OverloadResolver::usesSamConversionOrSamConstructor)) {
            best = findMaximallySpecificCall(candidates, false, true);
            if (best != null) return Set.of(best);
        }
        return candidates;
    }

    /** The candidates that pass, if they are some but not all of them, narrowed down further. */
    private Set<Candidate> filterByDiscriminationFlag(Set<Candidate> candidates, Predicate<Candidate> filter,
                                                      Flags newFlags) {
        Set<Candidate> filtered = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            if (filter.test(c)) filtered.add(c);
        }
        if (filtered.size() == 1) return filtered;
        if (filtered.isEmpty() || filtered.size() == candidates.size()) return null;
        return chooseMaximallySpecificCandidates(filtered, newFlags);
    }

    private static boolean usesSamConversionOrSamConstructor(Candidate c) {
        return c.samConversions != null || c.symbol instanceof FunctionSymbol f && SamResolver.isSamConstructor(f);
    }

    private static boolean hasPostponedAtomWithAdaptation(Candidate c) {
        for (PostponedArgument p : c.postponedArguments) {
            if (p instanceof CallableReferenceAtom r && r.resolved() != null && r.resolved().adaptation != null) {
                return true;
            }
        }
        return false;
    }

    private Set<Candidate> withMostSpecificInvokeReceiver(Set<Candidate> candidates) {
        Set<Candidate> receivers = new LinkedHashSet<>();
        for (Candidate c : candidates) receivers.add(c.invokeReceiver);
        Set<Candidate> best = chooseMaximallySpecific(receivers, false);
        if (best.size() != 1) return candidates;
        Candidate receiver = best.iterator().next();
        Set<Candidate> result = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            if (c.invokeReceiver == receiver) result.add(c);
        }
        return result;
    }

    // ==== Equivalent calls ====

    /**
     * Keeps one of equivalent top-level candidates, as FIR's
     * ConeEquivalentCallConflictResolver: the same declaration in several
     * modules. Within a module they would be redeclarations, so any are
     * taken to be from different modules. An actual is kept over an expect.
     */
    private static Set<Candidate> filterOutEquivalentCalls(Set<Candidate> candidates) {
        Set<Candidate> result = new LinkedHashSet<>();
        outer:
        for (Candidate me : candidates) {
            if (me.symbol instanceof CallableSymbol s && s.owner() instanceof PackageSymbol) {
                for (Iterator<Candidate> it = result.iterator(); it.hasNext(); ) {
                    Candidate other = it.next();
                    if (!areEquivalentTopLevelCallables(me, other)) continue;
                    CallableSymbol o = (CallableSymbol) other.symbol;
                    if (o.has(Modifier.EXPECT) && !s.has(Modifier.EXPECT)) {
                        it.remove();
                    } else {
                        continue outer;
                    }
                }
            }
            result.add(me);
        }
        return result;
    }

    private static boolean areEquivalentTopLevelCallables(Candidate a, Candidate b) {
        if (!(a.symbol instanceof CallableSymbol s) || !(b.symbol instanceof CallableSymbol o) || s == o) return false;
        if (!(o.owner() instanceof PackageSymbol p) || !p.equals(s.owner()) || !s.name().equals(o.name())) return false;
        if (!Arrays.equals(a.argumentMapping, b.argumentMapping)) return false;
        return Overrides.sameSignature(s, Substitution.EMPTY, o, Substitution.EMPTY)
                && Overrides.sameSignature(o, Substitution.EMPTY, s, Substitution.EMPTY);
    }

    // ==== Overrides ====

    /** Drops the candidates that others override, as K1's OverridingUtil.filterOverrides. */
    private Set<Candidate> filterOverrides(Set<Candidate> candidates) {
        if (candidates.size() <= 1) return candidates;
        Set<Candidate> result = new LinkedHashSet<>();
        outer:
        for (Candidate me : candidates) {
            for (Iterator<Candidate> it = result.iterator(); it.hasNext(); ) {
                Candidate other = it.next();
                if (overrides(me, other)) {
                    it.remove();
                } else if (overrides(other, me)) {
                    continue outer;
                }
            }
            result.add(me);
        }
        return result;
    }

    private boolean overrides(Candidate c, Candidate other) {
        if (!(c.symbol instanceof CallableSymbol s) || !(other.symbol instanceof CallableSymbol o)) return false;
        return s == o || scopes.overridden(s).contains(o);
    }

    // ==== Specificity ====

    /**
     * A candidate's signature for the call, as FIR's FlatSignature: the
     * types of its extension receiver and of the parameters of the
     * arguments, in the order of the arguments.
     */
    private record FlatSignature(Candidate origin, List<TypeParamSymbol> typeParams, List<KType> types,
                                 boolean hasExtensionReceiver, boolean hasVarargs, int numDefaults) {
    }

    /** @param useOriginalSamTypes whether the parameters of SAM-converted arguments are compared as SAM types */
    private Candidate findMaximallySpecificCall(Set<Candidate> candidates, boolean discriminateGenerics,
                                               boolean useOriginalSamTypes) {
        if (candidates.size() <= 1) return candidates.isEmpty() ? null : candidates.iterator().next();
        List<FlatSignature> signatures = new ArrayList<>();
        for (Candidate c : candidates) signatures.add(signature(c, useOriginalSamTypes));
        List<FlatSignature> best = new ArrayList<>();
        for (FlatSignature s : signatures) {
            boolean all = true;
            for (FlatSignature other : signatures) {
                if (s != other && !isEquallyOrMoreSpecific(s, other, discriminateGenerics)) {
                    all = false;
                    break;
                }
            }
            if (all) best.add(s);
        }
        FlatSignature result = exactMaxWith(best);
        return result == null ? null : result.origin();
    }

    /** The one with no varargs and fewest defaults, if one is better in that than all others. */
    private static FlatSignature exactMaxWith(List<FlatSignature> signatures) {
        FlatSignature result = null;
        for (FlatSignature s : signatures) {
            if (result == null || isEquallyOrMoreSpecificShape(s, result)) result = s;
        }
        if (result == null) return null;
        for (FlatSignature s : signatures) {
            if (s != result && isEquallyOrMoreSpecificShape(s, result)) return null;
        }
        return result;
    }

    private static boolean isEquallyOrMoreSpecificShape(FlatSignature a, FlatSignature b) {
        if (a.hasVarargs() != b.hasVarargs()) return !a.hasVarargs();
        return a.numDefaults() <= b.numDefaults();
    }

    private FlatSignature signature(Candidate c, boolean useOriginalSamTypes) {
        if (c.referenceType != null) return referenceSignature(c);
        switch (c.symbol) {
            case FunctionSymbol f -> {
                List<KType> types = new ArrayList<>();
                if (f.signature().receiver() != null) types.add(c.memberSubstitution.apply(f.signature().receiver()));
                List<ValueParamSymbol> params = f.signature().params();
                List<CallInfo.Argument> args = c.call.arguments();
                for (int i = 0; i < args.size(); i++) {
                    int p = c.argumentMapping == null ? -1 : c.argumentMapping[i];
                    if (p < 0) continue;
                    SamResolver.SamConversion sam = c.samConversions == null || useOriginalSamTypes ? null
                            : c.samConversions.get(args.get(i).value());
                    KType type = sam != null ? sam.functionType() : stages.parameterType(params.get(p), args.get(i));
                    types.add(c.memberSubstitution.apply(type));
                }
                boolean hasVarargs = params.stream().anyMatch(ValueParamSymbol::isVararg);
                return new FlatSignature(c, c.typeParams(), types, f.signature().receiver() != null, hasVarargs,
                        c.numDefaults);
            }
            case CallableSymbol p -> {
                KType receiver = p.signature().receiver();
                return new FlatSignature(c, c.typeParams(),
                        receiver == null ? List.of() : List.of(c.memberSubstitution.apply(receiver)),
                        receiver != null, false, 0);
            }
            case ClassSymbol cls -> {
                return new FlatSignature(c, cls.typeParams(), List.of(), false, false, 0);
            }
            case TypeAliasSymbol a -> {
                return new FlatSignature(c, a.typeParams(), List.of(), false, false, 0);
            }
            default -> {
                return new FlatSignature(c, List.of(), List.of(), false, false, 0);
            }
        }
    }

    /**
     * For the callable of a reference: the types of its extension receiver
     * and of the parameters of the reference's type, with its type
     * parameters, as FIR's computeSignatureTypes.
     */
    private FlatSignature referenceSignature(Candidate c) {
        CallableSymbol callable = c.callable();
        List<KType> types = new ArrayList<>();
        KType receiver = callable.signature().receiver();
        if (receiver != null) types.add(receiver);
        Map<Object, TypeArg> toTypeParams = new HashMap<>();
        for (TypeVariable v : c.freshVariables) toTypeParams.put(v, TypeArg.of(new KType.TypeParamType(v.typeParam(), false)));
        Substitution substitution = new Substitution(toTypeParams);
        List<TypeArg> args = ((KType.ClassType) Types.lowerBound(c.referenceType)).args();
        for (TypeArg a : args.subList(0, args.size() - 1)) types.add(substitution.apply(a.type()));
        boolean hasVarargs = callable.signature().params().stream().anyMatch(ValueParamSymbol::isVararg);
        return new FlatSignature(c, c.typeParams(), types, receiver != null, hasVarargs, c.numDefaults);
    }

    /** Whether {@code specific} is not less specific than {@code general}, as FIR's compareCallsByUsedArguments. */
    private boolean isEquallyOrMoreSpecific(FlatSignature specific, FlatSignature general,
                                            boolean discriminateGenerics) {
        if (discriminateGenerics) {
            boolean generic1 = !specific.typeParams().isEmpty();
            boolean generic2 = !general.typeParams().isEmpty();
            if (!generic1 && generic2) return true;
            if (generic1) return false;
        }
        if (specific.hasExtensionReceiver() != general.hasExtensionReceiver()) return false;
        if (specific.types().size() != general.types().size()) return false;

        ConstraintSystem system = new ConstraintSystem(ts);
        Map<Object, TypeArg> map = new HashMap<>();
        List<TypeVariable> variables = new ArrayList<>();
        for (TypeParamSymbol tp : general.typeParams()) {
            TypeVariable v = new TypeVariable(tp.name(), tp);
            system.registerVariable(v);
            variables.add(v);
            map.put(tp, TypeArg.of(v.type()));
        }
        Substitution substitution = new Substitution(map);
        for (TypeVariable v : variables) {
            for (KType bound : v.typeParam().bounds()) {
                system.addSubtypeConstraint(v.type(), substitution.apply(bound), position());
            }
        }
        for (int i = 0; i < specific.types().size(); i++) {
            if (isLessSpecific(system, general.typeParams(), substitution, specific.types().get(i),
                    general.types().get(i))) {
                return false;
            }
        }
        return true;
    }

    /** As FIR's FlatSignatureComparisonState.isLessSpecific. */
    private boolean isLessSpecific(ConstraintSystem system, List<TypeParamSymbol> typeParams,
                                   Substitution substitution, KType specific, KType general) {
        if (isDefinitelyLessSpecific(specific, general)) return true;
        if (typeParams.isEmpty() || !Types.contains(general,
                t -> t instanceof KType.TypeParamType p && typeParams.contains(p.symbol()))) {
            return !ts.isSubtype(specific, general) && !isNonSubtypeEquallyOrMoreSpecific(specific, general);
        }
        KType captured = ts.captureFromExpression(specific);
        system.addSubtypeConstraint(captured != null ? captured : specific, substitution.apply(general), position());
        return system.hasContradiction();
    }

    private static ConstraintPosition position() {
        return ConstraintPosition.of(ConstraintPosition.Kind.OTHER);
    }

    /**
     * As JvmTypeSpecificityComparator: a Java primitive wrapper is less
     * specific than a primitive, so that of {@code foo(int)} and
     * {@code foo(Integer)} the first is chosen.
     */
    private static boolean isDefinitelyLessSpecific(KType specific, KType general) {
        if (!(general instanceof KType.ClassType g) || !isPrimitive(g)) return false;
        if (specific instanceof KType.Flexible f && f.lower() instanceof KType.ClassType l && isPrimitive(l)) {
            return true;
        }
        // Without enhanced nullability, only nullable types are primitive wrappers of the same class.
        return Types.lowerBound(specific) instanceof KType.ClassType s && s.symbol() == g.symbol()
                && !(specific instanceof KType.ClassType c && isPrimitive(c));
    }

    private static boolean isPrimitive(KType.ClassType t) {
        return !t.nullable() && PRIMITIVES.contains(t.symbol().id().fqName());
    }

    private static final Set<String> PRIMITIVES = Set.of("kotlin.Boolean", "kotlin.Char", "kotlin.Byte",
            "kotlin.Short", "kotlin.Int", "kotlin.Long", "kotlin.Float", "kotlin.Double");

    private static final Set<String> SIGNED = Set.of("kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long");
    private static final Set<String> UNSIGNED = Set.of("kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong");

    /** As FIR's SpecificityComparisonWithNumerics: Int is more specific than Long, and so on. */
    private static boolean isNonSubtypeEquallyOrMoreSpecific(KType specific, KType general) {
        if (!(Types.lowerBound(specific) instanceof KType.ClassType s)
                || !(Types.upperBound(general) instanceof KType.ClassType g)) {
            return false;
        }
        String a = s.symbol().id().fqName();
        String b = g.symbol().id().fqName();
        if (SIGNED.contains(a) && UNSIGNED.contains(b)) return true;
        return switch (a) {
            case "kotlin.Int" -> b.equals("kotlin.Long") || b.equals("kotlin.Short") || b.equals("kotlin.Byte");
            case "kotlin.Short" -> b.equals("kotlin.Byte");
            case "kotlin.UInt" -> b.equals("kotlin.ULong") || b.equals("kotlin.UShort") || b.equals("kotlin.UByte");
            case "kotlin.UShort" -> b.equals("kotlin.UByte");
            case "kotlin.Double" -> b.equals("kotlin.Float");
            default -> false;
        };
    }
}
