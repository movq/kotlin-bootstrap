package check.annotations

import kotlin.reflect.KClass

annotation class Level(val value: Int)
annotation class Names(vararg val names: String)
annotation class Nested(val level: Level, val kinds: Array<KClass<*>> = [])
enum class Color { RED, GREEN }
annotation class Colored(val color: Color)
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
annotation class JavaRetained

const val BASE = 10
const val NAME = "n" + BASE
const val SHIFTED = (BASE shl 2) + 'a'.code - 1
const val LONG = Int.MAX_VALUE.toLong() * 2
const val TEXT = "$NAME:${BASE + 1}"
const val CHAR = 'a' + 2
const val NEG = -BASE / 3
const val DBL = 1.5 * BASE
const val CMP = BASE > 5 && NAME == "n10"
val notConst = 3

@Level(BASE * 2) fun a() {}
@Names("a", NAME) fun b() {}
@Names(names = ["x", "y"]) fun c() {}
@Names(*arrayOf("p", "q")) fun d() {}
@Nested(Level(1), kinds = [String::class, Int::class]) fun e() {}
@Colored(Color.GREEN) fun f() {}
@Deprecated("old", ReplaceWith("g()", imports = ["kotlin.math.abs"]), DeprecationLevel.WARNING) fun g() {}
@Suppress("UNUSED") fun suppressed() { @Suppress("UNCHECKED_CAST") val x = 1 }
@JvmName("renamed") fun named() {}
@Level(notConst) fun nonConst() {}

@Level(1) class Annotated @Level(2) constructor(@Level(3) val x: Int) {
    companion object { const val INNER = 5 }
    @Level(INNER) fun member() {}
}

