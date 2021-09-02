/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.jvm.checkers.declaration

import org.jetbrains.kotlin.config.JvmAnalysisFlags
import org.jetbrains.kotlin.config.JvmDefaultMode
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.context.findClosest
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirBasicDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.unsubstitutedScope
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.jvm.checkers.isCompiledToJvmDefault
import org.jetbrains.kotlin.fir.analysis.jvm.checkers.isJvm16
import org.jetbrains.kotlin.fir.containingClass
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.resolve.toFirRegularClass
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.scopes.getDirectOverriddenFunctions
import org.jetbrains.kotlin.fir.scopes.impl.FirClassUseSiteMemberScope
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirIntersectionCallableSymbol
import org.jetbrains.kotlin.name.JvmNames.JVM_DEFAULT_FQ_NAME
import org.jetbrains.kotlin.name.JvmNames.JVM_DEFAULT_NO_COMPATIBILITY_FQ_NAME

object FirJvmDefaultChecker : FirBasicDeclarationChecker() {
    override fun check(declaration: FirDeclaration, context: CheckerContext, reporter: DiagnosticReporter) {
        val jvmDefaultMode = context.session.languageVersionSettings.getFlag(JvmAnalysisFlags.jvmDefaultMode)
        var defaultAnnotation: FirAnnotationCall? = null
        val containingDeclaration = context.findClosest<FirClassLikeDeclaration>()

        if (declaration is FirAnnotatedDeclaration) {
            val isJvm16 = context.isJvm16()
            defaultAnnotation = declaration.getAnnotationByFqName(JVM_DEFAULT_FQ_NAME)

            if (defaultAnnotation != null) {
                if (containingDeclaration !is FirClass || !containingDeclaration.isInterface) {
                    reporter.reportOn(defaultAnnotation.source, FirJvmErrors.JVM_DEFAULT_NOT_IN_INTERFACE, context)
                    return
                } else if (isJvm16) {
                    reporter.reportOn(defaultAnnotation.source, FirJvmErrors.JVM_DEFAULT_IN_JVM6_TARGET, "JvmDefault", context)
                    return
                } else if (!jvmDefaultMode.isEnabled) {
                    reporter.reportOn(defaultAnnotation.source, FirJvmErrors.JVM_DEFAULT_IN_DECLARATION, "JvmDefault", context)
                    return
                }
            } else {
                val annotation = declaration.getAnnotationByFqName(JVM_DEFAULT_NO_COMPATIBILITY_FQ_NAME)
                if (annotation != null) {
                    if (isJvm16) {
                        reporter.reportOn(
                            annotation.source,
                            FirJvmErrors.JVM_DEFAULT_IN_JVM6_TARGET,
                            "JvmDefaultWithoutCompatibility",
                            context
                        )
                        return
                    } else if (!jvmDefaultMode.isEnabled) {
                        reporter.reportOn(
                            annotation.source,
                            FirJvmErrors.JVM_DEFAULT_IN_DECLARATION,
                            "JvmDefaultWithoutCompatibility",
                            context
                        )
                        return
                    }
                }
            }
        }

        if (declaration is FirClass) {
            val unsubstitutedScope = declaration.unsubstitutedScope(context)
            val hasDeclaredJvmDefaults = unsubstitutedScope is FirClassUseSiteMemberScope &&
                    unsubstitutedScope.directOverriddenFunctions.keys.any {
                        it.isCompiledToJvmDefault()
                    }
            if (!hasDeclaredJvmDefaults && !declaration.checkJvmDefaultsInHierarchy(jvmDefaultMode, context)) {
                reporter.reportOn(declaration.source, FirJvmErrors.JVM_DEFAULT_THROUGH_INHERITANCE, context)
            }
        }

        checkNonJvmDefaultOverridesJavaDefault(defaultAnnotation, jvmDefaultMode, declaration, containingDeclaration, context, reporter)
    }

    private fun FirDeclaration.checkJvmDefaultsInHierarchy(jvmDefaultMode: JvmDefaultMode, context: CheckerContext): Boolean {
        if (jvmDefaultMode.isEnabled) return true

        if (this !is FirClass) return true

        val unsubstitutedScope = unsubstitutedScope(context)
        if (unsubstitutedScope is FirClassUseSiteMemberScope) {
            val directOverriddenFunctions = unsubstitutedScope.directOverriddenFunctions.flatMap { it.value }.toSet()

            for (key in unsubstitutedScope.overrideByBase.keys) {
                if (directOverriddenFunctions.contains(key)) {
                    continue
                }

                if (key.getOverriddenDeclarations().all {
                        it.containingClass()?.toFirRegularClass(context.session)?.isInterface != true ||
                                !it.isCompiledToJvmDefaultWithProperMode() ||
                                it.modality == Modality.ABSTRACT
                    }
                ) {
                    continue
                }

                return false
            }
        }

        return true
    }

    private fun checkNonJvmDefaultOverridesJavaDefault(
        defaultAnnotation: FirAnnotationCall?,
        jvmDefaultMode: JvmDefaultMode,
        declaration: FirDeclaration,
        containingDeclaration: FirClassLikeDeclaration?,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        if (defaultAnnotation == null &&
            !jvmDefaultMode.forAllMethodsWithBody &&
            containingDeclaration is FirClass &&
            containingDeclaration.isInterface
        ) {
            val member = declaration as? FirSimpleFunction ?: return
            if (declaration is FirPropertyAccessor) return

            val unsubstitutedScope = containingDeclaration.unsubstitutedScope(context)
            val overriddenFunctions = unsubstitutedScope.getDirectOverriddenFunctions(member.symbol)

            if (overriddenFunctions.any { it.getAnnotationByFqName(JVM_DEFAULT_FQ_NAME) != null }) {
                reporter.reportOn(declaration.source, FirJvmErrors.JVM_DEFAULT_REQUIRED_FOR_OVERRIDE, context)
            } else if (jvmDefaultMode.isEnabled) {
                for (overriddenFunction in overriddenFunctions) {
                    val overriddenDeclarations = overriddenFunction.getOverriddenDeclarations()
                    for (overriddenDeclaration in overriddenDeclarations) {
                        val containingClass = overriddenDeclaration.containingClass()?.toSymbol(context.session)
                        if (containingClass?.origin is FirDeclarationOrigin.Java &&
                            overriddenDeclaration.modality != Modality.ABSTRACT
                        ) {
                            reporter.reportOn(declaration.source, FirJvmErrors.NON_JVM_DEFAULT_OVERRIDES_JAVA_DEFAULT, context)
                            return
                        }
                    }
                }
            }
        }
    }

    fun FirCallableSymbol<*>.getOverriddenDeclarations(): List<FirCallableSymbol<*>> {
        val result = mutableListOf<FirCallableSymbol<*>>()

        if (this is FirIntersectionCallableSymbol) {
            result.addAll(this.intersections)
        } else {
            result.add(this)
        }

        return result
    }

    fun FirCallableSymbol<*>.isCompiledToJvmDefaultWithProperMode(): Boolean {
        // TODO: Fix support for all cases
        return isCompiledToJvmDefault()
    }
}