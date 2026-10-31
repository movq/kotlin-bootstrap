package check.namebased

class Layer<out T>(val value: T, val exits: List<String>)

// With parentheses by name, renaming with =; with square brackets by position.
fun destructure(layer: Layer<Int>, pair: Pair<String, Long>, map: Map<String, Int>) {
    val (exits, v = value) = layer
    val (first, second) = pair
    val [a, b] = pair
    var (value) = layer
    value = 1
    for ((key, _ = value) in map) {
    }
    for ([index, element] in exits.withIndex()) {
    }
    map.forEach { [k, n] -> }
}

data class Point(val x: Int, val y: Int)

// The full form is by name whatever the setting.
fun fullForm(p: Point) {
    (val y, var x) = p
    [val first, val second] = p
}
