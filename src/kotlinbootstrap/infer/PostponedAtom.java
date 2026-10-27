package kotlinbootstrap.infer;

import java.util.List;

import kotlinbootstrap.types.KType;

/**
 * An argument whose analysis waits for some variables of the call's system
 * to be fixed, as kotlinc's PostponedResolvedAtomMarker: a lambda needs its
 * parameter types, a callable reference the type it is expected to have.
 */
public interface PostponedAtom {
    /** The types that must be known before analysis, e.g. a lambda's receiver and parameter types. */
    List<KType> inputTypes();

    /** The type analysis gives constraints for, e.g. a lambda's return type; or null. */
    KType outputType();

    boolean analyzed();
}
