package kotlinbootstrap.symbols;

/**
 * The name of a class: its package, and its name relative to the package
 * (dotted for nested classes). The split matters because {@code a.b.C.D}
 * may be class {@code D} nested in {@code a.b.C}, or class {@code C.D} in
 * package {@code a.b.C}... and only knowing which tells us how to find it.
 *
 * @param packageName dotted, empty for the root package
 * @param relativeName dotted, e.g. {@code Map.Entry}
 */
public record ClassId(String packageName, String relativeName) {
    public static ClassId topLevel(String fqName) {
        int dot = fqName.lastIndexOf('.');
        return dot < 0 ? new ClassId("", fqName) : new ClassId(fqName.substring(0, dot), fqName.substring(dot + 1));
    }

    public ClassId nested(String name) {
        return new ClassId(packageName, relativeName + "." + name);
    }

    /** The containing class, or null for a top-level class. */
    public ClassId outer() {
        int dot = relativeName.lastIndexOf('.');
        return dot < 0 ? null : new ClassId(packageName, relativeName.substring(0, dot));
    }

    public String shortName() {
        return relativeName.substring(relativeName.lastIndexOf('.') + 1);
    }

    public String fqName() {
        return packageName.isEmpty() ? relativeName : packageName + "." + relativeName;
    }

    @Override
    public String toString() {
        return fqName();
    }
}
