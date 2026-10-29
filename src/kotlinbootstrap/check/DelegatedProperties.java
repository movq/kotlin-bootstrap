package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.infer.CompletionMode;
import kotlinbootstrap.infer.ConstraintSystem;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.TypeArg;
import kotlinbootstrap.types.TypeSystem;

/**
 * Checks a delegated property, as FIR's transformPropertyAccessorsWithDelegate
 * with its FirDelegatedPropertyInferenceSession: the delegate is checked
 * without completing it; then its {@code provideDelegate} (if there is
 * one that applies), {@code getValue} and, for a {@code var} with a
 * declared type, {@code setValue} calls are each completed partially in a
 * system they share; then all are completed together. Without a declared
 * type, the property has the type {@code getValue} returns, and the
 * {@code setValue} call is resolved after.
 */
final class DelegatedProperties {
    private final BodyChecker checker;
    private final TypeSystem ts;

    DelegatedProperties(BodyChecker checker, TypeSystem ts) {
        this.checker = checker;
        this.ts = ts;
    }

    /**
     * @param declaredType  the property's type, if declared; nullable
     * @param cls           the class the property is a member of; nullable
     * @param dispatchType  the type of the class's instance; nullable
     * @param delegateCtx   where the delegate is checked, as an initializer
     * @param accessorCtx   where the accessors' calls are resolved
     * @param propertyType  given the type inferred for the property, approximated as its declaration's
     */
    CheckedDelegate check(PropertySymbol p, Expr delegateExpr, KType declaredType, ClassSymbol cls,
                          KType dispatchType, Context delegateCtx, Context accessorCtx,
                          UnaryOperator<KType> propertyType) {
        int pos = delegateExpr.pos();
        KType extensionType = p.signature().receiver();
        TExpr dispatchThis = cls == null ? null : new TExpr.This(pos, dispatchType, cls, true);
        TExpr thisRef = extensionType != null ? new TExpr.This(pos, extensionType, p, true) : thisRefForDispatch(pos, dispatchThis);
        KType.ClassType referenceType = propertyReferenceType(p, cls != null);

        TExpr delegate = checker.expr(delegateExpr, delegateCtx, Mode.DELEGATE);
        Candidate delegateCandidate = ArgumentChecker.subCandidate(delegate);
        ConstraintSystem parent = delegateCandidate != null ? delegateCandidate.system : new ConstraintSystem(ts);
        ConstraintSystem current = new ConstraintSystem(ts);
        current.addOtherSystem(parent);
        List<Candidate> calls = new ArrayList<>();
        if (delegateCandidate != null) calls.add(delegateCandidate);

        // provideDelegate is used if it applies; its thisRef is the instance even for an extension.
        CallInfo provideInfo = operator(pos, "provideDelegate", delegate,
                List.of(thisRefForDispatch(pos, dispatchThis), propertyReference(pos, p, referenceType)));
        CallResolver.Result provide = checker.calls().resolve(provideInfo, delegateCtx, current);
        boolean provides = provide.success() && provide.candidates().size() == 1;
        if (provides) delegate = partially(provide.candidates().getFirst(), Mode.RECEIVER, current, calls);
        KType delegateType = current.buildCurrentSubstitutor().apply(delegate.type());

        TExpr getValue = partially(resolve(pos, "getValue", delegateType, p, dispatchThis,
                        List.of(thisRef, propertyReference(pos, p, referenceType)), accessorCtx, current),
                declaredType != null ? Mode.expecting(declaredType) : Mode.DEPENDENT, current, calls);
        LocalVariable value = p.isVar()
                ? new LocalVariable("value", declaredType, LocalVariable.Kind.PARAMETER, false, null) : null;
        TExpr setValue = null;
        if (value != null && declaredType != null) {
            setValue = partially(resolve(pos, "setValue", delegateType, p, dispatchThis,
                    List.of(thisRef, propertyReference(pos, p, referenceType), new TExpr.LocalGet(pos, declaredType, value)),
                    accessorCtx, current), Mode.DEPENDENT, current, calls);
        }

        // Everything together.
        parent.replaceContentWith(current);
        checker.completer().runCompletion(parent, calls, CompletionMode.FULL, checker.unit());
        parent.prepareForReading();
        for (Candidate c : calls) {
            if (parent.hasContradiction() || !c.applicability().isUsable()) {
                throw checker.error(c.pos, "Inapplicable " + c.symbol.name() + ": "
                        + (c.diagnostics.isEmpty() ? parent.errors() : c.diagnostics));
            }
        }
        CompletionWriter w = new CompletionWriter(ts, parent.buildCurrentSubstitutor());
        delegate = w.write(delegate);
        TExpr getterValue = w.write(getValue);
        KType type = declaredType != null ? declaredType : propertyType.apply(getterValue.type());
        KType finalReferenceType = finalPropertyReferenceType(referenceType, dispatchType, extensionType, type);
        if (provides) delegate = withPropertyReferenceType(delegate, finalReferenceType);

        CheckedFunction getter = new CheckedFunction(p, null, extensionType, List.of(), List.of(), type);
        getter.setBody(new TExpr.Return(pos, ts.nothing(), getter,
                withPropertyReferenceType(getterValue, finalReferenceType)));
        CheckedFunction setter = null;
        if (value != null) {
            TExpr setterValue;
            if (setValue != null) {
                setterValue = w.write(setValue);
            } else {
                // Resolved once the type is known, as FIR's resolveSetter after completing the session.
                value.setType(type);
                setterValue = checker.completer().completeCall(resolve(pos, "setValue", delegate.type(), p, dispatchThis,
                        List.of(thisRef, propertyReference(pos, p, referenceType), new TExpr.LocalGet(pos, type, value)),
                        accessorCtx, null), Mode.INDEPENDENT);
            }
            setter = new CheckedFunction(p, null, extensionType, List.of(), List.of(value), checker.unit());
            setter.setBody(new TExpr.Return(pos, ts.nothing(), setter,
                    withPropertyReferenceType(setterValue, finalReferenceType)));
        }
        return new CheckedDelegate(delegate, getter, setter);
    }

