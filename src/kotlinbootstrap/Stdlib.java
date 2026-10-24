package kotlinbootstrap;

import java.nio.file.Path;
import java.util.List;

/**
 * The sources of the JVM standard library (kotlin-stdlib with its jdk7 and
 * jdk8 parts), as configured in libraries/stdlib/build.gradle.kts.
 * {@code jvm/compileOnly} is left out: it declares JDK classes to compile
 * against JDK 6's API, and we read the real JDK.
 */
public final class Stdlib {
    private static final List<String> ROOTS = List.of(
            "common/src", "src", "unsigned/src",
            "jvm/src", "jvm/runtime", "jvm/builtins",
            "jdk7/src", "jdk8/src");

    private Stdlib() {
    }

    /** @param kotlinSource the root of the Kotlin source tree */
    public static List<Path> sourceRoots(Path kotlinSource) {
        Path stdlib = kotlinSource.resolve("libraries/stdlib");
        return ROOTS.stream().map(stdlib::resolve).toList();
    }
}
