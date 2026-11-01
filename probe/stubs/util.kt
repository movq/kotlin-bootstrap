// Signatures of IntelliJ's Kotlin extensions, which kotlinc's jar has compiled; for probing only.
package com.intellij.util

inline fun <T> T.applyIf(condition: Boolean, body: T.() -> T): T = TODO()
