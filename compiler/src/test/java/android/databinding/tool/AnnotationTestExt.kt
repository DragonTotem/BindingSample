/*
 * Copyright (C) 2019 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.databinding.tool

import com.google.common.truth.Truth
import com.google.testing.compile.JavaSourcesSubjectFactory
import org.junit.rules.TemporaryFolder
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.element.TypeElement
import javax.tools.JavaFileObject

/**
 * Helper function to run unit tests inside the annotation processor.
 */
fun runProcessorTest(
        tmpFolder: TemporaryFolder,
        vararg jfos: JavaFileObject,
        block: (Context, ProcessingEnvironment) -> Unit
) {
    Truth.assertAbout(JavaSourcesSubjectFactory.javaSources())
            .that(jfos.toList())
            .withClasspath(emptyList())
            .processedWith(object : AbstractProcessor() {
                override fun process(
                        annotations: MutableSet<out TypeElement>,
                        roundEnv: RoundEnvironment
                ): Boolean {
                    if (!roundEnv.processingOver()) {
                        Context.init(
                                processingEnvironment = processingEnv,
                                args = CompilerArguments(
                                        incremental = false,
                                        artifactType = CompilerArguments.Type.APPLICATION,
                                        modulePackage = "foo.bar",
                                        minApi = 14,
                                        sdkDir = tmpFolder.newFolder("sdk"),
                                        dependencyArtifactsDir = tmpFolder.newFolder("dep_artifacts"),
                                        layoutInfoDir = tmpFolder.newFolder("layout_info"),
                                        classLogDir = tmpFolder.newFolder("class_log_dir"),
                                        isEnabledForTests = false,
                                        aarOutDir = null,
                                        baseFeatureInfoDir = null,
                                        enableDebugLogs = true,
                                        exportClassListOutFile = null,
                                        featureInfoDir = null,
                                        isEnableV2 = true,
                                        isTestVariant = false,
                                        printEncodedErrorLogs = true
                                ))
                        block(Context, processingEnv)
                    }
                    return true
                }


                override fun getSupportedAnnotationTypes() = setOf("*")
            })
            .compilesWithoutError()
}