package check.references

import java.io.File

fun twice(x: Int): Int = x * 2
fun withDefault(x: Int, y: Int = 3): Int = x + y
fun many(vararg xs: String): Int = xs.size
fun unit(x: Int) {}
class Box<T>(var value: T)

fun memberOfType() { val a = listOf("a", " ").filter(String::isNotBlank) }
fun topLevel() { val a = listOf(1, 2).map(::twice) }
fun withDefaultAdapted() { val a = listOf(1).map(::withDefault) }
fun constructor() { val a = listOf("x").map(::File) }
fun javaStatic() { val a = "1.5".let(java.lang.Float::parseFloat) }
fun property() { val a = listOf("a").map(String::length) }
fun varargAsArray() { val a = listOf(arrayOf("a")).map(::many) }
fun varargElement() { val a = listOf("a").map(::many) }
fun genericType() { val a = listOf(listOf(1)).map(List<Int>::size) }
fun boundMutable() { val a = Box(1)::value }
fun noExpectedType() { val a = ::twice }
fun commonSupertype() { val a = if (true) ::twice else ::withDefault }
fun commonSupertypeUnit() { val a = if (true) ::twice else ::unit }
fun coercionToUnit() { listOf(1).forEach(::twice) }
