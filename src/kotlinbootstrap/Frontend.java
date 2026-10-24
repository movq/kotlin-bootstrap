package kotlinbootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import kotlinbootstrap.ast.KtFile;
import kotlinbootstrap.java.JavaSourceClasses;
import kotlinbootstrap.java.JdkClasses;
import kotlinbootstrap.resolve.Resolver;
import kotlinbootstrap.symbols.SymbolTable;
import kotlinbootstrap.syntax.Parser;
import kotlinbootstrap.syntax.SourceFile;

/** Sets up resolution of a set of Kotlin and Java sources. */
public final class Frontend {
    private Frontend() {
    }

    /**
     * Parses the sources and enters their declarations.
     *
     * @param paths      .kt and .java files, and directories to search for them
     * @param jdkRelease the Java release whose API to compile against, e.g. "8"
     */
    public static Resolver load(List<Path> paths, String jdkRelease) throws IOException {
        SymbolTable table = new SymbolTable();
        Resolver resolver = new Resolver(table);
        table.addFinder(new JavaSourceClasses(table, sourceFiles(paths, ".java")));
        table.addFinder(new JdkClasses(table, jdkRelease));
        resolver.enter(parseAll(sourceFiles(paths, ".kt")));
        return resolver;
    }

    /**
     * The files with the extension among the paths and under the directories
     * among them, in a stable order.
     */
    public static List<Path> sourceFiles(List<Path> paths, String extension) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    files.filter(f -> f.toString().endsWith(extension)).sorted().forEach(result::add);
                }
            } else if (path.toString().endsWith(extension)) {
                result.add(path);
            }
        }
        return result;
    }

    public static List<KtFile> parseAll(List<Path> files) throws IOException {
        List<KtFile> result = new ArrayList<>();
        for (Path f : files) result.add(Parser.parse(SourceFile.read(f)));
        return result;
    }
}
