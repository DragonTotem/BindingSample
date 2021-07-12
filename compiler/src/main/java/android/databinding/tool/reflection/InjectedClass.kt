/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.databinding.tool.reflection

import android.databinding.tool.ext.toTypeName
import android.databinding.tool.util.StringUtils
import com.squareup.javapoet.TypeName
import java.util.*

/**
 * A class that can be used by ModelAnalyzer without any backing model. This is used
 * for ViewDataBinding subclasses that haven't been generated yet, but we still want
 * to resolve methods and fields for them.
 *
 * @see ModelAnalyzer.injectClass
 */
class InjectedClass(private val mClassName: String, private val mSuperClass: String) : ModelClass() {
    private val mMethods = ArrayList<InjectedMethod>()
    private val mFields = ArrayList<InjectedField>()

    override val isArray = false

    override val componentType: ModelClass? = null

    override val isNullable = true

    override val isPrimitive = false

    override val isBoolean = false

    override val isChar = false

    override val isByte = false

    override val isShort = false

    override val isInt = false

    override val isLong = false

    override val isFloat = false

    override val isDouble = false

    override val isGeneric = false

    override val typeArguments: List<ModelClass>? = null

    override val isTypeVar = false

    override val isWildcard = false

    override val isInterface = false

    override val isVoid = false

    override val isObservable by lazy(LazyThreadSafetyMode.NONE) {
        superclass.isObservable
    }

    override val superclass by lazy(LazyThreadSafetyMode.NONE) {
        ModelAnalyzer.getInstance().findClass(mSuperClass, null)!!
    }

    override val jniDescription: String by lazy(LazyThreadSafetyMode.NONE) {
        TypeUtil.getInstance().getDescription(this)
    }

    // not cached because it is mutable
    override val allFields: List<ModelField>
        get() {
            return superclass.allFields + mFields
        }

    // not cached because it is mutable
    override val allMethods: List<ModelMethod>
        get() {
            return superclass.allMethods + mMethods
        }

    override val typeName: TypeName by lazy(LazyThreadSafetyMode.NONE) {
        val instance = ModelAnalyzer.getInstance()
        mClassName.toTypeName(instance.libTypes)
    }

    fun addVariable(name: String, type: String, imports: ImportBag) {
        val capName = StringUtils.capitalize(name)
        val setName = "set" + capName!!
        val getName = "get$capName"
        addMethod(InjectedMethod(this, false, getName, imports, type))
        addMethod(InjectedMethod(this, false, setName, imports, "void", type))
    }

    fun addField(name: String, type: String) {
        addField(InjectedField(name, type))
    }

    private fun addField(field: InjectedField) {
        mFields.add(field)
    }

    fun addMethod(method: InjectedMethod) {
        mMethods.add(method)
    }

    override fun toJavaCode() = mClassName

    override fun unbox() = this

    override fun box() = this

    override fun isAssignableFrom(that: ModelClass?): Boolean {
        var maybeSuper = that
        while (maybeSuper != null && !maybeSuper.isObject) {
            if (maybeSuper.toJavaCode() == mClassName) {
                return true
            }
            maybeSuper = maybeSuper.superclass
        }
        return false
    }

    override fun erasure() = this

    override fun toString() = "Injected Class: $mClassName"
}
