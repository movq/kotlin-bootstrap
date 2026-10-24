package test.errors

fun unresolved(x: NoSuchType) {}
fun wrongArgs(x: List<Int, Int>) {}
fun inferred() = 42
val inferredProp = "x"
class Ok(val x: Int)
class BadSuper : Missing()
