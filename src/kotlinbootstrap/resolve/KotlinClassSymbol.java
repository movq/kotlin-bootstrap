package kotlinbootstrap.resolve;

import java.util.ArrayList;
import java.util.List;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.ast.Modifiers;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassId;
import kotlinbootstrap.symbols.ClassKind;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Lazy;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.Modality;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.SymbolTable;
import kotlinbootstrap.symbols.TypeAliasSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.KType;

/** A class declared in Kotlin source. */
public final class KotlinClassSymbol extends ClassSymbol {
    private final Resolver resolver;
    private final Decl.Class decl;
    private final FileContext file;
    private final List<ClassSymbol> nested = new ArrayList<>();
    private final Resolver.TypeParams typeParams;
    private final Lazy<List<KType>> supertypes;
    private final Lazy<MemberScope> members;
    private Scope bodyScope;
    private Scope staticBodyScope;

    KotlinClassSymbol(Resolver resolver, Decl.Class decl, ClassId id, KotlinClassSymbol outer, FileContext file) {
        super(id, outer, kind(decl), Origin.KOTLIN_SOURCE,
                Resolver.visibility(decl.modifiers(), Visibility.PUBLIC), modality(decl),
                Resolver.keywords(decl.modifiers()));
        this.resolver = resolver;
        this.decl = decl;
        this.file = file;
        this.typeParams = resolver.typeParams(decl.typeParams(), decl.constraints(), outerScope(), file);
        this.supertypes = new Lazy<>(this::resolveSupertypes);
        this.members = new Lazy<>(this::buildMembers);
    }

    private static ClassKind kind(Decl.Class decl) {
        return switch (decl.kind()) {
            case INTERFACE -> ClassKind.INTERFACE;
            case OBJECT -> ClassKind.OBJECT;
            case CLASS -> decl.modifiers().has(Modifier.ENUM) ? ClassKind.ENUM_CLASS
                    : decl.modifiers().has(Modifier.ANNOTATION) ? ClassKind.ANNOTATION_CLASS : ClassKind.CLASS;
        };
    }

    private static Modality modality(Decl.Class decl) {
        if (decl.modifiers().has(Modifier.ABSTRACT)) return Modality.ABSTRACT;
        if (decl.modifiers().has(Modifier.OPEN)) return Modality.OPEN;
        if (decl.modifiers().has(Modifier.SEALED)) return Modality.SEALED;
        return decl.kind() == Decl.ClassKind.INTERFACE ? Modality.ABSTRACT : Modality.FINAL;
    }

    public Decl.Class decl() {
        return decl;
    }

    public FileContext file() {
        return file;
    }

    void addNested(ClassSymbol cls) {
        nested.add(cls);
    }

    @Override
    public KotlinClassSymbol outer() {
        return (KotlinClassSymbol) super.outer();
    }

    @Override
    public List<TypeParamSymbol> typeParams() {
        return typeParams.symbols();
    }

    @Override
    public List<KType> supertypes() {
        return supertypes.get();
    }

    @Override
    public boolean supertypesInProgress() {
        return supertypes.isComputing();
    }

    @Override
    public MemberScope members() {
        return members.get();
    }

    // ==== Scopes ====

    /**
     * The scope around the class: the outer class's body, or the file. The
     * outer class's type parameters are only visible in inner classes.
     */
    private Scope outerScope() {
        return outer() == null ? file.scope() : outer().bodyScope(isInner());
    }

    /** The scope of the class header: its type parameters, then the outer scope. */
    Scope headerScope() {
        return typeParams.scope();
    }

    /**
     * The scope inside the class body: the class's type parameters (unless
     * for a nested class), then nested classes of the class, its companion
     * and its supertypes, then the outer scope.
     */
    Scope bodyScope(boolean withTypeParams) {
        if (bodyScope == null) {
            staticBodyScope = Scope.of(this::findClassifierInBody, outerScope());
            bodyScope = Scope.typeParams(typeParams(), staticBodyScope);
        }
        return withTypeParams ? bodyScope : staticBodyScope;
    }

    private ClassifierSymbol findClassifierInBody(String name) {
        ClassifierSymbol c = members().classifier(name);
        if (c != null) return c;
        ClassSymbol companion = companion();
        if (companion != null) {
            c = SymbolTable.findInheritedClassifier(companion, name);
            if (c != null) return c;
        }
        if (supertypesInProgress()) return null;
        for (KType t : supertypes()) {
            if (t instanceof KType.ClassType ct) {
                c = SymbolTable.findInheritedClassifier(ct.symbol(), name);
                if (c != null) return c;
            }
        }
        return null;
    }

