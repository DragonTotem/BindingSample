/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool

import android.databinding.tool.ext.cleanLazyProps
import android.databinding.tool.reflection.ModelAnalyzer
import android.databinding.tool.reflection.SdkUtil
import android.databinding.tool.reflection.TypeUtil
import android.databinding.tool.reflection.annotation.AnnotationAnalyzer
import android.databinding.tool.reflection.annotation.AnnotationLogger
import android.databinding.tool.store.SetterStore
import android.databinding.tool.util.EMPTY_RESOURCES
import android.databinding.tool.util.GenerationalClassUtil
import android.databinding.tool.util.L
import android.databinding.tool.util.parseRTxtFiles
import android.databinding.tool.util.Resources
import javax.annotation.processing.ProcessingEnvironment

/**
 * Simple class to hold all singletons so that it is relatively easier to clean them.
 * We cannot easily get rid of singletons w/o a bigger change so this is a middle ground where
 * we can start clearing them from a central location.
 *
 * Singletons are expected to use this to keep their instances.
 */
object Context {
    private val logger: AnnotationLogger = AnnotationLogger()
    @JvmStatic
    fun init(processingEnvironment: ProcessingEnvironment,
             args: CompilerArguments) {
        L.setClient(logger)
        val hasAndroidXBinding = discoverAndroidX(processingEnvironment)
        libTypes = LibTypes(hasAndroidXBinding)
        generationalClassUtil = GenerationalClassUtil.create(args)
        modelAnalyzer = AnnotationAnalyzer(processingEnvironment, libTypes)
        typeUtil = modelAnalyzer!!.createTypeUtil()
        setterStore = SetterStore.create(modelAnalyzer, generationalClassUtil)
        sdkUtil = SdkUtil.create(args.sdkDir, args.minApi)
        resources =
                parseRTxtFiles(args.localR, args.dependenciesRFiles, args.mergedDependenciesRFile)
    }

    private fun discoverAndroidX(processingEnvironment: ProcessingEnvironment): Boolean {
        val hasSupportBinding = processingEnvironment
                .elementUtils
                .getTypeElement("android.databinding.Observable") != null
        val hasAndroidXBinding = processingEnvironment
                .elementUtils
                .getTypeElement("androidx.databinding.Observable") != null
        if (hasAndroidXBinding && hasSupportBinding) {
            L.e("AndroidX Error: Both old and new data binding packages are available in dependencies. Make sure" +
                    " you've setup jettifier  for any data binding dependencies and also set android.useAndroidx in" +
                    " your gradle.properties file.")
        }
        return hasAndroidXBinding
    }

    @JvmStatic
    fun initForTests(modelAnayzer: ModelAnalyzer, sdkUtil: SdkUtil) {
        this.modelAnalyzer = modelAnayzer
        this.sdkUtil = sdkUtil
        typeUtil = modelAnalyzer!!.createTypeUtil()
    }

    @JvmStatic
    var modelAnalyzer: ModelAnalyzer? = null
        private set

    @JvmStatic
    var setterStore: SetterStore? = null
        private set

    @JvmStatic
    var generationalClassUtil: GenerationalClassUtil? = null
        private set

    @JvmStatic
    var typeUtil: TypeUtil? = null
        private set

    @JvmStatic
    var sdkUtil: SdkUtil? = null
        private set

    @JvmStatic
    var libTypes: LibTypes? = null
        private set

    // Ordered list of resources defined in each package. Order matters as the closest to the
    // current module should be chosen. Use when non-transitive R classes are enabled, and therefore
    // each resource needs to be referenced through a class in a module it was defined in.
    @JvmStatic
    var resources: Resources = EMPTY_RESOURCES
        private set

    @JvmStatic
    fun fullClear(processingEnvironment: ProcessingEnvironment) {
        logger.flushMessages(processingEnvironment)
        modelAnalyzer = null
        setterStore = null
        generationalClassUtil = null
        typeUtil = null
        sdkUtil = null
        libTypes = null
        resources = EMPTY_RESOURCES
        L.setClient(null)
        cleanLazyProps()
    }
}
