/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.light.classes.symbol

import com.intellij.psi.*
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtValueParameterSymbol
import org.jetbrains.kotlin.psi.KtParameter

internal abstract class FirLightParameterBaseForSymbol(
    private val parameterSymbol: KtValueParameterSymbol,
    private val containingMethod: FirLightMethod
) : FirLightParameter(containingMethod) {
    private val _name: String = parameterSymbol.name.asString()
    override fun getName(): String = _name

    override fun hasModifierProperty(name: String): Boolean =
        modifierList.hasModifierProperty(name)

    override val kotlinOrigin: KtParameter? = parameterSymbol.psi as? KtParameter

    abstract override fun getModifierList(): PsiModifierList

    private val _identifier: PsiIdentifier by lazyPub {
        FirLightIdentifier(this, parameterSymbol)
    }

    protected val nullabilityType: NullabilityType
        get() {
            val nullabilityApplicable = !containingMethod.containingClass.let { it.isAnnotationType || it.isEnum } &&
                    !containingMethod.hasModifierProperty(PsiModifier.PRIVATE)

            return if (nullabilityApplicable) {
                analyzeWithSymbolAsContext(parameterSymbol) {
                    getTypeNullability(
                        parameterSymbol.annotatedType.type
                    )
                }
            }
            else NullabilityType.Unknown
        }

    override fun getNameIdentifier(): PsiIdentifier = _identifier

    private val _type by lazyPub {
        val convertedType = analyzeWithSymbolAsContext(parameterSymbol) {
            parameterSymbol.annotatedType.type.asPsiType(this@FirLightParameterBaseForSymbol)
        }
        if (convertedType is PsiArrayType && parameterSymbol.isVararg) {
            PsiEllipsisType(convertedType.componentType, convertedType.annotationProvider)
        } else convertedType
    }

    override fun getType(): PsiType = _type

    abstract override fun equals(other: Any?): Boolean

    abstract override fun hashCode(): Int
}