package check.same2

// Equal syntax at the same place in two files: each is checked on its own.
class Items {
    val items: List<Byte>
        field = mutableListOf()
    fun add(x: Byte) { items.add(x) }
}
