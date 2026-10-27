package kotlinbootstrap.infer;

import kotlinbootstrap.types.KType;

/**
 * A postponed argument expected to have the type of a type variable, as
 * kotlinc's PostponedAtomWithRevisableExpectedType: completion may revise
 * the expected type to a function type built from the variable's
 * constraints, with variables for the parameter and return types.
 */
public interface PostponedAtomWithRevisableExpectedType extends PostponedAtom {
    /** The function type built for the argument, or null. */
    KType revisedExpectedType();

    void reviseExpectedType(KType type);
}
