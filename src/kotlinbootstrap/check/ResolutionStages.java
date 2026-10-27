package kotlinbootstrap.check;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.infer.ConstraintPosition;
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
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.Types;

/**
 * The checks that decide how well a candidate fits its call, as FIR's
 * resolution stages, in the order of FIR's CallKind: each records what is
 * wrong in the candidate, and the first that makes it inapplicable stops
 * the rest.
 */
final class ResolutionStages {
    private final TypeResolver types;
    private final ArgumentChecker arguments;
    private final VisibilityChecker visibility;

    private interface Stage {
        void check(Candidate candidate, Context ctx);
    }

    private final List<Stage> variableAccess = List.of(this::checkHidden, this::checkVisibility,
            this::discriminateSyntheticAndForbiddenProperties, this::initializeEmptyArgumentMap, this::createFreshVariables, this::checkDispatchReceiver,
            this::checkExtensionReceiver, this::checkLowPriority);
    private final List<Stage> function = List.of(this::checkHidden, this::checkVisibility, this::mapArguments,
            this::mapTypeArguments, this::createFreshVariables, this::checkDispatchReceiver,
            this::checkExtensionReceiver, this::checkArguments, this::checkCallModifiers, this::checkLowPriority);

    ResolutionStages(TypeResolver types, ArgumentChecker arguments, VisibilityChecker visibility) {
        this.types = types;
        this.arguments = arguments;
        this.visibility = visibility;
    }

    Applicability process(Candidate candidate, Context ctx) {
        for (Stage stage : candidate.call.kind() == CallInfo.Kind.FUNCTION ? function : variableAccess) {
            stage.check(candidate, ctx);
            if (!candidate.isSuccessful()) break;
        }
        return candidate.applicability();
    }

    // ==== Declarations ====

    private void checkHidden(Candidate c, Context ctx) {
        if (Annotations.isDeprecatedHidden(c.symbol)
                || c.typeAlias != null && Annotations.isDeprecatedHidden(c.typeAlias)) {
            c.addDiagnostic(Applicability.HIDDEN, "Deprecated and hidden");
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
    private void mapArguments(Candidate c, Context ctx) {
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

        int position = 0;
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
            KType expected = c.substitute(parameterType(params.get(p), args.get(i)));
            arguments.resolveArgument(c, args.get(i).value(), expected, i == 0 && receiverAsArgument, false);
        }
        if (c.system.hasContradiction() && !args.isEmpty()) {
            c.addDiagnostic(Applicability.INAPPLICABLE, "Inapplicable: " + c.system.errors());
        }
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
