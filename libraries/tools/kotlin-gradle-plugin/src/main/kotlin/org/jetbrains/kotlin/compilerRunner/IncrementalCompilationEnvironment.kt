/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.compilerRunner

import org.jetbrains.kotlin.daemon.common.MultiModuleICSettings
import org.jetbrains.kotlin.incremental.gradle.ChangedFiles
import java.io.File
import java.io.Serializable

internal class IncrementalCompilationEnvironment(
    val changedFiles: ChangedFiles,
    val workingDir: File,
    val usePreciseJavaTracking: Boolean = false,
    val disableMultiModuleIC: Boolean = false,
    val multiModuleICSettings: MultiModuleICSettings
) : Serializable {
    companion object {
        const val serialVersionUID: Long = 0
    }
}