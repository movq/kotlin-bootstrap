package check.pcla

fun r(vararg s: String) {}

fun spread(b: Boolean) {
    r(*buildList {
        add("a")
        if (b) { add("b") }
    }.toTypedArray())
}

fun loop(n: Int) {
    val x = buildList {
        for (i in 0 until n) add(if (i == 0) "a" else "b")
    }
}

fun local() {
    val x = buildList {
        add(1)
        val first = get(0)
        add(first + 1)
    }
}

fun nested() {
    val x = buildMap {
        put("a", buildList { add(1) })
        listOf(2, 3).forEach { put(it.toString(), listOf(it)) }
    }
}

fun receiver() {
    val x = buildList {
        add("a")
        this.let { it.add("b") }
    }
}

fun string() = buildString {
    append("a")
    listOf(1).forEach { append(it) }
}

fun set() = buildSet {
    add(1)
    addAll(listOf(2, 3))
}

fun s1() = sequence {
    yield(1)
    yieldAll(listOf(2, 3))
}

fun s2(): Sequence<String> = sequence {
    for (i in 0..3) yield(i.toString())
}

fun m1(keys: List<String>) = buildMap {
    for (k in keys) getOrPut(k) { mutableListOf<Int>() }.add(1)
}

fun l1() = buildList {
    if (isEmpty()) add(1)
    val n = size
    add(n)
}

fun l2(xs: List<String>?) = buildList {
    xs?.let { addAll(it) }
    add("x")
}

fun l3() = buildList {
    add(listOf(1))
    for (x in this) println(x.size)
}

fun l4(): List<Pair<String, Int>> = buildList {
    add("a" to 1)
    sortBy { it.second }
}

fun l5() = buildList {
    add("a")
    val last = lastOrNull()
    if (last != null) add(last + "b")
}

fun l6() = buildList<String> {
    add("a")
    forEach { println(it.length) }
}

fun i1() = iterator {
    yield("a")
}

// The not fixed R stands for a stub type in the common supertype of the branches: O, not O?.
inline fun <R> downlevel(block: () -> R): R = block()
fun <O> takeO(o: O) {}

fun <O> branches(c: Boolean, o: O) {
    takeO<O>(if (c) o else downlevel { o })
}

// Only a lambda with a variable in a type argument of an input type is analyzed with PCLA;
// one with just T waits for it to be inferred from the other.
class Member { val operator = true }
fun processMembers(p: (Member) -> Unit) { p(Member()) }
fun <T> findFirst(condition: (T) -> Boolean, processMembers: ((T) -> Unit) -> Unit): T? = null
fun firstOperator() = findFirst({ it.operator }, { processMembers(it) })

// In a lambda analyzed with PCLA, an assignment constrains the variables.
class Slot<T> { var value: T? = null }
fun <T> fill(f: (Slot<T>) -> Unit): T = TODO()
var stringSlot: Slot<String>? = null
fun assignedSlot() = fill { stringSlot = it }

// A lambda stored in a PCLA lambda has its own type, and an if without else in it is of Unit.
fun processEach(xs: List<String>, ys: List<String>) = buildMap {
    for (k in 1..2) {
        val process = { d: String ->
            if (d.isNotEmpty()) {
                put(d, k)
            }
        }
        xs.forEach(process)
        ys.forEach(process)
    }
}
fun takeInt(x: Int) = x
fun <T> collect(f: MutableList<T>.() -> Unit): T = TODO()
fun storedLength() = collect { add(1); val length = { d: String -> d.length }; takeInt(length("")) }
