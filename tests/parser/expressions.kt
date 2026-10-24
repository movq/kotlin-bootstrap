fun f() {
    val (a, b) = pair
    val lambda = { x: Int, (y, z): Pair<Int, Int> -> x + y }
    val ref = String::length
    val cls = List<String>::class
    val bound = this::f
    val anon = fun(x: Int): Int { return x }
    val obj = object : Runnable { override fun run() {} }
    val w = when (val s = get()) {
        is String, null -> 1
        in 1..2 -> 2
        !in 3..4 -> 3
        else -> 4
    }
    val t = try { 1 } catch (e: Exception) { 2 } finally { }
    loop@ for (i in 0 until 10) {
        if (i == 2) continue@loop else break@loop
    }
    do { x-- } while (x > 0)
    while (true);
    list.forEach lambda@{ return@lambda }
    a[1, 2] += 3
    foo<Int>(1) { it }
    @Suppress("x") val annotated = 1
    throw IllegalStateException()
    super<Base>.f()
    x?.let { it }!!
}
