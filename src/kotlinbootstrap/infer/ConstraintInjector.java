package kotlinbootstrap.infer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import kotlinbootstrap.types.CapturedTypeConstructor;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.SubtypeChecker;
import kotlinbootstrap.types.TypeApproximator;
import kotlinbootstrap.types.TypeSystem;
import kotlinbootstrap.types.TypeVariable;
import kotlinbootstrap.types.Types;
import kotlinbootstrap.types.Variance;

/**
 * Adds constraints to a system, as kotlinc's ConstraintInjector: a
 * subtyping question with type variables is answered by a
 * {@link SubtypeChecker} that records the bounds it implies on the
 * variables, which are then incorporated with the other constraints, which
 * in turn raises new subtyping questions, until nothing new turns up.
 */
final class ConstraintInjector {
    private final TypeSystem ts;
    private final ConstraintIncorporator incorporator;

    ConstraintInjector(TypeSystem ts) {
        this.ts = ts;
        this.incorporator = new ConstraintIncorporator(ts);
    }

    void addInitialSubtypeConstraint(ConstraintSystem c, KType lower, KType upper, ConstraintPosition position) {
        InitialConstraint initial = new InitialConstraint(lower, upper, ConstraintKind.UPPER, position);
        c.addInitialConstraint(initial);
        State state = new State(c, new IncorporationPosition(initial, false));
        addSubTypeConstraintAndIncorporateIt(state, lower, upper);
    }

    void addInitialEqualityConstraint(ConstraintSystem c, KType a, KType b, ConstraintPosition position) {
        KType typeVariable;
        KType equalType;
        if (InferenceTypes.typeVariable(a) != null) {
            typeVariable = a;
            equalType = b;
        } else if (InferenceTypes.typeVariable(b) != null) {
            typeVariable = b;
            equalType = a;
        } else {
            return;
        }
        InitialConstraint initial = new InitialConstraint(typeVariable, equalType, ConstraintKind.EQUALITY, position);
        c.addInitialConstraint(initial);
        State state = new State(c, new IncorporationPosition(initial, false));
        // Constraints like T? == Foo! are added the old way.
        if (typeVariable instanceof KType.Flexible || InferenceTypes.isMarkedNullable(typeVariable)) {
            addSubTypeConstraintAndIncorporateIt(state, typeVariable, equalType);
            addSubTypeConstraintAndIncorporateIt(state, equalType, typeVariable);
            return;
        }
        state.addConstraint(InferenceTypes.typeVariable(typeVariable), equalType, ConstraintKind.EQUALITY, false);
        processConstraints(c, state);
    }

    private void addSubTypeConstraintAndIncorporateIt(State state, KType lower, KType upper) {
        state.runIsSubtypeOf(lower, upper, false, false);
        processConstraints(state.c, state);
    }

    void processGivenForkPointBranchConstraints(ConstraintSystem c, List<ConstraintSystem.VariableConstraint> branch,
                                                IncorporationPosition position) {
        State state = new State(c, position);
        processGivenConstraints(c, state, branch);
        processConstraintsIgnoringForksData(c, state);
    }

    private void processConstraints(ConstraintSystem c, State state) {
        processConstraintsIgnoringForksData(c, state);
        List<List<List<ConstraintSystem.VariableConstraint>>> forks = state.extractForkPointsData();
        if (forks != null) {
            for (var branches : forks) c.addForkPoint(new ConstraintSystem.ForkPoint(state.position, branches));
            c.onNewConstraintOrForkPoint();
            // During completion, fork points are resolved right away.
            if (c.atCompletionState()) c.resolveForkPointsConstraints();
        }
    }

    private void processConstraintsIgnoringForksData(ConstraintSystem c, State state) {
        while (state.possibleNewConstraints != null) {
            List<ConstraintSystem.VariableConstraint> constraints = state.possibleNewConstraints;
            state.possibleNewConstraints = null;
            processGivenConstraints(c, state, constraints);
        }
    }

