package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.infer.Constraint;
import kotlinbootstrap.infer.ConstraintKind;
import kotlinbootstrap.infer.ConstraintPosition;
import kotlinbootstrap.infer.VariableWithConstraints;
import kotlinbootstrap.resolve.TypeResolver;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.ValueParamSymbol;
import kotlinbootstrap.types.FunctionTypes;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;

/**
 * The type of a reference to a candidate, as FIR's
 * CheckCallableReferenceExpectedType: for a function, {@code KFunctionN}
 * with the receiver of an unbound reference as first parameter, or a
 * {@code FunctionN} adapted to the function type expected (see
 * {@link TExpr.ReferenceAdaptation}); for a property, {@code KPropertyN}
 * or {@code KMutablePropertyN}. It must be a subtype of the expected type.
 */
final class CallableReferenceTypes {
    private final TypeSystem ts;
    private final TypeResolver types;
    private final ResolutionStages stages;
    private final VisibilityChecker visibility;

    CallableReferenceTypes(TypeSystem ts, TypeResolver types, ResolutionStages stages, VisibilityChecker visibility) {
        this.ts = ts;
        this.types = types;
        this.stages = stages;
        this.visibility = visibility;
    }

    void checkExpectedType(Candidate c, Context ctx) {
        CallInfo.ReferenceInfo info = c.reference;
        KType expected = info.expectedType();
        // Not through a qualifier, the type on the left is the receiver of an unbound reference.
        KType receiverType = c.call.explicitReceiver() instanceof CallInfo.ValueReceiver v
                && CallableReferenceExpression.isUnboundReceiver(v.value()) ? info.expression().lhsAsType : null;
        boolean expectedReflectionType = expected != null && FunctionTypes.kind(expected) != null
                && FunctionTypes.kind(expected).isReflect();
        KType resulting = switch (c.symbol) {
            case FunctionSymbol f -> functionType(c, f, receiverType, expected, expectedReflectionType);
            case PropertySymbol p -> propertyType(c, p, receiverType, ctx);
            default -> null;
        };
        if (resulting == null) return;
        if (c.adaptation != null && expectedReflectionType) {
            c.addDiagnostic(Applicability.RESOLVED_WITH_ERROR, "An adapted reference cannot be used with reflection");
        }
        c.referenceType = resulting;
        if (expected != null) {
            c.system.addSubtypeConstraint(resulting, expected, ConstraintPosition.of(ConstraintPosition.Kind.ARGUMENT));
        }
        if (c.system.hasContradiction()) {
            c.addDiagnostic(Applicability.INAPPLICABLE, "Reference of type " + resulting + " is not " + expected);
        }
    }

    /**
     * As FIR's buildResultingTypeAndAdaptation for a function: reflective
     * unless adapted, or the expected type is reflective.
     */
    private KType functionType(Candidate c, FunctionSymbol f, KType receiverType, KType expected,
                               boolean forceReflectionType) {
        Adaptation adaptation = adaptation(c, f, expected == null ? null : Types.lowerBound(expected),
                receiverType != null ? 1 : 0);
        KType extensionReceiver = f.signature().receiver();
        List<KType> parameters = new ArrayList<>();
        if (extensionReceiver == null && receiverType != null) parameters.add(receiverType);
        KType returnTypeWithoutCoercion = f.returnType();
        KType returnType;
        if (adaptation == null) {
            for (ValueParamSymbol p : f.signature().params()) {
                parameters.add(p.isVararg() ? types.varargArrayType(p.type()) : p.type());
            }
            returnType = returnTypeWithoutCoercion;
        } else {
            parameters.addAll(adaptation.argumentTypes());
            // Not a reference passed to a call, the result is not coerced to Unit if of a type parameter's type.
            boolean coerce = adaptation.result().coercionToUnit() && !(c.reference.hasSyntheticOuterCall()
                    && Types.lowerBound(returnTypeWithoutCoercion) instanceof KType.TypeParamType);
            returnType = coerce ? types.unit() : returnTypeWithoutCoercion;
            c.adaptation = adaptation.result();
            c.numDefaults = adaptation.defaults();
        }
        FunctionTypes.Kind kind = adaptation != null && adaptation.result().suspendConversion()
                ? FunctionTypes.Kind.SUSPEND_FUNCTION
                : f.has(Modifier.SUSPEND) ? FunctionTypes.Kind.SUSPEND_FUNCTION : FunctionTypes.Kind.FUNCTION;
        kind = adaptation == null || forceReflectionType ? kind.reflect() : kind.nonReflect();
        return c.substitute(FunctionTypes.create(ts.table(), kind, List.of(),
                extensionReceiver != null ? receiverType : null, parameters, returnType));
    }

