package test.overrides

abstract class Base<T> {
    protected abstract fun hook(t: T): Int
    internal open fun internalHook() {}
    protected open val prop: List<T> get() = emptyList()
}
class Derived : Base<String>() {
    override fun hook(t: String): Int = 0
    override fun internalHook() {}
    public override val prop: List<String> get() = emptyList()
}
// Overrides of protected Java members.
class MyList : java.util.AbstractList<String>() {
    override val size: Int get() = 0
    override fun get(index: Int): String = ""
    override fun removeRange(fromIndex: Int, toIndex: Int) {}
}
class MyRandom : java.util.Random() {
    override fun next(bits: Int): Int = 0
}
class Final : Comparable<Final> {
    override fun compareTo(other: Final): Int = 0
    final override fun toString(): String = ""
}
