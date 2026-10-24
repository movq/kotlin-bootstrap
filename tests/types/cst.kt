package types.cst

open class A
open class B : A()
class C : A()
interface I
interface J
class BI : B(), I, J
class CI : A(), I, J
class Inv<T>
class Out<out T>
class In<in T>

fun cst(a: B, b: C) {}
fun cst(a: B, b: A) {}
fun cst(a: B?, b: C) {}
fun cst(a: BI, b: CI) {}
fun cst(a: Int, b: Long) {}
fun cst(a: Int, b: String) {}
fun cst(a: Int, b: Nothing) {}
fun cst(a: Int, b: Nothing?) {}
fun cst(a: Inv<B>, b: Inv<C>) {}
fun cst(a: Inv<B>, b: Inv<B>) {}
fun cst(a: Inv<out B>, b: Inv<B>) {}
fun cst(a: Inv<in B>, b: Inv<out B>) {}
fun cst(a: Out<B>, b: Out<C>) {}
fun cst(a: In<B>, b: In<C>) {}
fun cst(a: List<B>, b: Set<C>) {}
fun cst(a: List<String>, b: MutableList<String>) {}
fun cst(a: List<List<B>>, b: List<List<C>>) {}
fun <T> cst(a: T, b: T?) {}
fun <T> cst(a: T, b: String) {}
fun <T : Any> cst(a: T, b: String) {}
fun <T : CharSequence> cst(a: T, b: String) {}
fun <T, S : T, R : T> cst(a: S, b: R) {}
fun <E : Enum<E>> cst(a: E, b: Enum<*>) {}
fun cst(a: () -> B, b: () -> C) {}
fun cst(a: (B) -> Unit, b: (C) -> Unit) {}

fun intersect(a: A, b: I) {}
fun intersect(a: B, b: A) {}
fun intersect(a: A?, b: I?) {}
fun intersect(a: A?, b: I) {}
fun <T> intersect(a: T, b: Any) {}
fun intersect(a: Inv<A>, b: Inv<A>) {}

fun capture(a: Inv<*>) {}
fun capture(a: Inv<out CharSequence>) {}
fun capture(a: Inv<in CharSequence>) {}
fun capture(a: Inv<String>) {}
fun <E : Enum<E>> capture(a: Inv<out E>) {}

fun asSupertype(a: ArrayList<String>, b: Collection<*>) {}
fun asSupertype(a: Map<String, Int>, b: Map<*, *>) {}
fun asSupertype(a: Inv<out CharSequence>, b: Inv<*>) {}
fun asSupertype(a: String, b: Comparable<*>) {}
fun asSupertype(a: String, b: List<*>) {}
