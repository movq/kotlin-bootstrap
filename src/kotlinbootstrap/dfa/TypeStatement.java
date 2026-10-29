package kotlinbootstrap.dfa;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import kotlinbootstrap.types.KType;

/**
 * The types a variable is known to have, and those it is known not to
 * have, as FIR's TypeStatement. Immutable.
 *
 * @param upperTypes the variable's value is of all of these
 * @param lowerTypes the variable's value is of none of these
 */
public record TypeStatement(DataFlowVariable variable, Set<KType> upperTypes, Set<DfaType> lowerTypes)
        implements Statement {

    public TypeStatement {
        upperTypes = Collections.unmodifiableSet(new LinkedHashSet<>(upperTypes));
        lowerTypes = Collections.unmodifiableSet(new LinkedHashSet<>(lowerTypes));
    }

    public static TypeStatement empty(DataFlowVariable variable) {
        return new TypeStatement(variable, Set.of(), Set.of());
    }

    public static TypeStatement typeEq(DataFlowVariable variable, KType type) {
        return new TypeStatement(variable, Set.of(type), Set.of());
    }

    public static TypeStatement typeNotEq(DataFlowVariable variable, KType type) {
        return new TypeStatement(variable, Set.of(), Set.of(new DfaType.Cone(type)));
    }

    public static TypeStatement valueNotEq(RealVariable variable, Collection<?> symbols) {
        return new TypeStatement(variable, Set.of(),
                symbols.stream().map(DfaType.Symbol::new).collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    public static TypeStatement valueNotEq(RealVariable variable, boolean value) {
        return new TypeStatement(variable, Set.of(), Set.of(new DfaType.BooleanLiteral(value)));
    }

    public boolean isEmpty() {
        return upperTypes.isEmpty() && lowerTypes.isEmpty();
    }

    public TypeStatement withVariable(DataFlowVariable v) {
        return v == variable ? this : new TypeStatement(v, upperTypes, lowerTypes);
    }

    /** With the types of another statement as well: the conjunction. */
    public TypeStatement and(TypeStatement other) {
        Set<KType> upper = new LinkedHashSet<>(upperTypes);
        upper.addAll(other.upperTypes);
        Set<DfaType> lower = new LinkedHashSet<>(lowerTypes);
        lower.addAll(other.lowerTypes);
        return new TypeStatement(variable, upper, lower);
    }

    @Override
    public String toString() {
        List<String> parts = Stream.of(
                upperTypes.isEmpty() ? null : upperTypes.stream().map(Object::toString).collect(Collectors.joining(" & ")),
                lowerTypes.isEmpty() ? null : "¬(" + lowerTypes.stream().map(Object::toString)
                        .collect(Collectors.joining(" | ")) + ")")
                .filter(s -> s != null).toList();
        return variable + ": " + String.join(" & ", parts);
    }
}
