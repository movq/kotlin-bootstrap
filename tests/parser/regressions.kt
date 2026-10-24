// `inner` is a modifier keyword, but here it is a label.
fun f() {
    for ([_, v] in xs) {
        inner@ for (x in v) continue@inner
    }
}

class C {
    companion {
        fun bar() {}
    }
}