    /** As FIR's buildResultingTypeAndAdaptation for a property. */
    private KType propertyType(Candidate c, PropertySymbol p, KType receiverType, Context ctx) {
        KType returnType = c.substitute(p.returnType());
        boolean mutable = canBeMutableReference(c, p, ctx);
        // Captured types in a mutable property's type are unsound.
        if (mutable && Types.contains(returnType, t -> t instanceof KType.Captured)) {
            KType approximated = TypeApproximator.approximateToSuperType(ts, returnType,
                    TypeApproximator.Config.INTERNAL_TYPES);
            if (approximated != null) returnType = approximated;
        }
        List<TypeArg> args = new ArrayList<>();
        if (receiverType != null) args.add(TypeArg.of(receiverType));
        args.add(TypeArg.of(returnType));
        String name = "kotlin.reflect.K" + (mutable ? "Mutable" : "") + "Property" + (args.size() - 1);
        return types.classType(name, args.toArray(TypeArg[]::new));
    }

    private boolean canBeMutableReference(Candidate c, PropertySymbol p, Context ctx) {
        if (!p.isVar()) return false;
        if (p.isJavaField()) return true;
        return visibility.isVisible(p, p.setterVisibility(), ctx, c.dispatchReceiver);
    }

    // ==== Adaptation ====

    /**
     * @param argumentTypes for each parameter of the expected function type (after an unbound
     *                      receiver), the type of the function's parameter it is passed to
     */
    private record Adaptation(List<KType> argumentTypes, int defaults, TExpr.ReferenceAdaptation result) {
    }

    private enum VarargMappingState { UNMAPPED, MAPPED_WITH_PLAIN_ARGS, MAPPED_WITH_ARRAY }

    /**
     * How the function is passed the parameters of the expected function
     * type, as FIR's getCallableReferenceAdaptation: as if called with them
     * as arguments. Null if no adaptation is needed, or none fits.
     */
    private Adaptation adaptation(Candidate c, FunctionSymbol f, KType expected, int unboundReceiverCount) {
        if (expected == null) return null;
        // Defaults and varargs cannot be mapped to the absent parameters of KCallable.
        if (expected instanceof KType.ClassType e && e.symbol().id().fqName().equals("kotlin.reflect.KCallable")) {
            return null;
        }
        KType.ClassType expectedFunction = CallableReferenceAtom.expectedFunctionType(expected);
        if (expectedFunction == null) return null;
        List<KType> inputTypes = FunctionTypes.valueParameterTypesIncludingReceiver(ts, expectedFunction);
        KType returnExpectedType = FunctionTypes.returnType(ts, expectedFunction);
        int expectedArgumentCount = inputTypes.size() - unboundReceiverCount;
        if (expectedArgumentCount < 0) return null;

        List<ValueParamSymbol> params = f.signature().params();
        Candidate fake = new Candidate(f, c.pos, c.system, new CallInfo(CallInfo.Kind.FUNCTION, f.name(), null,
                fakeArguments(f, expectedArgumentCount, inputTypes, unboundReceiverCount), List.of(),
                TExpr.CallOrigin.PLAIN, false, c.pos), List.of());
        stages.mapArguments(fake, null);
        if (!fake.applicability().isSuccess()) return null;

        VarargMappingState varargState = VarargMappingState.UNMAPPED;
        KType[] argumentTypes = new KType[expectedArgumentCount];
        for (int i = 0; i < expectedArgumentCount; i++) {
            ValueParamSymbol p = params.get(fake.argumentMapping[i]);
            if (!p.isVararg()) {
                argumentTypes[i] = p.type();
                continue;
            }
            KType expectedParameterType = inputTypes.get(i + unboundReceiverCount);
            KType elementType = p.type();
            KType arrayType = types.varargArrayType(elementType);
            switch (varargState) {
                case UNMAPPED -> {
                    if (isArrayOrPrimitiveArray(expectedParameterType)
                            || isApplicableTypeVariableForMappingAsArray(c, expectedParameterType, arrayType)) {
                        argumentTypes[i] = arrayType;
                        varargState = VarargMappingState.MAPPED_WITH_ARRAY;
                    } else {
                        argumentTypes[i] = elementType;
                        varargState = VarargMappingState.MAPPED_WITH_PLAIN_ARGS;
                    }
                }
                case MAPPED_WITH_PLAIN_ARGS -> argumentTypes[i] = elementType;
                // Another argument for an array: a mismatch.
                case MAPPED_WITH_ARRAY -> {
                    return null;
                }
            }
        }
        // A vararg given elements or nothing; an array passed for it is no adaptation.
        boolean isThereVararg = varargState != VarargMappingState.MAPPED_WITH_ARRAY
                && params.stream().anyMatch(ValueParamSymbol::isVararg);

        boolean coercionToUnit = BodyChecker.isUnitOrFlexibleUnit(returnExpectedType)
                && !BodyChecker.isUnitOrFlexibleUnit(f.returnType());
        FunctionTypes.Kind expectedKind = FunctionTypes.kind(expectedFunction);
        boolean suspendConversion = expectedKind.isSuspend() && !f.has(Modifier.SUSPEND);
        if (fake.numDefaults == 0 && !isThereVararg && !coercionToUnit && !suspendConversion) {
            // The identity conversion would make the type FunctionN instead of KFunctionN.
            return null;
        }
        List<Integer> argumentParams = new ArrayList<>();
        for (int i = 0; i < expectedArgumentCount; i++) argumentParams.add(fake.argumentMapping[i]);
        return new Adaptation(List.of(argumentTypes), fake.numDefaults, new TExpr.ReferenceAdaptation(argumentParams,
                varargState == VarargMappingState.MAPPED_WITH_ARRAY, coercionToUnit, suspendConversion));
    }

