package kotlinbootstrap.symbols;

/** A source of classes, such as the JDK or the Java sources being compiled. */
public interface ClassFinder {
    /** The class with this id, or null. */
    ClassSymbol findClass(ClassId id);

    boolean packageExists(String packageName);
}
