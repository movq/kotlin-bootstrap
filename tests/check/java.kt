package check.java

interface KHolder {
    val accessor: Any
}

// A Java getter overriding a Kotlin property makes a property of its type.
fun javaOverrides(h: HolderImpl, k: KHolder): Boolean {
    val a = h.accessor
    val b = k.accessor
    return a.isDefault
}