    /**
     * The arguments the function is called with for the parameters of the
     * expected function type, as FIR's createFakeArgumentsForReference:
     * positional, but by name after a vararg for those with defaults.
     */
    private static List<CallInfo.Argument> fakeArguments(FunctionSymbol f, int count, List<KType> inputTypes,
                                                         int unboundReceiverCount) {
        List<ValueParamSymbol> params = f.signature().params();
        List<CallInfo.Argument> result = new ArrayList<>();
        boolean afterVararg = false;
        boolean vararg = false;
        KType varargComponentType = null;
        for (int i = 0; i < count; i++) {
            KType inputType = i + unboundReceiverCount < inputTypes.size() ? inputTypes.get(i + unboundReceiverCount) : null;
            if (vararg && !Objects.equals(varargComponentType, inputType)) afterVararg = true;
            ValueParamSymbol p = i < params.size() ? params.get(i) : null;
            String name = afterVararg && p != null && p.hasDefault() ? p.name() : null;
            if (p != null && p.isVararg()) {
                varargComponentType = inputType;
                vararg = true;
            }
            result.add(new CallInfo.Argument(new TExpr.Const(0, inputType, null), name, false, false));
        }
        return result;
    }

    private static final Set<String> ARRAYS = Set.of("kotlin.Array", "kotlin.BooleanArray", "kotlin.CharArray",
            "kotlin.ByteArray", "kotlin.ShortArray", "kotlin.IntArray", "kotlin.LongArray", "kotlin.FloatArray",
            "kotlin.DoubleArray");

    private static boolean isArrayOrPrimitiveArray(KType t) {
        return Types.lowerBound(t) instanceof KType.ClassType c && ARRAYS.contains(c.symbol().id().fqName());
    }

    /**
     * For {@code fun <T> map(f: (T) -> Unit)} and {@code fun of(vararg a: String)},
     * in {@code map(::of)}: whether the variable for T can be the array.
     */
    private boolean isApplicableTypeVariableForMappingAsArray(Candidate c, KType expectedParameterType,
                                                              KType arrayType) {
        if (!(expectedParameterType instanceof KType.TypeVariableType tv)) return false;
        TypeVariable variable = tv.variable();
        VariableWithConstraints withConstraints = c.system.notFixedTypeVariables().get(variable);
        if (withConstraints == null) return false;
        KType array = c.substitute(arrayType);
        for (Constraint constraint : withConstraints.constraints()) {
            boolean impliesLower = constraint.kind() == ConstraintKind.LOWER || constraint.kind() == ConstraintKind.EQUALITY;
            if (impliesLower && !c.system.isSubtypeConstraintCompatible(constraint.type(), array)) return false;
        }
        return true;
    }
}
