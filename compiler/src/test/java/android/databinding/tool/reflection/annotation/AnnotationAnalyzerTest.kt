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

package android.databinding.tool.reflection.annotation

import android.databinding.tool.reflection.ImportBag
import android.databinding.tool.runProcessorTest
import com.google.common.truth.Truth.assertThat
import com.google.testing.compile.JavaFileObjects
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.ParameterizedTypeName
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AnnotationAnalyzerTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun boxedVoidType() {
        val code = JavaFileObjects.forSourceString("com.example.Closure",
                """
                    package com.example;
                    public class Closure<T, R> {
                        R run(T t) {
                            return null;
                        }
                    }
                """.trimIndent())
        runProcessorTest(tmpFolder, code) { context, processingEnvironment ->
            val klass = context.modelAnalyzer
                    ?.findClass("com.example.Closure<Integer, Void>", ImportBag.EMPTY)
                    ?: throw AssertionError("cannot find class")

            assertThat(klass.typeName).isEqualTo(
                    ParameterizedTypeName.get(
                            ClassName.get("com.example", "Closure"),
                            ClassName.get(java.lang.Integer::class.java),
                            ClassName.get(java.lang.Void::class.java)
                    )
            )
        }
    }
}