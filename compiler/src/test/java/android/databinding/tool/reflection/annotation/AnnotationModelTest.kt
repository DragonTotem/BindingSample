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
import android.databinding.tool.reflection.ModelAnalyzer
import android.databinding.tool.runProcessorTest
import com.google.common.truth.Truth.assertThat
import com.google.testing.compile.CompilationRule
import com.google.testing.compile.JavaFileObjects
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AnnotationModelTest {
    @JvmField
    @Rule
    val compilation = CompilationRule()

    @JvmField
    @Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun parseGeneric_unspecified() {
        runTest(
                ownerCodeDeclaration = "Owner<T>",
                ownerXMLDeclaration = "com.example.Owner",
                methodReturnType = "java.util.List<String>",
                expectedClassDeclaration = "com.example.Owner<?>",
                expectedMethodReturnTypeDeclaration = "java.util.List<java.lang.String>"
        )
    }

    @Test
    fun parseGeneric_fullySpecified() {
        runTest(
                ownerCodeDeclaration = "Owner<T>",
                ownerXMLDeclaration = "com.example.Owner<Integer>",
                methodReturnType = "java.util.List<String>",
                expectedClassDeclaration = "com.example.Owner<java.lang.Integer>",
                expectedMethodReturnTypeDeclaration = "java.util.List<java.lang.String>"
        )
    }


    @Test
    fun parseGeneric_childUnspecified() {
        runTest(
                ownerCodeDeclaration = "Owner<T>",
                ownerXMLDeclaration = "com.example.Owner",
                methodReturnType = "java.util.List",
                expectedClassDeclaration = "com.example.Owner<?>",
                expectedMethodReturnTypeDeclaration = "java.util.List"
        )
    }

    @Test
    fun parseGeneric_childInheritFromParent() {
        runTest(
                code = """
                    package com.example;
                    public abstract class Owner<T> {
                        public abstract java.util.List<T> get();
                    }
                """.trimIndent(),
                ownerXMLDeclaration = "com.example.Owner<Integer>",
                expectedClassDeclaration = "com.example.Owner<java.lang.Integer>",
                expectedMethodReturnTypeDeclaration = "java.util.List<java.lang.Integer>"
        )
    }

    @Test
    fun parseGeneric_accessingGenericField() {
        val code = JavaFileObjects.forSourceString("com.example.Owner",
                """
                    package com.example;
                    public abstract class Owner<T> {
                        public T value;
                    }
                """.trimIndent())
        runProcessorTest(tmpFolder, code) { _, _ ->
            val ownerModel = ModelAnalyzer.getInstance()
                    .findClass("com.example.Owner", ImportBag.EMPTY) as AnnotationClass
            val valueField = ownerModel.allFields.first {
                it.name == "value"
            }
            assertThat(valueField.fieldType.toDeclarationCode()).isEqualTo("java.lang.Object")
        }
    }

    @Test
    fun parseGeneric_accessingBoundGeneric() {
        val code = JavaFileObjects.forSourceString("com.example.Owner",
                """
                    package com.example;
                    import java.util.List;
                    public abstract class Owner<T extends List> {
                        public T value;
                    }
                """.trimIndent())
        runProcessorTest(tmpFolder, code) { _, _ ->
            val ownerModel = ModelAnalyzer.getInstance()
                    .findClass("com.example.Owner", ImportBag.EMPTY) as AnnotationClass
            val valueField = ownerModel.allFields.first {
                it.name == "value"
            }
            assertThat(valueField.fieldType.toDeclarationCode()).isEqualTo("java.util.List")
        }
    }

    private fun runTest(
            code: String,
            ownerXMLDeclaration: String,
            expectedClassDeclaration: String,
            expectedMethodReturnTypeDeclaration: String) {
        val owner = JavaFileObjects.forSourceString("com.example.Owner", code)
        runProcessorTest(tmpFolder, owner) { _, _ ->
            val ownerModel = ModelAnalyzer.getInstance()
                    .findClass(ownerXMLDeclaration, ImportBag.EMPTY) as AnnotationClass
            val methodModel = ownerModel.getMethod(
                    name = "get",
                    args = emptyList(),
                    staticOnly = false,
                    allowProtected = false,
                    unwrapObservableFields = true
            ) as AnnotationMethod
            assertThat(ownerModel.toDeclarationCode()).isEqualTo(expectedClassDeclaration)
            assertThat(methodModel.returnType.toDeclarationCode())
                    .isEqualTo(expectedMethodReturnTypeDeclaration)
        }
    }

    private fun runTest(
            ownerCodeDeclaration: String,
            ownerXMLDeclaration: String,
            methodReturnType: String,
            expectedClassDeclaration: String,
            expectedMethodReturnTypeDeclaration: String) {
        runTest(
                code = """
                    package com.example;
                    public abstract class $ownerCodeDeclaration {
                        public abstract $methodReturnType get();
                    }
                """.trimIndent(),
                ownerXMLDeclaration = ownerXMLDeclaration,
                expectedClassDeclaration = expectedClassDeclaration,
                expectedMethodReturnTypeDeclaration = expectedMethodReturnTypeDeclaration
        )
    }
}