package check.mapped

fun initCause(e: Exception) { val a = e.initCause(null) }
fun suppressed(e: Exception) { val a = e.suppressed }
fun localizedMessage(e: Throwable) { val a = e.localizedMessage }
fun stream(l: List<Int>) { val a = l.stream() }
fun removeIf(l: MutableList<Int>) { val a = l.removeIf { true } }
fun declaredInKotlin(m: Map<String, Int>) { val a = m.getOrDefault("a", 1) }
fun javaSubclass(m: HashMap<String, Int>) { val a = m.computeIfAbsent("a") { 1 } }
fun charSequence(s: java.lang.StringBuilder) { val a = s.chars() }
fun hidesMembers(l: List<String>) { l.forEach { it.length } }
fun hiddenUsesExtension(s: String) { val a = s.isBlank() }
fun readOnlyHasNoRemoveIf(l: List<Int>) { l.removeIf { true } }
fun renamed(n: Number) { n.intValue() }
fun hiddenOverride(a: ArrayList<Int>) { a.sort(null) }

// Throwable's protected four-argument constructor, mapped from java.lang.Throwable.
class Quiet(message: String) : Throwable(message, null, false, false)
class Plain(message: String) : Throwable(message)
