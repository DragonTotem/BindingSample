/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.databinding.tool.writer

import android.databinding.tool.CompilerArguments
import android.databinding.tool.LibTypes
import android.databinding.tool.ext.N
import android.databinding.tool.ext.S
import android.databinding.tool.ext.T
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeSpec
import javax.lang.model.element.Modifier

class MergedBindingMapperWriter(
        compilerArgs: CompilerArguments,
        private val featurePackages : Set<String>,
        private val hasV1CompatMapper: Boolean,
        private val libTypes: LibTypes) {
    private val generateAsTest = compilerArgs.isTestVariant && compilerArgs.isApp
    private val generateTestOverride = !generateAsTest && compilerArgs.isEnabledForTests
    private val overrideField = FieldSpec.builder(ClassName.bestGuess(libTypes.dataBinderMapper),
            "sTestOverride")
            .addModifiers(Modifier.STATIC)
            .build()

    companion object {
        const val APP_CLASS_NAME = "DataBinderMapperImpl"
        internal const val TEST_CLASS_NAME = "Test$APP_CLASS_NAME"
    }

    val pkg = libTypes.bindingPackage
    val qualifiedName = "$pkg.$APP_CLASS_NAME"
    private val appPkg: String = compilerArgs.modulePackage
    private val dataBinderMapper: ClassName = ClassName.bestGuess(libTypes.dataBinderMapper)

    private val mergedMapperBase: ClassName = ClassName.get(
            libTypes.bindingPackage,
            "MergedDataBinderMapper")

    private val testOverride: ClassName = ClassName.get(
            libTypes.bindingPackage,
            TEST_CLASS_NAME)

    fun write() = TypeSpec.classBuilder(APP_CLASS_NAME).apply {
        superclass(mergedMapperBase)
        addModifiers(Modifier.PUBLIC)
        addMethod(MethodSpec.constructorBuilder().apply {
            val mapper = ClassName.get(appPkg, APP_CLASS_NAME)
            addStatement("addMapper(new $T())", mapper)
            if (hasV1CompatMapper) {
                val compatMapper = ClassName.get(
                    BindingMapperWriter.v1CompatMapperPkg(libTypes.useAndroidX),
                    BindingMapperWriter.V1_COMPAT_MAPPER_NAME)
                addStatement("addMapper(new $T())", compatMapper)
            }
            featurePackages.forEach {
                addStatement("addMapper($S)", it)
            }
            if (generateTestOverride) {
                beginControlFlow("if($N != null)", overrideField).apply {
                    addStatement("addMapper($N)", overrideField)
                }.endControlFlow()
            }
        }.build())
        if (generateTestOverride) {
            addField(overrideField)
            addStaticBlock(CodeBlock.builder()
                    .beginControlFlow("try").apply {
                addStatement("$N = ($T) $T.class.getClassLoader().loadClass($S).newInstance()",
                        overrideField, dataBinderMapper,
                        dataBinderMapper,
                        testOverride)
            }.nextControlFlow("catch($T ignored)", ClassName.get(Throwable::class.java))
                    .apply {
                        addStatement("$N = null", overrideField)
                    }
                    .endControlFlow()
                    .build())
        }
    }.build()!!
}
