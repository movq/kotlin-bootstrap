package kotlinbootstrap.check;

import java.util.HashSet;
import java.util.Set;

import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.resolve.FileContext;
import kotlinbootstrap.resolve.KotlinClassSymbol;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.Origin;
import kotlinbootstrap.symbols.PackageSymbol;
import kotlinbootstrap.symbols.Symbol;
import kotlinbootstrap.symbols.TypeAliasSymbol;
import kotlinbootstrap.symbols.Visibility;
import kotlinbootstrap.types.KType;
import kotlinbootstrap.types.Types;

/**
 * Whether a declaration can be used at a point in a body, as FIR's
 * FirVisibilityChecker. Internal declarations are visible: everything is
 * compiled as one module. Whether the classes a declaration is in are
 * visible is not checked.
 */
final class VisibilityChecker {
    private final Resolver resolver;

    VisibilityChecker(Resolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @param dispatchReceiver the instance a member is used on, nullable;
     *                         for {@code super}, the receiver of the type of {@code this}
     */
    boolean isVisible(Symbol symbol, Context ctx, TExpr dispatchReceiver) {
        Visibility visibility = switch (symbol) {
            case CallableSymbol c -> c.visibility();
            case ClassSymbol c -> c.visibility();
            case TypeAliasSymbol a -> a.visibility();
            default -> Visibility.PUBLIC;
        };
        ClassSymbol owner = owner(symbol);
        return switch (visibility) {
            case PUBLIC, INTERNAL -> true;
            case PRIVATE -> canSeePrivateMemberOf(symbol, owner, ctx);
            case PROTECTED -> canSeeProtectedMemberOf(symbol, owner, ctx, dispatchReceiver)
                    || isJava(owner) && samePackage(symbol, ctx);
            case PACKAGE -> samePackage(symbol, ctx);
        };
    }

    /** The class a member or nested class is declared in; null for a top-level declaration. */
    private static ClassSymbol owner(Symbol symbol) {
        return switch (symbol) {
            case CallableSymbol c -> c.owner() instanceof ClassSymbol cls ? cls : null;
            case ClassSymbol c -> c.outer();
            default -> null;
        };
    }

    private boolean canSeePrivateMemberOf(Symbol symbol, ClassSymbol owner, Context ctx) {
        if (owner == null) {
            FileContext file = file(symbol);
            return file == null || file == ctx.file();
        }
        for (ClassSymbol c = ctx.containingClass(); c != null; c = c.outer()) {
            if (c == owner) return true;
            // The outer class sees the private members of its companion.
            if (owner.has(Modifier.COMPANION) && owner.outer() == c) return true;
        }
        return false;
    }

    private FileContext file(Symbol symbol) {
        return switch (symbol) {
            case KotlinClassSymbol c -> c.file();
            case CallableSymbol c -> {
                Resolver.DeclarationContext dc = resolver.context(c);
                yield dc == null ? null : dc.file();
            }
            default -> null;
        };
    }

    private boolean canSeeProtectedMemberOf(Symbol symbol, ClassSymbol owner, Context ctx, TExpr dispatchReceiver) {
        if (owner == null) return false;
        if (canSeePrivateMemberOf(symbol, owner, ctx)) return true;
        boolean isVariableOrNamedFunction = symbol instanceof CallableSymbol
                && !(symbol instanceof FunctionSymbol f && f.isConstructor());
        ClassSymbol companionOwner = dispatchReceiver == null ? null : companionOwner(dispatchReceiver.type());
        for (ClassSymbol c = ctx.containingClass(); c != null; c = c.outer()) {
            if (companionOwner != null && isSubclassOf(c, companionOwner)) return true;
            if (!isSubclassOf(c, owner)) continue;
            if (!isVariableOrNamedFunction || dispatchReceiver == null || fits(dispatchReceiver.type(), c)) return true;
        }
        return false;
    }

    /** For the type of a companion object, the class it is the companion of. */
    private static ClassSymbol companionOwner(KType type) {
        if (Types.lowerBound(type) instanceof KType.ClassType c && c.symbol().has(Modifier.COMPANION)) {
            return c.symbol().outer();
        }
        return null;
    }

    /** Whether a value of the type is an instance of the class. */
    private static boolean fits(KType type, ClassSymbol cls) {
        return switch (type) {
            case KType.ClassType c -> isSubclassOf(c.symbol(), cls);
            case KType.Flexible f -> fits(f.lower(), cls);
            case KType.DefinitelyNotNull d -> fits(d.inner(), cls);
            case KType.TypeParamType p -> p.symbol().bounds().stream().anyMatch(b -> fits(b, cls));
            case KType.Intersection i -> i.components().stream().anyMatch(c -> fits(c, cls));
            case KType.Captured c -> c.constructor().supertypes() != null
                    && c.constructor().supertypes().stream().anyMatch(s -> fits(s, cls));
            default -> false;
        };
    }

    static boolean isSubclassOf(ClassSymbol sub, ClassSymbol sup) {
        return isSubclassOf(sub, sup, new HashSet<>());
    }

    private static boolean isSubclassOf(ClassSymbol sub, ClassSymbol sup, Set<ClassSymbol> seen) {
        if (sub == sup) return true;
        if (!seen.add(sub)) return false;
        for (KType st : sub.supertypes()) {
            if (st instanceof KType.ClassType c && isSubclassOf(c.symbol(), sup, seen)) return true;
        }
        return false;
    }

    private static boolean isJava(ClassSymbol cls) {
        return cls != null && (cls.origin() == Origin.JAVA_SOURCE || cls.origin() == Origin.JAVA_BINARY);
    }

    private static boolean samePackage(Symbol symbol, Context ctx) {
        return packageOf(symbol).equals(ctx.file().packageName());
    }

    private static String packageOf(Symbol symbol) {
        return switch (symbol) {
            case CallableSymbol c when c.owner() instanceof PackageSymbol p -> p.name();
            case CallableSymbol c when c.owner() instanceof ClassSymbol cls -> cls.id().packageName();
            case ClassSymbol c -> c.id().packageName();
            case TypeAliasSymbol a -> a.id().packageName();
            default -> "";
        };
    }
}