    // ==== Completion ====

    private List<KType> resolveSupertypes() {
        List<KType> result = new ArrayList<>();
        for (Decl.SuperTypeEntry e : decl.superTypes()) {
            result.add(resolver.types().resolve(e.type(), headerScope(), file));
        }
        if (result.isEmpty() && !id().fqName().equals("kotlin.Any") && !id().fqName().equals("kotlin.Nothing")) {
            result.add(switch (kind()) {
                case ENUM_CLASS -> resolver.types().enumSupertype(this);
                case ANNOTATION_CLASS -> resolver.types().classType("kotlin.Annotation");
                default -> resolver.types().any();
            });
        }
        return result;
    }

    private MemberScope buildMembers() {
        MemberScope scope = new MemberScope();
        for (ClassSymbol c : nested) scope.add(c);
        Scope body = bodyScope(true);
        for (Decl member : decl.members()) addMember(scope, member, body, false);

        if (decl.primaryConstructor() != null) {
            Decl.PrimaryConstructor ctor = decl.primaryConstructor();
            scope.add(constructor(ctor.modifiers(), ctor.params(), ctor));
            for (Decl.Param p : ctor.params()) {
                if (p.valOrVar() != Decl.ValOrVar.NONE) scope.add(constructorProperty(p));
            }
        } else if (scope.constructors().isEmpty() && (kind() == ClassKind.CLASS || kind() == ClassKind.ENUM_CLASS)) {
            scope.add(constructor(Modifiers.EMPTY, List.of(), decl));
        }
        for (Decl.EnumEntry e : decl.enumEntries()) {
            KType type = resolver.types().defaultType(this);
            scope.add(new PropertySymbol(e.name(), Origin.KOTLIN_SOURCE, this, Visibility.PUBLIC, Modality.FINAL,
                    Resolver.keywords(e.modifiers()), true, e, false, false, null, List::of,
                    () -> CallableSymbol.Signature.NONE, () -> type));
        }
        return scope;
    }

    private void addMember(MemberScope scope, Decl member, Scope body, boolean isStatic) {
        switch (member) {
            case Decl.Function f -> scope.add(resolver.function(f, this, body, file, this, isStatic));
            case Decl.Property p -> scope.add(resolver.property(p, this, body, file, this, isStatic));
            case Decl.TypeAlias a -> {
                TypeAliasSymbol alias = resolver.typeAlias(a, id().nested(a.name()), body, file);
                scope.add(alias);
                resolver.table().addKotlinClassifier(alias.id(), alias);
            }
            case Decl.SecondaryConstructor c -> scope.add(constructor(c.modifiers(), c.params(), c));
            case Decl.CompanionBlock b -> {
                for (Decl d : b.members()) addMember(scope, d, body, true);
            }
            case Decl.Class c -> {
                // Entered already.
            }
            case Decl.Initializer i -> {
            }
            default -> throw new ResolveException(file.file().source(), member.pos(), "Unexpected member");
        }
    }

    private FunctionSymbol constructor(Modifiers modifiers, List<Decl.Param> params,
                                       Object source) {
        Visibility defaultVisibility = kind() == ClassKind.ENUM_CLASS || modality() == Modality.SEALED
                ? Visibility.PRIVATE : Visibility.PUBLIC;
        Scope scope = bodyScope(true);
        return new FunctionSymbol(FunctionSymbol.CONSTRUCTOR_NAME, Origin.KOTLIN_SOURCE, this,
                Resolver.visibility(modifiers, defaultVisibility), Modality.FINAL, Resolver.keywords(modifiers),
                false, source, this::typeParams,
                () -> new CallableSymbol.Signature(null, List.of(), resolver.valueParams(params, scope, file)),
                () -> resolver.types().defaultType(this));
    }

    private PropertySymbol constructorProperty(Decl.Param p) {
        Visibility visibility = Resolver.visibility(p.modifiers(), Visibility.PUBLIC);
        boolean isVar = p.valOrVar() == Decl.ValOrVar.VAR;
        Scope scope = bodyScope(true);
        return new PropertySymbol(p.name(), Origin.KOTLIN_SOURCE, this, visibility,
                Resolver.modality(p.modifiers(), this, false), Resolver.keywords(p.modifiers()), false, p, isVar,
                false, isVar ? visibility : null, List::of, () -> CallableSymbol.Signature.NONE,
                () -> resolver.types().resolve(p.type(), scope, file));
    }
}
