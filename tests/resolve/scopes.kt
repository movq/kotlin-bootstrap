package test.scopes

import java.util.*
import java.lang.reflect.Array as JArray
import kotlin.collections.List as KList

// Array is the builtin even though java.lang.reflect.* would also be visible here;
// explicit star imports beat default imports, so Date is java.util.Date.
fun f(a: Array<Int>, j: JArray, l: KList<Int>, d: Date) {}

class Map
// Same package beats default imports.
fun g(m: Map) {}

interface Container {
    class Item
}
class Impl : Container {
    // Nested classes of supertypes are visible in the body.
    fun item(): Item = TODO()
    companion object : Container {
        fun item2(): Item = TODO()
    }
}

class Shadow<T> {
    // The function's T shadows the class's.
    fun <T> f(t: T): T = t
    class NotInner {
        // A nested class does not see the outer T.
        fun g(t: T) {}
    }
}
