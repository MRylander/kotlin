/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.config

import kotlin.reflect.KProperty

object JvmAnalysisFlags {
    @JvmStatic
    val strictMetadataVersionSemantics by AnalysisFlag.Delegates.Boolean

    @JvmStatic
    val jvmDefaultMode by Delegates.JvmDefaultModeDisabledByDefault

    @JvmStatic
    val inheritMultifileParts by AnalysisFlag.Delegates.Boolean

    @JvmStatic
    val sanitizeParentheses by AnalysisFlag.Delegates.Boolean

    @JvmStatic
    val suppressMissingBuiltinsError by AnalysisFlag.Delegates.Boolean

    @JvmStatic
    val disableUltraLightClasses by AnalysisFlag.Delegates.Boolean

    @JvmStatic
    val enableJvmPreview by AnalysisFlag.Delegates.Boolean

    @JvmStatic
    val useIR by AnalysisFlag.Delegates.Boolean

    private object Delegates {
        object JvmDefaultModeDisabledByDefault {
            operator fun provideDelegate(instance: Any?, property: KProperty<*>): AnalysisFlag.Delegate<JvmDefaultMode> =
                AnalysisFlag.Delegate(property.name, JvmDefaultMode.DISABLE)
        }
    }
}
