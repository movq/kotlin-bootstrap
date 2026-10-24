package test.classes

class Plain
open class WithPrimary(val x: Int, var y: String?, z: Long)
abstract class WithSecondary {
    constructor(a: Int)
    protected constructor(a: String)
    abstract fun f(): Int
    open val p: Int get() = 1
    private var q: Int = 0
        private set
    internal lateinit var r: String
}
interface I<in T> {
    fun g(t: T)
    fun h(): Int = 0
    val v: String
}
sealed class Sealed
object Obj : I<String> {
    override fun g(t: String) {}
    override val v: String get() = ""
}
class Outer<T> {
    inner class Inner<S>(val s: S, val t: T)
    class Nested
    fun make(): Inner<Int> = TODO()
    companion object {
        const val C: Int = 1
    }
}
annotation class Ann(vararg val names: String, val n: Int = 0)
class Vararg(vararg val xs: Int, vararg val ys: Outer<*>)
