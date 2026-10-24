package kotlinbootstrap.ast;

import java.util.List;

import kotlinbootstrap.syntax.SourceFile;

/**
 * A parsed Kotlin source file.
 *
 * @param annotations  {@code @file:} annotations
 * @param packageName  package name segments, empty for the root package
 */
public record KtFile(SourceFile source, List<Annotation> annotations, List<String> packageName,
                     List<Import> imports, List<Decl> declarations) {
    /**
     * @param allUnder {@code import a.b.*}
     * @param alias    {@code import a.b as c}, nullable
     */
    public record Import(int pos, List<String> path, boolean allUnder, String alias) implements Node {
    }
}
