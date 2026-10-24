package types.subtyping

open class A
open class B : A()
class Inv<T>
class Out<out T>
class In<in T>
interface Self<T : Self<T>>
class SelfImpl : Self<SelfImpl>
abstract class StringList : AbstractList<String>(), MutableList<String>

fun subtype(a: B, b: A) {}
fun subtype(a: A, b: B) {}
fun subtype(a: B?, b: A) {}
fun subtype(a: B, b: A?) {}
fun subtype(a: Nothing, b: B) {}
fun subtype(a: Nothing?, b: B?) {}
fun subtype(a: Nothing?, b: B) {}
fun subtype(a: A, b: Any) {}
fun subtype(a: A?, b: Any) {}

// Variance
fun subtype(a: Inv<B>, b: Inv<A>) {}
fun subtype(a: Inv<B>, b: Inv<out A>) {}
fun subtype(a: Inv<A>, b: Inv<in B>) {}
fun subtype(a: Inv<B>, b: Inv<in A>) {}
fun subtype(a: Inv<B>, b: Inv<*>) {}
fun subtype(a: Out<B>, b: Out<A>) {}
fun subtype(a: In<A>, b: In<B>) {}
fun subtype(a: In<B>, b: In<A>) {}
fun subtype(a: Inv<out B>, b: Inv<out A>) {}
fun subtype(a: Inv<out A>, b: Inv<A>) {}
fun subtype(a: Inv<*>, b: Inv<out Any?>) {}
fun subtype(a: Inv<*>, b: Inv<out Any>) {}

// Supertypes with arguments
fun subtype(a: ArrayList<String>, b: Collection<CharSequence>) {}
fun subtype(a: ArrayList<String>, b: MutableCollection<CharSequence>) {}
fun subtype(a: Map<String, B>, b: Map<out CharSequence, A>) {}
fun subtype(a: SelfImpl, b: Self<*>) {}
fun subtype(a: SelfImpl, b: Self<SelfImpl>) {}
fun subtype(a: StringList, b: List<String>) {}
fun subtype(a: StringList, b: List<String?>) {}
fun subtype(a: String, b: Comparable<String>) {}
fun subtype(a: Int, b: Comparable<Int>) {}
fun subtype(a: Int, b: Number) {}

// Type parameters
fun <T> subtype(a: T, b: Any) {}
fun <T> subtype(a: T, b: Any?) {}
fun <T : Any> subtype(a: T, b: Any) {}
fun <T> subtype(a: T & Any, b: Any) {}
fun <T> subtype(a: T & Any, b: T) {}
fun <T> subtype(a: T, b: T & Any) {}
fun <T> subtype(a: T, b: T?) {}
fun <T : CharSequence> subtype(a: T, b: CharSequence) {}
fun <T : CharSequence?> subtype(a: T, b: CharSequence) {}
fun <T : CharSequence?> subtype(a: T, b: CharSequence?) {}
fun <T, S : T> subtype(a: S, b: T) {}
fun <T, S : T> subtype(a: T, b: S) {}
fun <T : List<String>> subtype(a: T, b: Collection<CharSequence>) {}
fun <T> subtype(a: Nothing, b: T) {}
fun <T> subtype(a: Nothing?, b: T) {}

// Flexible types from Java
fun subtype(a: java.lang.StringBuilder, b: CharSequence) {}

// Function types
fun subtype(a: (A) -> B, b: (B) -> A) {}
fun subtype(a: (B) -> A, b: (A) -> B) {}
fun subtype(a: A.() -> Unit, b: (A) -> Unit) {}
fun subtype(a: () -> Unit, b: Function<Unit>) {}
fun subtype(a: suspend () -> Unit, b: Function<Unit>) {}

fun equal(a: Inv<A>, b: Inv<A>) {}
fun equal(a: List<A>, b: List<B>) {}
fun <T> equal(a: T, b: T?) {}
