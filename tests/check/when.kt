package check.whens

fun inConditions(x: Int, list: List<Long>) {
    val a = when (x) {
        in 0..16 -> "low"
        !in list.map { it.toInt() } -> "absent"
        else -> "other"
    }
}