    /** A call on the delegate in its field. */
    private Candidate resolve(int pos, String name, KType delegateType, PropertySymbol p, TExpr dispatchThis,
                              List<TExpr> args, Context ctx, ConstraintSystem current) {
        CallInfo info = operator(pos, name, new TExpr.DelegateGet(pos, delegateType, p, dispatchThis), args);
        return checker.resolved(info, checker.calls().resolve(info, ctx, current));
    }

    /** Completes a call partially, its system becoming the shared one. */
    private TExpr partially(Candidate c, Mode mode, ConstraintSystem current, List<Candidate> calls) {
        TExpr result = checker.completer().completeDelegateOperator(c, mode);
        current.replaceContentWith(c.system);
        calls.add(c);
        return result;
    }

    private static CallInfo operator(int pos, String name, TExpr receiver, List<TExpr> args) {
        return new CallInfo(CallInfo.Kind.FUNCTION, name, new CallInfo.ValueReceiver(receiver),
                args.stream().map(a -> new CallInfo.Argument(a, null, false, false)).toList(), List.of(),
                TExpr.CallOrigin.OPERATOR, false, pos);
    }

    private TExpr thisRefForDispatch(int pos, TExpr dispatchThis) {
        return dispatchThis != null ? dispatchThis : new TExpr.Const(pos, ts.nullableNothing(), null);
    }

    // ==== Property references ====

    /** {@code ::p}, of a star-projected {@code KPropertyN} until the property's type is known. */
    private static TExpr propertyReference(int pos, PropertySymbol p, KType.ClassType type) {
        List<KType> typeArgs = new ArrayList<>();
        for (TypeParamSymbol tp : p.typeParams()) typeArgs.add(new KType.TypeParamType(tp, false));
        return new TExpr.CallableReference(pos, type, p, typeArgs, null, null, List.of(), null);
    }

    private KType.ClassType propertyReferenceType(PropertySymbol p, boolean member) {
        int arity = (member ? 1 : 0) + (p.signature().receiver() != null ? 1 : 0);
        TypeArg[] stars = new TypeArg[arity + 1];
        Arrays.fill(stars, TypeArg.STAR);
        return ts.classType("kotlin.reflect.K" + (p.isVar() ? "Mutable" : "") + "Property" + arity, stars);
    }

    /**
     * As FIR's replacePropertyReferenceTypeInDelegateAccessors: the class
     * (star-projected) or extension receiver, then the property's type.
     */
    private static KType.ClassType finalPropertyReferenceType(KType.ClassType type, KType dispatchType,
                                                              KType extensionType, KType propertyType) {
        List<TypeArg> args = new ArrayList<>();
        for (int i = 0; i < type.args().size(); i++) {
            KType arg = i == type.args().size() - 1 ? propertyType
                    : i == 0 && dispatchType != null ? starProjected(dispatchType) : extensionType;
            args.add(arg != null ? TypeArg.of(arg) : type.args().get(i));
        }
        return new KType.ClassType(type.symbol(), args, false);
    }

    private static KType starProjected(KType type) {
        KType.ClassType c = (KType.ClassType) type;
        return new KType.ClassType(c.symbol(), c.args().stream().map(a -> TypeArg.STAR).toList(), false);
    }

    /** A delegate operator call with its {@code ::p} argument of the final type. */
    private static TExpr withPropertyReferenceType(TExpr e, KType type) {
        if (!(e instanceof TExpr.Call c) || c.args().size() < 2) return e;
        TExpr.Argument a = c.args().get(1);
        if (!(a.value() instanceof TExpr.CallableReference r)) return e;
        List<TExpr.Argument> args = new ArrayList<>(c.args());
        args.set(1, new TExpr.Argument(a.param(), new TExpr.CallableReference(r.pos(), type, r.target(), r.typeArgs(),
                r.dispatchReceiver(), r.extensionReceiver(), r.contextArgs(), r.adaptation())));
        return new TExpr.Call(c.pos(), c.type(), c.function(), c.typeArgs(), c.dispatchReceiver(), c.extensionReceiver(),
                c.contextArgs(), args, c.superCall(), c.origin());
    }
}
