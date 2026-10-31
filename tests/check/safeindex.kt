package check.safeindex

class Box(val list: MutableList<Int>, val map: Map<String, Int>)

fun takeIntOrNull(x: Int?) = x

// An index on a safe call is inside it: b?.list[0] is b?.(list[0]).
fun get(b: Box?) = takeIntOrNull(b?.list[0])
fun getMap(b: Box?) = takeIntOrNull(b?.map["x"])
fun set(b: Box?) { b?.list[0] = 1 }
fun augmented(b: Box?) { b?.list[0] += 1 }
fun increment(b: Box?) = takeIntOrNull(b?.list[0]++)
