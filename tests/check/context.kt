package check.context

interface Logger {
    fun log(s: String)
}

interface Session {
    val name: String
}

open class Base
class Derived : Base()

context(logger: Logger)
fun info(s: String) = logger.log(s)

context(session: Session)
val sessionName: String get() = session.name

context(t: T)
fun <T> given(): T = t

// Context parameters of the caller are context arguments.
context(logger: Logger, session: Session)
fun caller() {
    info(sessionName)
    // Unnamed, still a context value.
    val g = given<Session>()
}

context(_: Logger)
fun unnamed() {
    info("x")
}

// The extension receiver is a context value, in the same group as the context parameters.
context(d: Derived)
fun Base.sameGroup(): Int {
    // Both are Base values, but only one is Derived.
    val x = given<Derived>()
    return 0
}

// Inner groups win over outer ones.
class Holder : Logger {
    override fun log(s: String) {}

    context(other: Logger)
    fun inner() {
        info("inner")
    }

    fun outer() {
        info("outer")
    }
}

// Lambdas with context parameters give context values.
fun withLogger(block: context(Logger) () -> Unit) {}
fun <A, R> withContext(a: A, block: context(A) () -> R): R = block(a)

fun lambdas(logger: Logger, session: Session) {
    withLogger { info("lambda") }
    val n: String = withContext(session) { sessionName }
}

// Invoking a value of a function type with context parameters passes them implicitly.
context(logger: Logger)
fun invokes(f: context(Logger) (Int) -> Unit, g: context(Logger) () -> String) {
    f(1)
    f(logger, 2)
    val s = g()
}

// Inference from the context argument.
context(session: Session)
fun inferred() {
    val s = given()
    val n = s.name
}
