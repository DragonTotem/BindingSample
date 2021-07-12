/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.databinding.annotationprocessor.BindableBag
import android.databinding.tool.ext.L
import android.databinding.tool.ext.S
import android.databinding.tool.reflection.ModelAnalyzer
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import javax.lang.model.element.Modifier

class BRWriter(private val useFinal : Boolean) {
    fun write(values : BindableBag.ModuleBR): String {
        val spec = TypeSpec.classBuilder("BR").apply {
            addModifiers(Modifier.PUBLIC)
            if (ModelAnalyzer.getInstance().hasGeneratedAnnotation) {
                addAnnotation(AnnotationSpec.builder(ClassName.get("javax.annotation", "Generated"))
                        .addMember("value", S,"Android Data Binding").build())
            }
            values.br.props.forEach {
                addField(
                        FieldSpec.builder(TypeName.INT, it.first, Modifier.PUBLIC,
                                Modifier.STATIC).apply {
                            if (useFinal) {
                                addModifiers(Modifier.FINAL)
                            }
                            initializer(L, it.second)
                        }.build()
                )
            }
        }.build()
        val sb = StringBuilder()
        JavaFile.builder(values.pkg, spec).build()
                .writeTo(sb)
        return  sb.toString()
    }
}
