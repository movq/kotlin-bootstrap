package types.approximation

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
interface Cmp<in T>
class P : A(), Cmp<P>
class Q : A(), Cmp<Q>
class Bounded<T : CharSequence>

fun localCst(a: BI, b: CI) {}
fun localCst(a: Int, b: Long) {}
fun localCst(a: Int, b: String) {}
fun localCst(a: In<Int>, b: In<Long>) {}
fun localCst(a: In<B>, b: In<C>) {}
fun localCst(a: P, b: Q) {}
fun localCst(a: List<Int>, b: List<Long>) {}
fun localCst(a: (B) -> Unit, b: (C) -> Unit) {}
fun <T, S : T, R : T> localCst(a: S, b: R) {}

fun publicCst(a: BI, b: CI) {}
fun publicCst(a: Int, b: Long) {}
fun publicCst(a: Int, b: String) {}
fun publicCst(a: In<Int>, b: In<Long>) {}
fun publicCst(a: In<B>, b: In<C>) {}
fun publicCst(a: P, b: Q) {}
fun publicCst(a: List<Int>, b: List<Long>) {}
fun publicCst(a: (B) -> Unit, b: (C) -> Unit) {}
fun <T, S : T, R : T> publicCst(a: S, b: R) {}

fun captureApproximate(a: Inv<*>) {}
fun captureApproximate(a: Inv<out CharSequence>) {}
fun captureApproximate(a: Inv<in CharSequence>) {}
fun captureApproximate(a: In<*>) {}
fun captureApproximate(a: Out<*>) {}
fun captureApproximate(a: Bounded<*>) {}
fun captureApproximate(a: Inv<Out<*>>) {}
fun <E : Enum<E>> captureApproximate(a: Inv<out E>) {}
