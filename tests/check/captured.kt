package check.captured

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

// g(String) where x is smart cast to String, g(Any?) where it is unstable.
fun g(s: String): Int = 0
fun g(a: Any?): Long = 0L

@OptIn(ExperimentalContracts::class)
fun both(a: () -> Unit, b: () -> Unit) {
    contract {
        callsInPlace(a)
        callsInPlace(b)
    }
    a()
    b()
}

fun <T> pair(a: T, b: Any?) {}

fun lambdaAssigns(p: Any?) {
    var x: Any? = p
    if (x is String) { val r = g(x) }
    val f = { x = 1 }
    if (x is String) { val r = g(x) }
}

fun assignedAfterLambda(p: Any?) {
    var x: Any? = p
    if (x is String) {
        val f = { val r = g(x) }
    }
    x = 1
}

fun inPlace(p: Any?) {
    var x: Any? = p
    if (x is String) run { val r = g(x) }
    x = 1
}

fun inOtherBranch(p: Any?, c: Boolean) {
    var x: Any? = p
    if (x is String) {
        if (c) {
            val f = { val r = g(x) }
        } else {
            x = 1
        }
    }
}

fun preservesType(p: Any?) {
    var x: Any? = p
    val f = { x = "a" }
    if (x is String) { val r = g(x) }
}

fun localFunction(p: Any?) {
    var x: Any? = p
    fun reset() { x = null }
    if (x is String) { val r = g(x) }
}

fun concurrentLambdas(p: Any?) {
    var x: Any? = p
    if (x is String) both({ val r = g(x) }, { x = 1 })
}

fun dataFlowPostponed(p: Any?) {
    var x: Any? = p
    if (x is String) pair(run { x = 1; 2 }, g(x))
}

fun localFunctionReads(p: Any?) {
    var x: Any? = p
    if (x is String) {
        fun read() { val r = g(x) }
    }
    x = 1
}

fun alias(p: Any?) {
    var x: Any? = p
    val y = x
    val f = { x = 1 }
    if (y is String) { val r = g(y) }
}

fun loopIncrement(p: Int?) {
    var i = p
    if (i != null) {
        while (i < 10) i++
    }
}

// A safe call's receiver is not null: Captured(*), not Captured(*)?; T, not T & Any.
interface Node {
    val children: List<Node>
}

interface NodeHolder<S : Node> {
    fun node(): S?
}

fun nodeChildren(h: NodeHolder<*>) = h.node()?.children

fun <T : Any> notNullReceiver(t: T?) = t?.let { it }

// A flexible type parameter is captured from the bounds of its lower bound, not null.
interface Sym<T> { val ok: Boolean }
fun Sym<*>.isOk(): Boolean = ok
fun <S : Sym<*>> flexibleReceiver(set: MutableSet<S>) { set.removeIf { s -> s.isOk() } }

// Captured types alone, unlike type variables, have a common supertype.
abstract class SelfContext<C : SelfContext<C>> { fun replaced(x: Int): C = TODO() }
fun eitherContext(outer: SelfContext<*>, b: Boolean) {
    val context = if (b) outer.replaced(1) else outer.replaced(2)
    context.replaced(3)
}
