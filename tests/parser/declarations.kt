@file:JvmName("Decls")
package p

import a.b.C
import a.b.*

data class Point(val x: Int, val y: Int = 0) : Comparable<Point> {
    override fun compareTo(other: Point): Int = x - other.x
}

sealed interface Shape

enum class Color(val rgb: Int) {
    RED(0xff0000), GREEN(0x00ff00) {
        override fun toString() = "g"
    };
    abstract class Nested
}

abstract class Base<in T, out R : Any>() where T : Comparable<T> {
    abstract val p: R?
    var q: Int = 0
        private set
    val r by lazy { 1 }
    lateinit var s: String
    constructor(x: Int) : this()
    init { println() }
}

typealias Fn<T> = suspend T.(Int) -> Unit

inline fun <reified T> Any.cast(): T = this as T

val <T> List<T>.second: T get() = this[1]

fun vararg(vararg xs: Int, f: (Int, String) -> Boolean = { _, _ -> true }) {}

context(c: Ctx)
fun withContext() {}

object Singleton : Base<Int, String>(1)

expect class Platform
