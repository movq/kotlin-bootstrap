// Signatures of IntelliJ's Kotlin extensions, which kotlinc's jar has compiled; for probing only.
package com.intellij.psi.util

import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType

val PsiElement?.elementType: IElementType?
    get() = TODO()

inline fun <reified T : PsiElement> PsiElement.childrenOfType(): List<T> = TODO()

fun PsiElement.descendants(
    childrenFirst: Boolean = false,
    canGoInside: (PsiElement) -> Boolean = { true },
): Sequence<PsiElement> = TODO()
