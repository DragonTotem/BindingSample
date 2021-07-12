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
package android.databinding.tool.reflection.annotation

import android.databinding.tool.reflection.*
import android.databinding.tool.util.L
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.TypeName
import java.util.*
import javax.lang.model.element.ElementKind
import javax.lang.model.element.TypeElement
import javax.lang.model.type.*
import javax.lang.model.util.ElementFilter
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

/**
 * This is the implementation of ModelClass for the annotation
 * processor. It relies on AnnotationAnalyzer.
 */
class AnnotationClass(
        @JvmField
        val typeMirror: TypeMirror
) : ModelClass() {

    private val typeUtils: Types
        get() = AnnotationAnalyzer.get().mProcessingEnv.typeUtils

    private val elementUtils: Elements
        get() = AnnotationAnalyzer.get().mProcessingEnv.elementUtils

    override fun toJavaCode(): String {
        return if (isIncomplete) {
            canonicalName
        } else {
            AnnotationTypeUtil.getInstance().toJava(typeMirror)
        }
    }

    override val componentType by lazy(LazyThreadSafetyMode.NONE) {
        computeComponentType() as ModelClass?
    }

    override fun toDeclarationCode(): String {
        if (typeMirror is TypeVariable) {
            // if it is a type var, use upper bound
            // see b/144300600 for the fallback to typeMirror itself
            return AnnotationTypeUtil.getInstance().toJava(typeMirror.upperBound ?: typeMirror)
        }
        return AnnotationTypeUtil.getInstance().toJava(typeMirror)
    }

    private fun computeComponentType(): AnnotationClass? {
        val component: TypeMirror?
        when {
            isArray -> component = (typeMirror as ArrayType).componentType
            isList -> {
                for (method in getMethods("get", 1)) {
                    val parameter = method.parameterTypes[0]
                    if (parameter.isInt || parameter.isLong) {
                        val parameters = ArrayList<ModelClass>(1)
                        parameters.add(parameter)
                        return method.getReturnType(parameters) as AnnotationClass
                    }
                }
                // no "get" call found!
                return null
            }
            else -> {
                val mapClass = ModelAnalyzer.getInstance().mapType as AnnotationClass?
                val mapType = findInterface(mapClass!!.typeMirror) ?: return null
                component = mapType.typeArguments[1]
            }
        }

        return AnnotationClass(component)
    }

    private fun findInterface(interfaceType: TypeMirror): DeclaredType? {
        val typeUtil = typeUtils
        var foundInterface: TypeMirror? = null
        if (typeUtil.isSameType(interfaceType, typeUtil.erasure(typeMirror))) {
            foundInterface = typeMirror
        } else {
            val toCheck = ArrayList<TypeMirror>()
            toCheck.add(typeMirror)
            while (!toCheck.isEmpty()) {
                val typeMirror = toCheck.removeAt(0)
                if (typeUtil.isSameType(interfaceType, typeUtil.erasure(typeMirror))) {
                    foundInterface = typeMirror
                    break
                } else {
                    toCheck.addAll(typeUtil.directSupertypes(typeMirror))
                }
            }
            if (foundInterface == null) {
                L.e("Detected " + interfaceType + " type for " + typeMirror +
                        ", but not able to find the implemented interface.")
                return null
            }
        }
        if (foundInterface.kind != TypeKind.DECLARED) {
            L.e("Found " + interfaceType + " type for " + typeMirror +
                    ", but it isn't a declared type: " + foundInterface)
            return null
        }
        return foundInterface as DeclaredType?
    }

    override val isNullable: Boolean
        get() = when (typeMirror.kind) {
            TypeKind.ARRAY, TypeKind.DECLARED, TypeKind.NULL -> true
            else -> false
        }

    override val isPrimitive: Boolean
        get() = when (typeMirror.kind) {
            TypeKind.BOOLEAN, TypeKind.BYTE, TypeKind.SHORT, TypeKind.INT,
            TypeKind.LONG, TypeKind.CHAR, TypeKind.FLOAT, TypeKind.DOUBLE -> true
            else -> false
        }

    override val isArray = typeMirror.kind == TypeKind.ARRAY

    override val isBoolean = typeMirror.kind == TypeKind.BOOLEAN

    override val isChar = typeMirror.kind == TypeKind.CHAR

    override val isByte = typeMirror.kind == TypeKind.BYTE

    override val isShort = typeMirror.kind == TypeKind.SHORT

    override val isInt = typeMirror.kind == TypeKind.INT

    override val isLong = typeMirror.kind == TypeKind.LONG

    override val isFloat = typeMirror.kind == TypeKind.FLOAT

    override val isDouble = typeMirror.kind == TypeKind.DOUBLE

    override val isTypeVar = typeMirror.kind == TypeKind.TYPEVAR

    override val isWildcard = typeMirror.kind == TypeKind.WILDCARD

    override val isVoid = typeMirror.kind == TypeKind.VOID

    override val isInterface by lazy(LazyThreadSafetyMode.NONE) {
        typeMirror.kind == TypeKind.DECLARED &&
                (typeMirror as DeclaredType).asElement().kind == ElementKind.INTERFACE
    }

    override val isGeneric by lazy(LazyThreadSafetyMode.NONE) {
        typeMirror.kind == TypeKind.DECLARED &&
            (typeMirror as DeclaredType)
                    .typeArguments
                    .isNotEmpty()
    }

    private fun extractTargetApi(): Int? {
        if (typeMirror.kind == TypeKind.DECLARED) {
            val declaredType = typeMirror as DeclaredType
            val annotations = elementUtils.getAllAnnotationMirrors(declaredType.asElement())

            val targetApi = elementUtils.getTypeElement("android.annotation.TargetApi")
            val targetApiType = targetApi.asType()
            val typeUtils = typeUtils
            for (annotation in annotations) {
                if (typeUtils.isAssignable(annotation.annotationType, targetApiType)) {
                    for (value in annotation.elementValues.values) {
                        return value.value as Int
                    }
                }
            }
        }
        return null
    }

    override val minApi by lazy(LazyThreadSafetyMode.NONE) {
        extractTargetApi() ?: super.minApi
    }

    override val typeArguments by lazy(LazyThreadSafetyMode.NONE) {
        if (typeMirror.kind == TypeKind.DECLARED) {
            (typeMirror as? DeclaredType)?.typeArguments?.map {
                AnnotationClass(it)
            }?.let {
                if (it.isEmpty()) {
                    null
                } else {
                    it
                }
            }
        } else {
            null
        }
    }

    private val computedUnbox by lazy(LazyThreadSafetyMode.NONE) {
        if (!isNullable) {
            this
        } else {
            try {
                AnnotationClass(typeUtils.unboxedType(typeMirror))
            } catch (e: IllegalArgumentException) {
                // I'm being lazy. This is much easier than checking every type.
                this
            }
        }
    }

    override fun unbox() = computedUnbox

    private val computedBox by lazy(LazyThreadSafetyMode.NONE) {
        if (!isPrimitive) {
            this
        } else {
            AnnotationClass(typeUtils.boxedClass(typeMirror as PrimitiveType).asType())
        }
    }

    override fun box() = computedBox

    override fun isAssignableFrom(that: ModelClass?): Boolean {
        var other: ModelClass? = that
        while (other != null && other !is AnnotationClass) {
            other = other.superclass
        }
        if (other == null) {
            return false
        }
        if (equals(other)) {
            return true
        }
        val thatAnnotationClass = other as? AnnotationClass ?: return false
        if (typeUtils.isAssignable(thatAnnotationClass.typeMirror, this.typeMirror)) {
            return true
        }
        // If this is incomplete, java typeUtils won't be able to detect assignments like
        // List <- List<String> because we'll resolve List as List<T>.
        // To handle those cases, we run a custom assignability as well :/
        if (isIncomplete || other.isIncomplete) {
            if (this.isTypeVar) {
                // if this is a type var and resolved as a type var, accept it.
                // This allows assigning List<Foo> to List (List<?> is internal representation so
                // technically it is assigning Foo to ?)
                // Java does also accept assigning List<?> to List<Foo> but we'll not accept it
                // as it creates really weird assignability cases like ? being assignable to
                // LiveData if ? is resolved from a generic. For instance:
                // data class Foo<T>(val value : T)
                // the type of `foo.value` will be `?` so checking LiveData isAssignableFrom
                // `foo.value` would return true (which we don't want).
                return true
            }
            val myTypeArguments = typeArguments ?: return false
            val otherTypeArguments = other.typeArguments ?: return false
            val myErasure = erasure()
            val otherErasure = other.erasure()
            if (myTypeArguments.size == otherTypeArguments.size &&
                myErasure.isAssignableFrom(otherErasure)) {
                myTypeArguments.forEachIndexed { index, myTypeArgument ->
                    if (!myTypeArgument.isAssignableFrom(otherTypeArguments[index])) {
                        return false
                    }
                }
                return true;
            }
        }
        return false
    }

    override val allMethods by lazy(LazyThreadSafetyMode.NONE) {
        if (typeMirror.kind == TypeKind.DECLARED) {
            val declaredType = typeMirror as DeclaredType
            val elementUtils = elementUtils
            val typeElement = declaredType.asElement() as TypeElement
            val members = elementUtils.getAllMembers(typeElement)
            ElementFilter.methodsIn(members).map {
                AnnotationMethod(declaredType, it) as ModelMethod
            }
        } else {
            emptyList()
        }
    }

    override val superclass by lazy(LazyThreadSafetyMode.NONE) {
        val superClass = if (typeMirror.kind == TypeKind.DECLARED) {
            ((typeMirror as DeclaredType).asElement() as? TypeElement)?.superclass
        } else {
            null
        }
        if (superClass?.kind == TypeKind.DECLARED) {
            AnnotationClass(superClass)
        } else {
            null
        }
    }

    private val computedCanonicalName by lazy(LazyThreadSafetyMode.NONE) {
        // see b/144300600 for the fallback to typeMirror itself
        AnnotationTypeUtil.getInstance().toJava(typeUtils.erasure(typeMirror) ?: typeMirror)
    }

    override val canonicalName: String = computedCanonicalName

    private val computedErasure by lazy(LazyThreadSafetyMode.NONE) {
        val erasure = typeUtils.erasure(typeMirror)
        if (erasure === typeMirror) {
            this
        } else {
            AnnotationClass(erasure)
        }
    }

    override fun erasure(): ModelClass = computedErasure

    private val computedJniDescription by lazy(LazyThreadSafetyMode.NONE) {
        TypeUtil.getInstance().getDescription(this)
    }

    override val jniDescription: String
        get() = computedJniDescription

    override val allFields by lazy(LazyThreadSafetyMode.NONE) {
        if (typeMirror.kind == TypeKind.DECLARED) {
            val declaredType = typeMirror as DeclaredType
            val elementUtils = elementUtils
            val typeElement = declaredType.asElement() as TypeElement
            val members = elementUtils.getAllMembers(typeElement)
            ElementFilter.fieldsIn(members).map {
                AnnotationField(declaredType, it) as ModelField
            }
        } else {
            emptyList()
        }
    }

    private val javaCodeRepresentation by lazy(LazyThreadSafetyMode.NONE) {
        AnnotationTypeUtil.getInstance().toJava(typeMirror)
    }

    override fun toString() = javaCodeRepresentation

    private val computedTypeName by lazy(LazyThreadSafetyMode.NONE) {
        ClassName.get(typeMirror)
    }

    override val typeName: TypeName
        get() = computedTypeName

    override fun hashCode() = javaCodeRepresentation.hashCode()


    @Suppress("RedundantOverride")
    override fun equals(other: Any?): Boolean {
        // intentional delegation to super which implements this in data binding generic way.
        return super.equals(other)
    }
}