    private void processGivenConstraints(ConstraintSystem c, State state, List<ConstraintSystem.VariableConstraint> constraints) {
        for (ConstraintSystem.VariableConstraint vc : constraints) {
            TypeVariable variable = vc.variable();
            Constraint constraint = vc.constraint();
            if (shouldWeSkipConstraint(variable, constraint)) continue;

            VariableWithConstraints variableConstraints = c.notFixedTypeVariables().get(variable);
            if (variableConstraints == null) throw state.fixedTypeVariable(variable);
            // Added here rather than in the subtype checker, since incorporation reads the constraints.
            VariableWithConstraints.Added added = variableConstraints.addConstraint(constraint);
            ConstraintPosition from = constraint.position().from();
            Constraint toIncorporate = null;
            if (added.wasAdded() && !constraint.isNullabilityConstraint()) {
                toIncorporate = added.constraint();
            } else if (from.kind() == ConstraintPosition.Kind.FIX_VARIABLE && from.detail() == variable
                    && constraint.kind() == ConstraintKind.EQUALITY) {
                toIncorporate = added.constraint();
            }
            if (added.wasAdded()) {
                c.onNewConstraintOrForkPoint();
                for (TypeVariable referenced : InferenceTypes.extractAllContainingTypeVariables(constraint.type())) {
                    c.recordTypeVariableReferenceInConstraint(variable, referenced);
                }
            }
            if (toIncorporate != null) {
                ConstraintPosition.Kind initialKind = constraint.position().fromKind();
                boolean causedByFixation = initialKind == ConstraintPosition.Kind.FIX_VARIABLE
                        || initialKind == ConstraintPosition.Kind.SEMI_FIX_VARIABLE;
                incorporator.incorporate(state, variable, toIncorporate, causedByFixation);
            }
        }
    }

    private boolean shouldWeSkipConstraint(TypeVariable variable, Constraint constraint) {
        if (constraint.kind() == ConstraintKind.EQUALITY) return false;
        KType type = constraint.type();
        if (InferenceTypes.typeVariable(type) == variable) {
            // T? <: T is kept; T <: T(?!) says nothing.
            return !(InferenceTypes.isMarkedNullable(Types.lowerBound(type)) && constraint.kind() == ConstraintKind.LOWER);
        }
        // T <: Any? from a declared bound.
        return constraint.position().fromKind() == ConstraintPosition.Kind.DECLARED_UPPER_BOUND
                && constraint.kind() == ConstraintKind.UPPER && InferenceTypes.isNullableAny(ts, type);
    }

    /**
     * Answers subtyping questions for one initial constraint (and what
     * incorporation derives from it), collecting the constraints on type
     * variables they imply: kotlinc's TypeCheckerStateForConstraintSystem
     * and TypeCheckerStateForConstraintInjector.
     */
    final class State extends SubtypeChecker {
        final ConstraintSystem c;
        final IncorporationPosition position;
        List<ConstraintSystem.VariableConstraint> possibleNewConstraints;

        private List<List<List<ConstraintSystem.VariableConstraint>>> forkPointsData;
        /** For each fork point being run, the branches so far; for each branch being run, its constraints. */
        private final List<List<List<ConstraintSystem.VariableConstraint>>> forkPointStack = new ArrayList<>();
        private final List<List<ConstraintSystem.VariableConstraint>> branchStack = new ArrayList<>();

        private boolean incorporatingFromDeclaredUpperBound;
        private Set<TypeVariable> currentDerivedFrom = Set.of();

        State(ConstraintSystem c, IncorporationPosition position) {
            super(c.ts());
            this.c = c;
            this.position = position;
        }

        List<List<List<ConstraintSystem.VariableConstraint>>> extractForkPointsData() {
            var result = forkPointsData;
            forkPointsData = null;
            return result;
        }

        IllegalStateException fixedTypeVariable(TypeVariable variable) {
            return new IllegalStateException("Type variable " + variable + " should not be fixed; base constraint "
                    + position.initial());
        }

        private boolean isMyTypeVariable(KType rigid) {
            return !(rigid instanceof KType.Flexible) && c.allTypeVariables().contains(InferenceTypes.typeVariable(rigid));
        }

        private boolean anyBoundIsMyTypeVariable(KType t) {
            return t instanceof KType.Flexible f
                    ? isMyTypeVariable(f.lower()) || isMyTypeVariable(f.upper()) : isMyTypeVariable(t);
        }

