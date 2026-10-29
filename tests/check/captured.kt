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
