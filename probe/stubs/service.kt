// Signatures of IntelliJ's Kotlin extensions, which kotlinc's jar has compiled; for probing only.
package com.intellij.openapi.components

inline fun <reified T : Any> service(): T = TODO()

inline fun <reified T : Any> serviceOrNull(): T? = TODO()

inline fun <reified T : Any> ComponentManager.service(): T = TODO()

inline fun <reified T : Any> ComponentManager.serviceOrNull(): T? = TODO()
