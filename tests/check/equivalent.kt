package check.equivalent

// The same top-level function, as in two modules: one is called.
fun twice(x: Int): Int = x * 2

fun callTwice(): Int = twice(1)
