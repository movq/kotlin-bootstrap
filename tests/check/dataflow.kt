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
