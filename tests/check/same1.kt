package check.same1

// Equal syntax at the same place in two files: each is checked on its own.
class Items {
    val items: List<Long>
        field = mutableListOf()
    fun add(x: Long) { items.add(x) }
}
