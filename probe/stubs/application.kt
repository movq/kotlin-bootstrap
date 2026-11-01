// Signatures of IntelliJ's Kotlin extensions, which kotlinc's jar has compiled; for probing only.
package com.intellij.openapi.application

fun <T> runWriteAction(runnable: () -> T): T = TODO()
