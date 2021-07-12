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
package android.databinding.tool.reflection.annotation;

import android.databinding.tool.BindableCompat;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelField;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

class AnnotationField extends ModelField {

    final VariableElement mField;

    final DeclaredType mDeclaredClass;

    public AnnotationField(DeclaredType declaredClass, VariableElement field) {
        mDeclaredClass = declaredClass;
        mField = field;
    }

    @Override
    public String toString() {
        return mField.toString();
    }

    @Override
    public String getName() {
        return mField.getSimpleName().toString();
    }

    @Override
    public boolean isPublic() {
        return mField.getModifiers().contains(Modifier.PUBLIC);
    }

    @Override
    public boolean isStatic() {
        return mField.getModifiers().contains(Modifier.STATIC);
    }

    @Override
    public boolean isFinal() {
        return mField.getModifiers().contains(Modifier.FINAL);
    }

    @Override
    public ModelClass getFieldType() {
        Types typeUtils = AnnotationAnalyzer.get().getTypeUtils();
        TypeMirror type = typeUtils.asMemberOf(mDeclaredClass, mField);
        return new AnnotationClass(type);
    }

    @Override
    public BindableCompat getBindableAnnotation() {
        return BindableCompat.extractFrom(mField);
    }

    @Override
    public int hashCode() {
        return mField.getSimpleName().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof AnnotationField) {
            AnnotationField that = (AnnotationField) obj;
            Types typeUtils = AnnotationAnalyzer.get().getTypeUtils();
            return typeUtils.isSameType(mDeclaredClass, that.mDeclaredClass)
                    && typeUtils.isSameType(mField.asType(), that.mField.asType())
                    && mField.getSimpleName().equals(that.mField.getSimpleName());
        } else {
            return false;
        }
    }
}
