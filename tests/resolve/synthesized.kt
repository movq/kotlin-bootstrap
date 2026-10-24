package test.synthesized

data class Point(val x: Int, val y: Int)
data class Named(val name: String) {
    override fun toString(): String = name
}
@JvmInline
value class Meters(val value: Double)
enum class Color(val rgb: Int) {
    RED(0xff0000), GREEN(0x00ff00);
    fun brighter(): Color = this
}
data object Singleton
