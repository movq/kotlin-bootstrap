package kotlinbootstrap.symbols;

import java.util.List;
import java.util.Set;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.types.KType;

/**
 * A class, interface, object or enum entry, from any origin. The header
 * (name, kind, modifiers) is known up front; type parameters, supertypes
 * and members are computed on demand by the subclass for each origin.
 */
public abstract non-sealed class ClassSymbol implements ClassifierSymbol {
    private final ClassId id;
    private final ClassSymbol outer;
    private final ClassKind kind;
    private final Origin origin;
    private final Visibility visibility;
    private final Modality modality;
    private final Set<Modifier> modifiers;

    /**
     * @param outer     the containing class, null for a top-level class
     * @param modifiers flag keywords such as {@code data}, {@code inner} or
     *                  {@code companion}; visibility and modality are separate
     */
    protected ClassSymbol(ClassId id, ClassSymbol outer, ClassKind kind, Origin origin, Visibility visibility,
                          Modality modality, Set<Modifier> modifiers) {
        this.id = id;
        this.outer = outer;
        this.kind = kind;
        this.origin = origin;
        this.visibility = visibility;
        this.modality = modality;
        this.modifiers = modifiers;
    }

    public ClassId id() {
        return id;
    }

    @Override
    public String name() {
        return id.shortName();
    }

    public ClassSymbol outer() {
        return outer;
    }

    public ClassKind kind() {
        return kind;
    }

    @Override
    public Origin origin() {
        return origin;
    }

    public Visibility visibility() {
        return visibility;
    }

    public Modality modality() {
        return modality;
    }

    public Set<Modifier> modifiers() {
        return modifiers;
    }

    public boolean has(Modifier m) {
        return modifiers.contains(m);
    }

    public boolean isInterface() {
        return kind == ClassKind.INTERFACE || kind == ClassKind.ANNOTATION_CLASS;
    }

    public boolean isObject() {
        return kind == ClassKind.OBJECT;
    }

    /** Whether instances hold a reference to an instance of the outer class. */
    public boolean isInner() {
        return has(Modifier.INNER);
    }

    /** Whether the class is declared in a function body or initializer (including anonymous objects). */
    public boolean isLocal() {
        return false;
    }

    /** Whether this is the class of an object expression. */
    public boolean isAnonymous() {
        return false;
    }

    public abstract List<TypeParamSymbol> typeParams();

    /** Direct supertypes. Only {@code kotlin.Any} has none. */
    public abstract List<KType> supertypes();

    /** Whether the supertypes are being computed, i.e. asking for them would be a cycle. */
    public boolean supertypesInProgress() {
        return false;
    }

    /** Declared members, not including inherited ones. */
    public abstract MemberScope members();

    /** The companion object, or null. */
    public ClassSymbol companion() {
        for (ClassifierSymbol c : members().classifiers().values()) {
            if (c instanceof ClassSymbol cls && cls.has(Modifier.COMPANION)) return cls;
        }
        return null;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
