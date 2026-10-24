package kotlinbootstrap;

import java.io.IOException;
import java.nio.file.Path;

import kotlinbootstrap.ast.AstPrinter;
import kotlinbootstrap.syntax.Parser;
import kotlinbootstrap.syntax.SourceFile;
import kotlinbootstrap.syntax.SyntaxError;

/** Command line entry point. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || !args[0].equals("parse")) {
            System.err.println("usage: kbc parse <file.kt>...");
            System.exit(2);
        }
        int failures = 0;
        for (int i = 1; i < args.length; i++) {
            SourceFile file = SourceFile.read(Path.of(args[i]));
            try {
                System.out.print(AstPrinter.print(Parser.parse(file)));
            } catch (SyntaxError e) {
                System.err.println(e);
                failures++;
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}