        void runIsSubtypeOf(KType lower, KType upper, boolean shouldTryUseDifferentFlexibilityForUpperType,
                            boolean fromNullabilityConstraint) {
            if (isSubtype(lower, upper, fromNullabilityConstraint)) return;
            if (shouldTryUseDifferentFlexibilityForUpperType && !(upper instanceof KType.Flexible)) {
                // Flexibility is not propagated through type variables; tolerate that.
                KType flexibleUpper = InferenceTypes.createTrivialFlexibleTypeOrSelf(upper);
                if (!isSubtype(lower, flexibleUpper, false)) {
                    c.addError(new ConstraintError(ConstraintError.Kind.NEW_CONSTRAINT, null, lower, flexibleUpper, position));
                }
            } else {
                c.addError(new ConstraintError(ConstraintError.Kind.NEW_CONSTRAINT, null, lower, upper, position));
            }
        }

        // ==== From incorporation ====

        /** {@code A <: B} for {@code A <:(=) T <:(=) B}. */
        void processNewInitialConstraintFromIncorporation(KType lower, KType upper,
                                                          boolean shouldTryUseDifferentFlexibilityForUpperType,
                                                          Set<TypeVariable> newDerivedFrom,
                                                          boolean fromNullabilityConstraint,
                                                          boolean fromDeclaredUpperBound) {
            if (lower.equals(upper)) return;
            if (!currentDerivedFrom.isEmpty()) throw new IllegalStateException("Recursive incorporation");
            try {
                currentDerivedFrom = newDerivedFrom;
                incorporatingFromDeclaredUpperBound = fromDeclaredUpperBound;
                runIsSubtypeOf(lower, upper, shouldTryUseDifferentFlexibilityForUpperType, fromNullabilityConstraint);
            } finally {
                currentDerivedFrom = Set.of();
                incorporatingFromDeclaredUpperBound = false;
            }
        }

        void addNewIncorporatedConstraint(TypeVariable variable, KType type, ConstraintKind kind,
                                          Set<TypeVariable> derivedFrom, boolean isNullabilityConstraint) {
            KType target = type;
            if (InferenceTypes.contains(type, this::isCapturedTypeFromSubtyping)) {
                // T <: type follows from T <: a subtype of it, and similarly for lower constraints.
                KType approximated = switch (kind) {
                    case UPPER -> TypeApproximator.approximateToSubType(ts, type, TypeApproximator.Config.SUBTYPE_CAPTURED_TYPES);
                    case LOWER -> TypeApproximator.approximateToSuperType(ts, type, TypeApproximator.Config.SUBTYPE_CAPTURED_TYPES);
                    case EQUALITY -> null;
                };
                if (approximated == null) {
                    c.addError(new ConstraintError(ConstraintError.Kind.CAPTURED_TYPE_FROM_SUBTYPING, variable, type, null,
                            position));
                    return;
                }
                target = approximated;
            }
            IncorporationPosition p = incorporatingFromDeclaredUpperBound
                    ? new IncorporationPosition(position.initial(), true) : position;
            addPossibleNewConstraint(variable, new Constraint(kind, target, p, derivedFrom, isNullabilityConstraint));
        }

        private boolean isCapturedTypeFromSubtyping(KType t) {
            if (!(t instanceof KType.Captured captured)) return false;
            return switch (captured.constructor().status()) {
                case FROM_EXPRESSION -> false;
                case FOR_SUBTYPING -> true;
                case FOR_INCORPORATION -> throw new IllegalStateException(
                        "Captured type for incorporation escaped from incorporation: " + t + "; base constraint "
                                + position.initial());
            };
        }

        private void addPossibleNewConstraint(TypeVariable variable, Constraint constraint) {
            ConstraintSystem.VariableConstraint vc = new ConstraintSystem.VariableConstraint(variable, constraint);
            if (!forkPointStack.isEmpty()) {
                if (branchStack.isEmpty()) throw new IllegalStateException("Constraint added outside of a fork branch");
                branchStack.getLast().add(vc);
                return;
            }
            if (possibleNewConstraints == null) possibleNewConstraints = new ArrayList<>();
            possibleNewConstraints.add(vc);
        }

        private void addConstraint(TypeVariable variable, KType type, ConstraintKind kind, boolean fromNullability) {
            if (!c.allTypeVariables().contains(variable)) throw new IllegalStateException("Not a type variable: " + variable);
            addNewIncorporatedConstraint(variable, type, kind, currentDerivedFrom, fromNullability);
        }

        private void addLowerConstraint(KType variableType, KType subType, boolean fromNullability) {
            addConstraint(InferenceTypes.typeVariable(variableType), subType, ConstraintKind.LOWER, fromNullability);
        }

