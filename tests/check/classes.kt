package check.classes

fun g(s: String): Int = 0
fun g(a: Any?): Long = 0L

// The type arguments of the superclass's constructor are those of the supertype.
open class Base<T>(val value: T, val label: String = "base") {
    constructor(value: T, times: Int) : this(value, "x".repeat(times))
}

// Defaults see the parameters before them; the super call sees all of them.
class Derived(a: Any?, b: String = a.toString(), c: Int = b.length) : Base<String>(b, c) {
    val d: Int
    init {
        // The parameters, as in initializers.
        val e = g(a)
        d = c + e.toInt()
    }
}

// Secondary constructors only: each calls the superclass's.
class Secondaries : Base<Int> {
    constructor(x: Int) : super(x, "s")
    constructor(s: String) : this(s.length) {
        val n = g(s)
    }
}

// Implicitly, Any().
class Implicit {
    constructor(x: Int) {
        val y = g(x)
    }
}

// A Java superclass, with overloaded constructors.
class Failure(message: String, cause: Throwable?) : Exception(message, cause) {
    constructor(message: String) : this(message, null)
}

fun f(x: Int = 1, y: Any? = x, z: String = if (y is String) y else "") {}

// A supertype delegate, with the primary constructor's parameters.
class Wrapper(list: List<String>) : List<String> by list.subList(0, 1)

enum class Color(val rgb: Int) {
    RED(0xff0000), GREEN(rgb = 0x00ff00), BLUE;

    constructor() : this(0)
}

object Singleton : Base<Long>(1L, 2)

open class Outer(val n: Int) {
    open inner class Inner(val m: Int)

    // The outer instance of the inner superclass is this Outer's.
    inner class SubInner : Inner(n)
}

class OtherOuter : Outer(1) {
    // The outer instance is OtherOuter's, as an Outer.
    inner class Sub : Inner(2)
}

annotation class Tags(val names: Array<String> = [], val ids: IntArray = [1, 2])

// A local class's bodies see the smart cast where it is declared, as x is not assigned after.
fun local(p: Any?) {
    var x: Any? = p
    if (x is String) {
        class Local(val s: Int = g(x)) : Base<Int>(g(x)) {
            init {
                val i = g(x)
            }
        }
        val o = object : Base<Int>(g(x), "o") {}
    }
}

// Assigned after it, x is unstable in a local class.
fun localUnstable(p: Any?) {
    var x: Any? = p
    if (x is String) {
        class Local(val s: Long = g(x))
    }
    x = 1
}
