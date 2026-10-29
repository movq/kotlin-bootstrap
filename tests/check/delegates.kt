package check.delegates

import kotlin.properties.Delegates
import kotlin.reflect.KProperty

class Box<T>(val v: T) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = v
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {}
}
class Provider { operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): Box<String> = Box("") }
class Generic { operator fun <T> getValue(thisRef: Any?, property: KProperty<*>): List<T> = listOf() }

val lazyInt by lazy { 1 }
val lazyList by lazy { listOf("a") }
val declaredType: Number by lazy { 2 }
var notNull: Int by Delegates.notNull()
var inferredVar by Box(3)
val provided by Provider()
val String.extension by lazy { 4L }
val fromExpectedType: List<String> by Generic()
val mapped: String by mapOf("mapped" to "x")
fun readsDelegated() { val a = lazyInt + inferredVar }
fun local() {
    val a by lazy { "a" }
    var b: Int by Delegates.notNull()
    b = a.length
    val c = a + b
}
fun localsAreNotSmartCast(x: Any) {
    val y by lazy<Any> { x }
    if (y is String) y.length
}