        // ==== Fork points ====

        @Override
        protected boolean runForkingPoint(List<BooleanSupplier> forks) {
            forkPointStack.add(new ArrayList<>());
            boolean anySuccessful = false;
            for (BooleanSupplier fork : forks) {
                branchStack.add(new ArrayList<>());
                anySuccessful |= fork.getAsBoolean();
                List<ConstraintSystem.VariableConstraint> branch = branchStack.removeLast();
                if (!branch.isEmpty()) forkPointStack.getLast().add(List.copyOf(new LinkedHashSet<>(branch)));
            }
            List<List<ConstraintSystem.VariableConstraint>> constraintSets = forkPointStack.removeLast();
            if (constraintSets.isEmpty()) return anySuccessful;
            if (constraintSets.size() > 1) {
                if (forkPointsData == null) forkPointsData = new ArrayList<>();
                forkPointsData.add(constraintSets);
                return anySuccessful;
            }
            processGivenForkPointBranchConstraints(c, constraintSets.getFirst(), position);
            return anySuccessful;
        }

        // ==== Subtyping with type variables ====

        @Override
        protected LowerCapturedTypePolicy lowerCapturedTypePolicy(KType sub, KType.Captured sup) {
            if (isMyTypeVariable(sub)) {
                var projection = sup.constructor().projection();
                return !projection.isStar() && projection.variance() == Variance.IN && isMyTypeVariable(projection.type())
                        ? LowerCapturedTypePolicy.CHECK_ONLY_LOWER : LowerCapturedTypePolicy.SKIP_LOWER;
            }
            if (InferenceTypes.contains(sub, this::anyBoundIsMyTypeVariable)) return LowerCapturedTypePolicy.CHECK_ONLY_LOWER;
            return LowerCapturedTypePolicy.CHECK_SUBTYPE_AND_LOWER;
        }

        @Override
        protected Boolean addSubtypeConstraint(KType subType, KType superType, boolean fromNullability) {
            Boolean answer = null;
            if (anyBoundIsMyTypeVariable(superType)) {
                answer = simplifyLowerConstraint(superType, subType, fromNullability);
            }
            if (anyBoundIsMyTypeVariable(subType)) {
                return simplifyUpperConstraint(subType, superType) && (answer == null || answer);
            }
            KType extracted = extractTypeVariableForSubtype(subType, superType);
            if (extracted != null) return simplifyUpperConstraint(extracted, superType) && (answer == null || answer);
            Boolean intersection = simplifyConstraintForPossibleIntersectionSubType(subType, superType);
            return intersection != null ? intersection : answer;
        }

        /** The type variable T in a subtype {@code Captured(out T)}; for {@code Captured(in T)}, adds the constraints. */
        private KType extractTypeVariableForSubtype(KType subType, KType superType) {
            KType.Captured captured = subType instanceof KType.Flexible ? null : InferenceTypes.capturedUnwrappingDnn(subType);
            if (captured == null) return null;
            var projection = captured.constructor().projection();
            if (projection.isStar()) return null;
            KType type = projection.type();
            if (projection.variance() == Variance.IN) {
                if (isMyTypeVariable(type)) {
                    simplifyLowerConstraint(type, superType, false);
                    if (isMyTypeVariable(superType)) addLowerConstraint(superType, ts.nullableAny(), false);
                }
                return null;
            }
            if (projection.variance() != Variance.OUT) return null;
            if (isMyTypeVariable(type)) return type;
            if (type instanceof KType.Flexible f && isMyTypeVariable(f.lower())) return f.lower();
            return null;
        }

