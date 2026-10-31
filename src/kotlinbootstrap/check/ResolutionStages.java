package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.resolve.TypeResolver;
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
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.Types;

/**
 * The checks that decide how well a candidate fits its call, as FIR's
 * resolution stages, in the order of FIR's CallKind: each records what is
 * wrong in the candidate, and the first that makes it inapplicable stops
 * the rest.
 */
final class ResolutionStages {
    private final TypeResolver types;
    private final TypeSystem ts;
    private final ArgumentChecker arguments;
    private final SamResolver sam;
    private final JvmMappedScope mapped;
    private final VisibilityChecker visibility;
    private final CallableReferenceTypes references;

    private interface Stage {
        void check(Candidate candidate, Context ctx);
    }

    private final List<Stage> variableAccess = List.of(this::checkHidden, this::checkVisibility,
            this::discriminateSyntheticAndForbiddenProperties, this::initializeEmptyArgumentMap, this::createFreshVariables, this::checkDispatchReceiver,
            this::checkExtensionReceiver, this::checkContextArguments, this::checkLowPriority);
    private final List<Stage> function = List.of(this::checkHidden, this::checkVisibility, this::mapArguments,
            this::mapTypeArguments, this::createFreshVariables, this::checkDispatchReceiver,
            this::checkExtensionReceiver, this::checkArguments, this::checkContextArguments, this::checkCallModifiers,
            this::eagerResolveCallableReferences, this::checkLowPriority);
    private final List<Stage> callableReference = List.of(this::checkHidden, this::checkVisibility,
            this::discriminateSyntheticAndForbiddenProperties, this::initializeEmptyArgumentMap,
            this::createFreshVariables, this::checkDispatchReceiver, this::checkExtensionReceiver,
            this::checkContextArguments, this::checkCallableReferenceExpectedType, this::checkLowPriority);

    ResolutionStages(TypeSystem ts, TypeResolver types, ArgumentChecker arguments, SamResolver sam,
                     JvmMappedScope mapped, VisibilityChecker visibility) {
        this.mapped = mapped;
        this.ts = ts;
        this.types = types;
        this.arguments = arguments;
        this.sam = sam;
        this.visibility = visibility;
        this.references = new CallableReferenceTypes(ts, types, this, visibility);
    }

    Applicability process(Candidate candidate, Context ctx) {
        return process(candidate, ctx, true);
    }

    /**
     * Runs the stages not run yet, past errors, as FIR's fullyProcessCandidate:
     * for the candidates of a call without a successful one.
     */
    void fullyProcess(Candidate candidate, Context ctx) {
        process(candidate, ctx, false);
    }

    private Applicability process(Candidate candidate, Context ctx, boolean stopOnFirstError) {
        List<Stage> stages = switch (candidate.call.kind()) {
            case FUNCTION -> function;
            case VARIABLE -> variableAccess;
            case CALLABLE_REFERENCE -> callableReference;
        };
        while (candidate.passedStages < stages.size()) {
            stages.get(candidate.passedStages++).check(candidate, ctx);
            if (stopOnFirstError && !candidate.isSuccessful()) break;
        }
        return candidate.applicability();
    }

    // ==== Declarations ====

    private void checkHidden(Candidate c, Context ctx) {
        if (Annotations.isDeprecatedHidden(c.symbol)
                || c.typeAlias != null && Annotations.isDeprecatedHidden(c.typeAlias)) {
            c.addDiagnostic(Applicability.HIDDEN, "Deprecated and hidden");
        } else if (c.symbol instanceof CallableSymbol s
                && mapped.isHidden(s, c.call.explicitReceiver() instanceof CallInfo.SuperReceiver)) {
            c.addDiagnostic(Applicability.HIDDEN, "Hidden JDK member");
        }
    }

