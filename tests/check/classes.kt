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

// Entries with bodies are object literals with the enum class as supertype.
enum class Op(val symbol: String) {
    PLUS("+") {
        override fun apply(a: Int, b: Int) = a + b
    },
    TIMES("*") {
        val unit = 1
        override fun apply(a: Int, b: Int): Int = g(symbol) * a * b * unit
    },
    // The entry is an Op, as an implicit receiver too.
    MINUS("-") {
        override fun apply(a: Int, b: Int) = a - b + negated()
    };

    abstract fun apply(a: Int, b: Int): Int
}

fun Op.negated(): Int = -apply(1, 1)

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

// State is also an invisible type alias of kotlin.sequences, a default import, which resolves
// as a value; as a qualifier the enum, without a companion object, still wins.
private enum class State { BEGINNING, MIDDLE }

private fun nextState(s: State): State = if (s == State.BEGINNING) State.MIDDLE else State.BEGINNING

// Where its explicit backing field is visible, a property reads as the field.
open class Collector {
    val items: List<String>
        field = mutableListOf()

    fun add(item: String) {
        items.add(item)
    }
}

// A vararg overload is not an override of a non-vararg one.
fun checkState(value: Boolean, message: String) {}
fun checkState(value: Boolean, vararg messages: String) {}

fun useCheckState() {
    checkState(true, "a", "b")
}

// Among same-signature members, the one with the more specific return type wins.
fun removeFirst(list: MutableList<String>) {
    val it = list.iterator()
    it.next()
    it.remove()
}

// A default value is inherited from the overridden function.
open class WithDefault {
    open fun f(x: Int = 1): Int = x
}

class OverridesDefault : WithDefault() {
    override fun f(x: Int): Int = x + 1

    operator fun plusAssign(other: Int) {}

    fun g(): Int {
        this += 1
        return f()
    }
}

// Also for an override, where the property is stable.
abstract class MapHolder {
    abstract val byName: Map<String, List<Int>>
}

class MapCollector : MapHolder() {
    override val byName: Map<String, List<Int>>
        field = mutableMapOf<String, MutableList<Int>>()

    @Throws(IllegalStateException::class)
    fun add(name: String) {
        byName.getOrPut(name) { mutableListOf() }.add(1)
    }
}

// An explicit type argument against the bound makes a candidate inapplicable, not ambiguous.
fun <K : Comparable<K>, V> nullsLastMap(): java.util.SortedMap<K?, V> =
    sortedMapOf<K?, V>(nullsLast(compareByDescending { it }))

// A star projection is the same as out the bound in an override's parameters.
interface Recorder {
    fun record(results: MutableList<*>)
}

class ListRecorder : Recorder {
    override fun record(results: MutableList<out Any?>) {}

    fun recordTwice(results: MutableList<String>) {
        record(results)
    }
}

// super<T> may leave out the type arguments of a generic supertype.
interface Counted<F> { fun count(): Int = 1 }
class CountedString : Counted<String> { override fun count(): Int = super<Counted>.count() + 1 }
