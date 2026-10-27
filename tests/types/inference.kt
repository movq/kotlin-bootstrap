package types.inference

open class A
class B : A()
class C : A()
class Inv<T>

fun <T> infer(p: T, a: Int): T {}
fun <T> infer(p1: T, a1: Int, p2: T, a2: String): T {}
fun <T> infer(p1: T, a1: B, p2: T, a2: C): T {}
fun <T> infer(p: List<T>, a: List<String>): T {}
fun <T> infer(p: T?, a: String?): T {}
fun <T> infer(p: T, a: String?): T {}
fun <T : Comparable<T>> infer(p: T, a: Int): T {}
fun <T> infer(expected: String): T {}
fun <T> infer(p: T, a: Int, expected: Any?): T {}
fun <T> infer(p: T, a: B, expected: A): T {}
fun <T> infer(): T {}
fun <E : Enum<E>> infer(): E {}
fun <T> infer(p: MutableList<T>, a: MutableList<out Number>): T {}
fun <T> infer(p: Inv<T>, a: Inv<String>, p2: T, a2: Int): T {}
fun <T, R> infer(p1: T, a1: Int, p2: (T) -> R, a2: (Any) -> String): R {}
fun <T> infer(p: List<T>, a: List<Nothing>): T {}
fun <T> infer(p: T, a: Nothing?): T {}
fun <K, V> infer(p: Map<K, V>, a: Map<String, Int>): Pair<K, V> {}
fun <T, S : T> infer(p: S, a: String): T {}
fun <T, S : T> infer(p: S, a: String, expected: CharSequence): T {}
fun <T : CharSequence> infer(p: List<T>, a: List<String>): T {}
inline fun <reified T> infer(expected: String?): T {}
fun <T> infer(p1: T, a1: String, p2: Comparable<T>, a2: Comparable<CharSequence>): T {}
fun <T> infer(p1: In<T>, a1: In<B>, p2: In<T>, a2: In<C>): T {}
fun <T> infer(p: Array<T>, a: Array<out A>): T {}

class In<in T>