    private void checkVisibility(Candidate c, Context ctx) {
        if (c.symbol instanceof FunctionSymbol f && f.isConstructor() && f.owner() instanceof ClassSymbol cls
                && (cls.isObject() || cls.kind() == ClassKind.ENUM_ENTRY)) {
            c.addDiagnostic(Applicability.HIDDEN, "Constructor of an object");
            return;
        }
        TExpr receiver = c.dispatchReceiver;
        if (c.call.explicitReceiver() instanceof CallInfo.SuperReceiver s
                && c.explicitReceiverKind == Candidate.ExplicitReceiverKind.DISPATCH) {
            receiver = s.thisValue();
        }
        if (!visibility.isVisible(c.symbol, ctx, receiver)) {
            c.addDiagnostic(Applicability.K2_VISIBILITY_ERROR, c.symbol + " is not visible");
        }
        if (c.symbol instanceof PropertySymbol p && p.source() instanceof Decl.Property d && d.backingField() != null) {
            Visibility field = Resolver.visibility(d.backingField().modifiers(), Visibility.PRIVATE);
            c.explicitBackingFieldVisible = visibility.isVisible(p, field, ctx, receiver);
        }
    }

    /** Synthetic Java properties lose to real ones, and an enum entry named {@code entries} to the entries. */
    private void discriminateSyntheticAndForbiddenProperties(Candidate c, Context ctx) {
        if (!(c.symbol instanceof PropertySymbol p)) return;
        if (p.javaGetter() != null) c.addDiagnostic(Applicability.K2_SYNTHETIC_RESOLVED, "Synthetic property");
        if (p.isEnumEntry() && p.name().equals("entries")) {
            c.addDiagnostic(Applicability.RESOLVED_LOW_PRIORITY, "Enum entry named entries");
        }
    }

    private void checkLowPriority(Candidate c, Context ctx) {
        if (Annotations.has(c.symbol, "LowPriorityInOverloadResolution")) {
            c.addDiagnostic(Applicability.RESOLVED_LOW_PRIORITY, "Low priority");
        }
    }

    // ==== Arguments ====

    private void initializeEmptyArgumentMap(Candidate c, Context ctx) {
        c.argumentMapping = new int[0];
    }

