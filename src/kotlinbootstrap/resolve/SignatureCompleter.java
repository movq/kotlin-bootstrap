package kotlinbootstrap.resolve;

import java.util.LinkedHashSet;
import java.util.Set;

import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.symbols.CallableSymbol;
import kotlinbootstrap.symbols.ClassSymbol;
import kotlinbootstrap.symbols.ClassifierSymbol;
import kotlinbootstrap.symbols.FunctionSymbol;
import kotlinbootstrap.symbols.MemberScope;
import kotlinbootstrap.symbols.PropertySymbol;
import kotlinbootstrap.symbols.TypeAliasSymbol;
import kotlinbootstrap.symbols.TypeParamSymbol;

/**
 * Resolves every type in every declaration signature of the Kotlin sources,
 * which otherwise happens lazily. Types that must be inferred from bodies
 * are skipped. Stops at the first error.
 */
public final class SignatureCompleter {
    private SignatureCompleter() {
    }

    /** Returns the number of classifiers completed. */
    public static int completeAll(Resolver resolver) {
        int count = 0;
        for (ClassifierSymbol c : resolver.table().kotlinClassifiers().values()) {
            complete(c);
            count++;
        }
        Set<String> packages = new LinkedHashSet<>();
        for (FileContext file : resolver.files()) packages.add(file.packageName());
        for (String pkg : packages) completeCallables(resolver.table().packageScope(pkg));
        return count;
    }

    private static void complete(ClassifierSymbol c) {
        switch (c) {
            case ClassSymbol cls -> {
                for (TypeParamSymbol tp : cls.typeParams()) tp.bounds();
                cls.supertypes();
                completeCallables(cls.members());
                for (FunctionSymbol ctor : cls.members().constructors()) complete(ctor);
            }
            case TypeAliasSymbol alias -> {
                for (TypeParamSymbol tp : alias.typeParams()) tp.bounds();
                alias.expansion();
            }
            case TypeParamSymbol tp -> tp.bounds();
        }
    }

    private static void completeCallables(MemberScope scope) {
        scope.functions().values().forEach(fs -> fs.forEach(SignatureCompleter::complete));
        scope.properties().values().forEach(ps -> ps.forEach(SignatureCompleter::complete));
    }

    private static void complete(CallableSymbol c) {
        for (TypeParamSymbol tp : c.typeParams()) tp.bounds();
        c.signature();
        if (hasDeclaredType(c)) c.returnType();
    }

    private static boolean hasDeclaredType(CallableSymbol c) {
        return switch (c.source()) {
            case Decl.Function f -> f.returnType() != null || !(f.body() != null && !(f.body() instanceof Expr.Block));
            case Decl.Property p -> p.type() != null || p.getter() != null && p.getter().returnType() != null;
            default -> c instanceof FunctionSymbol || c instanceof PropertySymbol;
        };
    }
}
