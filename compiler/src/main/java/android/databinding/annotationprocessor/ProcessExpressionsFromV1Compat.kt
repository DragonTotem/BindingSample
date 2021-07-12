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

package android.databinding.annotationprocessor

import android.databinding.tool.CompilerChef
import android.databinding.tool.CompilerArguments
import android.databinding.tool.reflection.ModelAnalyzer
import android.databinding.tool.store.ResourceBundle
import android.databinding.tool.writer.BindingMapperWriterV2
import android.databinding.tool.writer.JavaFileWriter
import java.util.HashMap
import javax.annotation.processing.ProcessingEnvironment

/**
 * Loads intermediates from dependencies which are in V1, generates their code and creates a
 * compiler chef specific to them. (to be able to treat them like a v2 dependency).
 */
class ProcessExpressionsFromV1Compat(
    private val processingEnvironment: ProcessingEnvironment,
    private val args : CompilerArguments,
    private val intermediates : List<ProcessExpressions.IntermediateV2>,
    private val writer : JavaFileWriter) {
    /**
     * Returns a CompilerChef if we find V1 dependencies and generate code for them.
     * Null if all dependencies are in v2
     */
    fun generate(): CompilerChef? {
        // This is tricky:
        // in libraries, we should generate them in a stripped way so that if same dependency
        // shows up in 2 different path, it wont blow the final app
        // if we are an app, we should generate it.
        val isModuleInV2Lookup = HashMap<String, Boolean>()

        fun isModuleInV2(modulePackage : String) : Boolean {
            return isModuleInV2Lookup.getOrPut(modulePackage) {
                val mapperClass = BindingMapperWriterV2.createMapperQName(modulePackage)
                // check if mapper exists for it
                val typeElement =
                    processingEnvironment.elementUtils.getTypeElement(mapperClass)
                typeElement != null
            }
        }
        // mapping from key (layoutName) to generated code QName (or base class)
        val classMapping = mutableMapOf<String, String>()
        val compatBundle = ResourceBundle(args.modulePackage,
                ModelAnalyzer.getInstance().libTypes.useAndroidX)
        intermediates
            .flatMap {
                it.extractBundles()
            }
            .filterNot { layoutFileBundle ->
                isModuleInV2(layoutFileBundle.modulePackage)
            }
            .forEach {
                classMapping[it.fileName] = it.fullBindingClass
                compatBundle.addLayoutBundle(it, false)
            }
        return writeResourceBundle(
            resourceBundle = compatBundle,
            compilerArgs = args.copyAsV1(COMPAT_PACKAGE)
        )
    }

    /**
     * Generates the code for v1 compat, returns the newly generated CompilerChef.
     * Returns null if we don't generate anything.
     */
    private fun writeResourceBundle(
        resourceBundle: ResourceBundle,
        compilerArgs: CompilerArguments): CompilerChef? {
        val compilerChef = CompilerChef.createChef(
            resourceBundle,
            writer, compilerArgs
        )
        compilerChef.sealModels()
        // write this only if we are compiling an app or a library test app.
        // even if data binding is enabled for tests, we should not re-generate this.
        if (compilerChef.hasAnythingToGenerate()) {
            if (!compilerArgs.isEnableV2) {
                compilerChef.writeViewBinderInterfaces(compilerArgs.isLibrary
                        && !compilerArgs.isTestVariant)
            }
            if (compilerArgs.isApp != compilerArgs.isTestVariant
                || compilerArgs.isEnabledForTests && !compilerArgs.isLibrary
                || compilerArgs.isEnableV2
            ) {
                compilerChef.writeViewBinders(compilerArgs.minApi)
            }
        } else {
            return null
        }
        return compilerChef
    }

    companion object {
        const val COMPAT_PACKAGE = "android.databinding.v1Compat"
    }
}