    /** As FIR's FirCallArgumentsProcessor. */
    void mapArguments(Candidate c, Context ctx) {
        if (!(c.symbol instanceof FunctionSymbol f)) {
            c.addDiagnostic(Applicability.HIDDEN, c.symbol + " is not a function");
            return;
        }
        List<ValueParamSymbol> params = f.signature().params();
        List<CallInfo.Argument> args = c.call.arguments();
        int[] mapping = new int[args.size()];
        Arrays.fill(mapping, -1);
        // For each parameter, whether it has an argument.
        boolean[] mapped = new boolean[params.size()];
        boolean isIndexedSetOperator = c.call.origin() == TExpr.CallOrigin.OPERATOR && f.name().equals("set")
                && isOperator(f);
        // The first parameters of such an invoke take context arguments.
        c.contextArgumentsOfInvoke = contextParameterCountForInvoke(c, params.size());
        for (int p = 0; p < c.contextArgumentsOfInvoke; p++) mapped[p] = true;

        int position = c.contextArgumentsOfInvoke;
        boolean namedOnly = false;
        boolean inVararg = false;
        int external = -1;
        int lastNonLambda = -1;
        for (int i = 0; i < args.size(); i++) {
            if (!args.get(i).trailingLambda()) lastNonLambda = i;
        }
        for (int i = 0; i < args.size(); i++) {
            CallInfo.Argument arg = args.get(i);
            if (arg.trailingLambda()) {
                if (external < 0) {
                    external = i;
                } else {
                    mappingError(c, "Only one lambda expression is allowed outside the parentheses");
                }
                continue;
            }
            boolean isLast = i == lastNonLambda;
            if (arg.name() == null) {
                if (inVararg && isIndexedSetOperator && isLast) {
                    inVararg = false;
                    position++;
                }
                if (namedOnly) {
                    mappingError(c, "Mixing named and positioned arguments is not allowed");
                    continue;
                }
                int index = position;
                if (isIndexedSetOperator) {
                    index = isLast ? params.size() - 1 : position >= params.size() - 1 ? -1 : position;
                }
                if (index < 0 || index >= params.size()) {
                    mappingError(c, "Too many arguments for " + f);
                    continue;
                }
                mapping[i] = index;
                mapped[index] = true;
                if (params.get(index).isVararg()) {
                    inVararg = true;
                } else {
                    position++;
                    inVararg = false;
                }
            } else {
                if (inVararg) {
                    inVararg = false;
                    position++;
                }
                boolean mixedAllowed = !namedOnly;
                namedOnly = true;
                int index = -1;
                for (int p = 0; p < params.size(); p++) {
                    if (params.get(p).name().equals(arg.name())) index = p;
                }
                if (index < 0) {
                    mappingError(c, "No parameter named " + arg.name());
                    continue;
                }
                if (mapped[index]) {
                    mappingError(c, "An argument is already passed for " + arg.name());
                    continue;
                }
                mapping[i] = index;
                mapped[index] = true;
                if (mixedAllowed && position == index) {
                    namedOnly = false;
                    position++;
                }
            }
        }
        if (external >= 0) {
            int last = params.size() - 1;
            if (last < 0) {
                mappingError(c, "Too many arguments for " + f);
            } else if (params.get(last).isVararg()) {
                mappingError(c, "Passing a lambda outside the parentheses to a vararg parameter");
            } else if (mapped[last]) {
                mappingError(c, "Too many arguments for " + f);
            } else {
                mapping[external] = last;
                mapped[last] = true;
            }
        }
        for (int i = 0; i < args.size(); i++) {
            if (mapping[i] >= 0 && args.get(i).spread() && !params.get(mapping[i]).isVararg()) {
                mappingError(c, "The spread operator is only allowed for a vararg parameter");
            }
        }
        int defaults = 0;
        for (int p = 0; p < params.size(); p++) {
            if (mapped[p]) continue;
            if (params.get(p).hasDefault()) {
                defaults++;
            } else if (!params.get(p).isVararg()) {
                mappingError(c, "No value passed for parameter " + params.get(p).name());
            }
        }
        c.argumentMapping = mapping;
        c.numDefaults = defaults;
    }

    /**
     * For an implicit invoke of a value of a type like {@code context(C) () -> R},
     * the number of context parameters, unless the arguments are for them
     * too; 0 otherwise. As FIR's getExpectedContextParameterTypesForInvoke.
     */
    private static int contextParameterCountForInvoke(Candidate c, int paramCount) {
        if (!c.call.isImplicitInvoke() || c.dispatchReceiver == null
                || !(Types.lowerBound(c.dispatchReceiver.type()) instanceof KType.ClassType t)) {
            return 0;
        }
        int count = t.contextParams();
        return count + c.call.arguments().size() <= paramCount ? count : 0;
    }

    private static void mappingError(Candidate c, String message) {
        c.addDiagnostic(Applicability.INAPPLICABLE_ARGUMENTS_MAPPING_ERROR, message);
    }

    /**
     * The type of a parameter as declared, for an argument: for a vararg
     * parameter, the element type, or the array type for a spread or named argument.
     */
    KType parameterType(ValueParamSymbol p, CallInfo.Argument arg) {
        if (!p.isVararg()) return p.type();
        return arg.spread() || arg.name() != null ? types.varargArrayType(p.type()) : p.type();
    }

    private void checkArguments(Candidate c, Context ctx) {
        List<ValueParamSymbol> params = c.callable().signature().params();
        boolean receiverAsArgument = isInvokeFromExtensionFunctionType(c);
        List<CallInfo.Argument> args = c.call.arguments();
        for (int i = 0; i < args.size(); i++) {
            int p = c.argumentMapping[i];
            if (p < 0) continue;
            TExpr value = args.get(i).value();
            KType declared = parameterType(params.get(p), args.get(i));
            KType withSamConversion = expectedTypeWithSamConversion(c, value, declared);
            KType expected = c.substitute(withSamConversion != null ? withSamConversion : declared);
            boolean isReceiver = i == 0 && c.contextArgumentsOfInvoke == 0 && receiverAsArgument;
            arguments.resolveArgument(c, value, expected, isReceiver, false);
        }
        if (c.system.hasContradiction() && !args.isEmpty()) {
            c.addDiagnostic(Applicability.INAPPLICABLE, "Inapplicable: " + c.system.errors());
        }
    }

