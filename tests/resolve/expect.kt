package test.expect

expect class Platform {
    fun name(): String
}
actual class Platform {
    actual fun name(): String = "jvm"
    fun extra(): Int = 0
}
expect fun platformName(x: Int = 0): String
actual fun platformName(x: Int): String = ""
