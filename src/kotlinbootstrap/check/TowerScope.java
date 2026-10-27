package kotlinbootstrap.check;

import java.util.ArrayList;
import java.util.List;

import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.types.Substitution;

/**
 * A scope of declarations found by name, a level of the tower that has no
 * receiver of its own: an importing scope, the file's package, the static
 * members and nested classes of a class, or a local scope.
 */
interface TowerScope {
    /**
     * A function or property found in a scope.
     *
     * @param importedFromObject the object it is a member of, when imported from one; its dispatch receiver
     * @param substitution       for a member of an object, as for the object's type
     */
    record ScopeMember(CallableSymbol symbol, ClassSymbol importedFromObject, Substitution substitution) {
        static List<ScopeMember> of(List<? extends CallableSymbol> symbols) {
            List<ScopeMember> result = new ArrayList<>(symbols.size());
            for (CallableSymbol s : symbols) result.add(new ScopeMember(s, null, Substitution.EMPTY));
            return result;
        }
    }

    List<ScopeMember> functions(String name);

    List<ScopeMember> properties(String name);

    List<ClassifierSymbol> classifiers(String name);

    /** The statics and nested classifiers of a class, as reached by simple name in its body. */
    record ClassStatics(ClassSymbol owner, MemberScopes scopes) implements TowerScope {
        @Override
        public List<ScopeMember> functions(String name) {
            return ScopeMember.of(scopes.statics(owner, name, true));
        }

        @Override
        public List<ScopeMember> properties(String name) {
            return ScopeMember.of(scopes.statics(owner, name, false));
        }

        @Override
        public List<ClassifierSymbol> classifiers(String name) {
            ClassifierSymbol c = owner.members().classifier(name);
            return c == null ? List.of() : List.of(c);
        }
    }
}
