package check.equivalent

// The same top-level function, as in two modules: one is called.
fun twice(x: Int): Int = x * 2

fun callTwice(): Int = twice(1)

// Non-public classifiers of the same name, as in two modules, are not redeclarations.
internal typealias Ints = List<Int>

fun sum(ints: Ints): Int = ints.sum()
