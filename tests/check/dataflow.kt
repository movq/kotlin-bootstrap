package check.dataflow

class S(val d: List<String>? = null)

fun <T> take(a: List<T>, f: (T) -> Unit) {}

fun safeCallArgument(d: List<String>?) {
    d?.plus(d.size)
}

fun safeCallLambda(d: List<String>?) {
    d?.let { take(d) { } }
}

fun implicitReceiverProperty(s: S) {
    with(s) {
        d?.let { take(d) { } }
    }
}

fun condition(d: List<String>?) {
    if (d != null) run { d.size }
}

// break and continue in an inline lambda leave the loop around it.
fun countUntilPositive(xs: List<List<Int>>): Int {
    var n = 0
    outer@ for (x in xs) {
        x.forEach { if (it > 0) continue@outer; if (it < -5) break }
        n++
    }
    return n
}

// A property getter's contract smart casts after the property is read.
sealed class Mode { class Typed(val type: String) : Mode(); object Untyped : Mode() }
@OptIn(kotlin.contracts.ExperimentalContracts::class)
val Mode.isTyped: Boolean
    get() {
        kotlin.contracts.contract { returns(true) implies (this@isTyped is Mode.Typed) }
        return this is Mode.Typed
    }
fun typeLength(m: Mode) = if (m.isTyped) m.type.length else 0

// A contract's type is of the call's type arguments.
sealed class Result<D : Any> { class Some<D : Any>(val value: D) : Result<D>(); class None<D : Any> : Result<D>() }
@OptIn(kotlin.contracts.ExperimentalContracts::class)
fun <D : Any> Result<D>.isSome(): Boolean {
    kotlin.contracts.contract { returns(true) implies (this@isSome is Result.Some<D>) }
    return this is Result.Some
}
fun <D : Any> Result<D>.valueOrNull(): D? = if (isSome()) value else null

// An inline function calls its lambda in place: a smart cast after it is stable.
open class Node
class Leaf : Node() { fun weight() = 1 }
inline fun List<Int>.each(f: (Int) -> Unit) { for (x in this) f(x) }
fun lastLeafWeight(xs: List<Int>): Int {
    var last: Node? = null
    xs.each { last = if (it > 0) Leaf() else Node() }
    return if (last is Leaf) last.weight() else 0
}
