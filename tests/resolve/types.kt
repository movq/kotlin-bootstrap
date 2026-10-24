package test.types

typealias Pred<T> = (T) -> Boolean
typealias StringMap<V> = Map<String, V>

fun functionTypes(
    a: () -> Unit,
    b: (Int, String) -> Long?,
    c: Int.(String) -> Unit,
    d: suspend () -> Int,
    e: suspend String.() -> Unit,
    f: ((Int) -> Int)?,
    g: (() -> Unit).() -> Unit,
    h: @ExtensionFunctionType Function2<Int, Int, Unit>,
): Pred<String> = TODO()

fun aliases(m: StringMap<Int>, p: Pred<Pred<Int>>): Unit {}
fun projections(a: List<*>, b: MutableList<out Number>, c: Comparable<in String>, d: Array<out Any?>) {}
fun <T> definitelyNotNull(t: T & Any): T = t
fun <T : Comparable<T>> bounded(x: T) where T : CharSequence {}
inline fun <reified T> reified(): T = TODO()
context(s: String) fun withContext(): Int = 0
fun withContextType(f: context(String) () -> Unit) {}
val Int.ext: Int get() = this
var <T> MutableList<T>.second: T
    get() = this[1]
    set(value) { this[1] = value }
fun javaTypes(f: java.io.File, l: java.util.ArrayList<String>): java.lang.Thread = TODO()