        /**
         * {@code Foo <: T} is kept. {@code Foo <: T?} gives {@code Foo & Any <: T}
         * or {@code Foo <: T}. {@code Foo <: T!} gives {@code Foo! <: T}, and
         * {@code (Foo..Bar) <: T!} gives {@code (Foo & Any..Bar) <: T}.
         * {@code Foo? <: T & Any} is a contradiction.
         */
        private boolean simplifyLowerConstraint(KType typeVariable, KType subType, boolean fromNullability) {
            boolean subTypeIsVariable = InferenceTypes.typeVariable(subType) != null;
            KType lowerConstraint;
            if (typeVariable instanceof KType.Flexible) {
                if (subType instanceof KType.Flexible f) {
                    lowerConstraint = InferenceTypes.makeLowerBoundDefinitelyNotNullOrNotNull(f);
                } else if (!InferenceTypes.isMarkedNullable(subType)) {
                    lowerConstraint = InferenceTypes.createTrivialFlexibleTypeOrSelf(subType);
                } else {
                    lowerConstraint = subType;
                }
            } else if (typeVariable instanceof KType.DefinitelyNotNull && !subTypeIsVariable
                    && !Types.isSubtypeOfAny(subType)) {
                return false;
            } else if (InferenceTypes.isMarkedNullable(typeVariable)) {
                TypeVariable variable = InferenceTypes.typeVariable(typeVariable);
                // Foo <: T? usually gives Foo <: T, which keeps e.g. Bar<Foo> for Bar(t) with class Bar<T>(t: T?),
                // with Foo & Any <: T to fall back on; but just the latter where T is only in out positions.
                if (subTypeIsVariable || !variable.containedInInvariantOrContravariantPositions()) {
                    lowerConstraint = Types.makeDefinitelyNotNull(subType);
                } else {
                    KType notNull = subType.withNullable(false);
                    if (addForkPointForDifferentDnnAndMarkedNotNullable(subType, notNull, typeVariable, fromNullability)) {
                        return true;
                    }
                    lowerConstraint = notNull;
                }
            } else {
                lowerConstraint = subType;
            }
            addLowerConstraint(typeVariable, lowerConstraint, fromNullability);
            return true;
        }

        private boolean addForkPointForDifferentDnnAndMarkedNotNullable(KType subType, KType notNullSubType,
                                                                       KType typeVariable, boolean fromNullability) {
            KType dnnSubType = Types.makeDefinitelyNotNull(subType);
            if (dnnSubType.equals(notNullSubType)) return false;
            List<BooleanSupplier> forks = new ArrayList<>();
            for (KType variant : List.of(notNullSubType, dnnSubType)) {
                forks.add(() -> {
                    addLowerConstraint(typeVariable, variant, fromNullability);
                    return true;
                });
            }
            runForkingPoint(forks);
            return true;
        }

        /**
         * {@code T! <: Foo} gives {@code T <: Foo!}; {@code T? <: Foo} gives
         * {@code T <: Foo} and {@code Nothing? <: Foo}; {@code T & Any <: Foo}
         * gives {@code T <: Foo?}.
         */
        private boolean simplifyUpperConstraint(KType typeVariable, KType superType) {
            KType lower = Types.lowerBound(typeVariable);
            KType simplified;
            if (typeVariable instanceof KType.Flexible) {
                if (lower instanceof KType.DefinitelyNotNull) {
                    simplified = superType.withNullable(true);
                } else if (!(superType instanceof KType.Flexible)) {
                    simplified = InferenceTypes.createTrivialFlexibleTypeOrSelf(superType);
                } else {
                    simplified = superType;
                }
            } else if (lower instanceof KType.DefinitelyNotNull) {
                simplified = superType.withNullable(true);
            } else {
                simplified = superType;
            }
            addConstraint(InferenceTypes.typeVariable(lower), simplified, ConstraintKind.UPPER, false);
            if (InferenceTypes.isMarkedNullable(lower)) {
                return anyBoundIsMyTypeVariable(simplified) || isSubtype(ts.nullableNothing(), simplified);
            }
            return true;
        }

        private Boolean simplifyConstraintForPossibleIntersectionSubType(KType subType, KType superType) {
            if (!(Types.lowerBound(subType) instanceof KType.Intersection i)) return null;
            List<KType> components = i.components().stream().map(Types::lowerBound).toList();
            List<KType> typeVariables = components.stream().filter(this::isMyTypeVariable).toList();
            if (typeVariables.isEmpty()) return null;
            List<KType> others = components.stream().filter(t -> !isMyTypeVariable(t)).toList();
            if (!others.isEmpty() && ts.isSubtype(ts.intersect(others), superType)) return true;
            // From {Any & T} <: S, T <: S does not follow, only T <: S?.
            KType sup = others.stream().anyMatch(Types::isSubtypeOfAny) ? superType.withNullable(true) : superType;
            for (KType v : typeVariables) {
                if (!simplifyUpperConstraint(v, sup)) return false;
            }
            return true;
        }
    }
}