    /** The function type of the SAM type of a parameter, if the argument is to be converted to it. */
    private KType expectedTypeWithSamConversion(Candidate c, TExpr argument, KType declared) {
        if (FunctionTypes.isFunctionType(declared)) return null;
        SamResolver.SamConversion conversion = sam.conversion(declared);
        if (conversion == null || !shouldUseSamConversion(c, argument, declared)) return null;
        if (c.samConversions == null) c.samConversions = new IdentityHashMap<>();
        c.samConversions.put(argument, conversion);
        return conversion.functionType();
    }

    private boolean shouldUseSamConversion(Candidate c, TExpr argument, KType declared) {
        if (argument instanceof TExpr.Unresolved u
                && (u.state() instanceof LambdaExpression || u.state() instanceof CallableReferenceExpression)) {
            return true;
        }
        if (isCallWithGenericReturnTypeAndMatchingLambda(argument)) return true;
        if (c.system.isSubtypeConstraintCompatible(argument.type(), c.substitute(declared))) return false;
        return FunctionTypes.isFunctionTypeOrSubtype(ts, argument.type());
    }

    /**
     * Whether the argument is a call whose type is a variable that a lambda
     * among its arguments is expected to be of, as {@code run { {} }}: that
     * is likely a function type, as for a lambda.
     */
    private static boolean isCallWithGenericReturnTypeAndMatchingLambda(TExpr argument) {
        Candidate sub = switch (argument) {
            case TExpr.Unresolved u when u.state() instanceof Candidate s -> s;
            case TExpr.SafeCall s when s.selector() instanceof TExpr.Unresolved u
                    && u.state() instanceof Candidate sc -> sc;
            default -> null;
        };
        if (sub == null || !(Types.lowerBound(argument.type()) instanceof KType.TypeVariableType t)) return false;
        for (PostponedArgument p : sub.postponedArguments) {
            if (p instanceof LambdaWithTypeVariableAtom l
                    && Types.lowerBound(l.expectedType()) instanceof KType.TypeVariableType e
                    && e.variable() == t.variable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a Kotlin candidate converts an argument to a Java SAM type, so
     * that it loses to one that does not, as FIR's shouldHaveLowPriorityDueToSAM.
     */
    boolean shouldHaveLowPriorityDueToSam(Candidate c) {
        if (c.samConversions == null || isJavaApplicable(c) || c.argumentMapping == null) return false;
        List<ValueParamSymbol> params = c.callable().signature().params();
        for (int p : c.argumentMapping) {
            if (p < 0) continue;
            KType type = params.get(p).type();
            if (sam.isSamType(type) && Types.lowerBound(type) instanceof KType.ClassType t && (t.symbol().origin() == Origin.JAVA_SOURCE || t.symbol().origin() == Origin.JAVA_BINARY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJavaApplicable(Candidate c) {
        if (!(c.symbol instanceof FunctionSymbol f)) return false;
        return isJava(f) || !f.isConstructor() && overridesJava(f);
    }

    private static boolean overridesJava(CallableSymbol s) {
        for (CallableSymbol o : Overrides.overridden(s)) {
            if (isJava(o) || overridesJava(o)) return true;
        }
        return false;
    }

    /** As FIR's EagerResolveOfCallableReferences. */
    private void eagerResolveCallableReferences(Candidate c, Context ctx) {
        resolveCallableReferences(c);
    }

    /**
     * Resolves the callable references among a candidate's arguments
     * against the types expected of them, as far as known.
     */
    static void resolveCallableReferences(Candidate c) {
        boolean hasSyntheticOuterCall = c.symbol.name().equals(SyntheticCalls.ACCEPT) && c.call == null;
        for (PostponedArgument p : List.copyOf(c.postponedArguments)) {
            if (!(p instanceof CallableReferenceAtom r) || !r.needsResolution()) continue;
            if (!r.expression.resolver.resolveCallableReference(c, r, hasSyntheticOuterCall)) {
                c.addDiagnostic(Applicability.INAPPLICABLE, r.error());
            }
        }
    }

    private void checkCallableReferenceExpectedType(Candidate c, Context ctx) {
        references.checkExpectedType(c, ctx);
    }

    /** An implicit invoke of a value of an extension function type, whose first argument is the receiver. */
    static boolean isInvokeFromExtensionFunctionType(Candidate c) {
        return c.call.isImplicitInvoke() && c.dispatchReceiver != null
                && Types.lowerBound(c.dispatchReceiver.type()) instanceof KType.ClassType t && t.extensionFunction();
    }

    // ==== Type arguments ====

    private void mapTypeArguments(Candidate c, Context ctx) {
        List<TypeArg> typeArgs = c.call.typeArgs();
        if (!typeArgs.isEmpty() && typeArgs.size() != c.typeParams().size()) {
            c.addDiagnostic(Applicability.INAPPLICABLE, "Wrong number of type arguments: " + typeArgs.size()
                    + " instead of " + c.typeParams().size());
        }
    }

    /** As FIR's CreateFreshTypeVariableSubstitutorStage, with the explicit type arguments. */
    private void createFreshVariables(Candidate c, Context ctx) {
        c.createFreshVariables();
        List<TypeArg> typeArgs = c.call.typeArgs();
        if (typeArgs.size() != c.freshVariables.size()) return;
        for (int i = 0; i < typeArgs.size(); i++) {
            TypeArg arg = typeArgs.get(i);
            if (arg == null || arg.type() == null) continue;
            c.system.addEqualityConstraint(c.freshVariables.get(i).type(), arg.type(),
                    ConstraintPosition.of(ConstraintPosition.Kind.EXPLICIT_TYPE_ARGUMENT));
        }
    }

    // ==== Receivers ====

    private void checkDispatchReceiver(Candidate c, Context ctx) {
        if (c.call.explicitReceiver() instanceof CallInfo.SuperReceiver
                && c.symbol instanceof CallableSymbol s && s.modality() == Modality.ABSTRACT) {
            c.addDiagnostic(Applicability.RESOLVED_LOW_PRIORITY, "Abstract member called through super");
        }
        if (c.dispatchReceiver == null) return;
        if (!Types.isSubtypeOfAny(c.dispatchReceiver.type())) {
            c.addDiagnostic(Applicability.UNSAFE_CALL, "Unsafe call on a nullable receiver of type "
                    + c.dispatchReceiver.type());
        }
    }

    private void checkExtensionReceiver(Candidate c, Context ctx) {
        if (!(c.symbol instanceof CallableSymbol s) || s.signature().receiver() == null) return;
        if (c.extensionReceiver == null) return;
        arguments.resolveArgument(c, c.extensionReceiver, c.substitute(s.signature().receiver()), true, false);
    }

    // ==== Context arguments ====

    /**
     * Chooses an implicit value for each context parameter, from the
     * innermost group with ones of fitting types, as FIR's CheckContextArguments.
     */
    private void checkContextArguments(Candidate c, Context ctx) {
        if (!(c.symbol instanceof CallableSymbol s)) return;
        List<KType> expectedTypes = c.contextArgumentsOfInvoke > 0
                ? s.signature().params().subList(0, c.contextArgumentsOfInvoke).stream()
                        .map(ValueParamSymbol::type).toList()
                : s.signature().contextParams();
        if (expectedTypes.isEmpty()) return;
        List<TExpr> result = new ArrayList<>();
        for (KType type : expectedTypes) {
            KType expected = c.substitute(type);
            List<ImplicitArgument> matching = closestMatchingContextArguments(c, expected);
            switch (matching.size()) {
                case 0 -> c.addDiagnostic(Applicability.INAPPLICABLE, "No context argument for " + expected);
                case 1 -> {
                    result.add(matching.getFirst().value());
                    c.system.addSubtypeConstraint(matching.getFirst().type(), expected,
                            ConstraintPosition.of(ConstraintPosition.Kind.OTHER));
                }
                default -> c.addDiagnostic(Applicability.INAPPLICABLE, "Ambiguous context argument for " + expected
                        + ": " + matching);
            }
        }
        if (result.size() == expectedTypes.size()) c.contextArguments = result;
    }

    /** An implicit value with its type as an argument, as FIR's ImplicitArgumentDescription. */
    private record ImplicitArgument(TExpr value, KType type) {
    }

    /** The values of fitting types in the innermost group with any. */
    private List<ImplicitArgument> closestMatchingContextArguments(Candidate c, KType expected) {
        for (List<TExpr> group : c.implicitValues.get()) {
            List<ImplicitArgument> result = new ArrayList<>();
            for (TExpr value : group) {
                KType type = arguments.implicitArgumentType(value.type(), expected);
                if (c.system.isSubtypeConstraintCompatible(type, expected)) result.add(new ImplicitArgument(value, type));
            }
            if (!result.isEmpty()) return result;
        }
        return List.of();
    }

    // ==== Modifiers ====

    private void checkCallModifiers(Candidate c, Context ctx) {
        if (!(c.symbol instanceof FunctionSymbol f)) return;
        TExpr.CallOrigin origin = c.call.origin();
        if (f.isConstructor()) {
            if (origin.isOperator()) {
                c.addDiagnostic(Applicability.CONVENTION_ERROR, "A constructor cannot be called as an operator");
            }
            return;
        }
        boolean eligibleOperator = isOperator(f) && c.call.name().equals(f.name());
        if (origin == TExpr.CallOrigin.INFIX && !isInfix(f)) {
            c.addDiagnostic(Applicability.CONVENTION_ERROR, f + " is not infix");
        } else if (origin.isOperator() && !eligibleOperator) {
            c.addDiagnostic(Applicability.CONVENTION_ERROR, f + " is not an operator");
        } else if (c.call.isImplicitInvoke() && !eligibleOperator) {
            c.addDiagnostic(Applicability.CONVENTION_ERROR, f + " is not an operator");
        }
    }

    /** Whether a function is an operator: declared so, overriding one, or a Java method named as one. */
    static boolean isOperator(FunctionSymbol f) {
        return hasInherited(f, Modifier.OPERATOR) || isJava(f) && isOperatorName(f.name());
    }

    private static boolean isInfix(FunctionSymbol f) {
        return hasInherited(f, Modifier.INFIX) || isJava(f) && f.signature().params().size() == 1;
    }

    private static boolean hasInherited(CallableSymbol s, Modifier m) {
        if (s.has(m)) return true;
        if (!s.has(Modifier.OVERRIDE) && !isJava(s)) return false;
        for (CallableSymbol o : Overrides.overridden(s)) {
            if (hasInherited(o, m)) return true;
        }
        return false;
    }

    private static boolean isJava(CallableSymbol s) {
        return s.origin() == Origin.JAVA_SOURCE || s.origin() == Origin.JAVA_BINARY;
    }

    private static final Set<String> OPERATOR_NAMES = Set.of("get", "set", "invoke", "contains", "iterator", "next",
            "hasNext", "plus", "minus", "times", "div", "rem", "rangeTo", "rangeUntil", "unaryPlus", "unaryMinus",
            "not", "inc", "dec", "compareTo", "equals", "plusAssign", "minusAssign", "timesAssign", "divAssign",
            "remAssign", "getValue", "setValue", "provideDelegate");

    private static boolean isOperatorName(String name) {
        return OPERATOR_NAMES.contains(name) || name.matches("component[1-9][0-9]*");
    }
}
