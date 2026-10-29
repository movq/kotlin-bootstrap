package check.sam

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

fun interface Pred<T> { fun test(x: T): Boolean }
fun <T> usePred(p: Pred<T>, x: T): Boolean = p.test(x)
fun foo(r: Runnable) = 1
fun foo(f: () -> Unit) = ""
fun bar(c: Callable<Int>) = 1
fun bar(r: Runnable) = ""

fun typeAliasConstructor() { val a = Comparator<String> { a, b -> a.length - b.length } }
fun inferredTypeArgument() { val a = listOf("b", "a").sortedWith(Comparator { a, b -> a.compareTo(b) }) }
fun javaConstructor() { val a = Runnable { } }
fun funInterfaceConstructor() { val a = Pred<Int> { it > 0 } }
fun funInterfaceArgument() { val a = usePred({ x: Int -> x > 0 }, 1) }
fun javaMethodArgument() { val a = ConcurrentHashMap<String, Int>().computeIfAbsent("a") { it.length } }
fun javaConstructorArgument() { val a = Thread { println() } }
fun functionValueArgument() { val f: () -> Unit = {}; val a = Thread(f) }
fun functionTypeWins() { val a = foo { } }
fun noSamWithoutConversion() { val r = Runnable { }; val a = foo(r) }
fun referenceArgument() { val a = Thread(::println) }
