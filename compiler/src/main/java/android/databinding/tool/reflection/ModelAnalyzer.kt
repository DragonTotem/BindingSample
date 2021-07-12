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
package android.databinding.tool.reflection

import android.databinding.tool.Context
import android.databinding.tool.LibTypes
import android.databinding.tool.util.Preconditions
import java.util.*

/**
 * This is the base class for several implementations of something that
 * acts like a ClassLoader. Different implementations work with the Annotation
 * Processor, ClassLoader, and an Android Studio plugin.
 */
abstract class ModelAnalyzer protected constructor(@JvmField val libTypes: LibTypes) {

    val mapType by lazy(LazyThreadSafetyMode.NONE) {
        loadClassErasure(MAP_CLASS_NAME)!!
    }

    val stringType by lazy(LazyThreadSafetyMode.NONE) {
        findClass(STRING_CLASS_NAME, null)!!
    }
    val objectType  by lazy(LazyThreadSafetyMode.NONE) {
        findClass(OBJECT_CLASS_NAME, null)!!
    }

    val observableType by lazy(LazyThreadSafetyMode.NONE) {
        findClass(libTypes.observable, null)!!
    }
    val observableListType  by lazy(LazyThreadSafetyMode.NONE) {
        loadClassErasure(libTypes.observableList)!!
    }
    val observableMapType  by lazy(LazyThreadSafetyMode.NONE) {
        loadClassErasure(libTypes.observableMap)!!
    }
    val liveDataType  by lazy(LazyThreadSafetyMode.NONE) {
        loadClassErasure(libTypes.liveData)
    }
    val mutableLiveDataType  by lazy(LazyThreadSafetyMode.NONE) {
        loadClassErasure(libTypes.mutableLiveData)
    }
    val stateFlowType  by lazy(LazyThreadSafetyMode.NONE) {
        loadClassErasure(libTypes.stateFlow)
    }
    val mutableStateFlowDataType  by lazy(LazyThreadSafetyMode.NONE) {
        loadClassErasure(libTypes.mutableStateFlow)
    }
    val viewDataBindingType  by lazy(LazyThreadSafetyMode.NONE) {
        val klass = findClass(libTypes.viewDataBinding, null)
        Preconditions.checkNotNull(klass, "Cannot find %s class." +
                "Something is wrong in the classpath,  please submit a bug" +
                " report", libTypes.viewDataBinding)
        klass
    }
    val viewBindingType by lazy(LazyThreadSafetyMode.NONE) {
        val klass = findClass(libTypes.viewBinding, null)
        Preconditions.checkNotNull(klass, "Cannot find %s class." +
          "Something is wrong in the classpath,  please submit a bug" +
          " report", libTypes.viewBinding)
        klass
    }

    val viewStubType  by lazy(LazyThreadSafetyMode.NONE) {
        findClass(VIEW_STUB_CLASS_NAME, null)
    }
    val viewStubProxyType  by lazy(LazyThreadSafetyMode.NONE) {
        findClass(libTypes.viewStubProxy, null)
    }

    /**
     * If present, rely on it for fetching resources when possible.
     */
    val appCompatResourcesType by lazy(LazyThreadSafetyMode.NONE) {
        findClass(libTypes.appCompatResources, null)
    }

    /**
     * If it is present, we annotate generated classes with @Generated.
     */
    val hasGeneratedAnnotation by lazy(LazyThreadSafetyMode.NONE) {
        findGeneratedAnnotation()
    }

    private val mInjectedClasses = HashMap<String, InjectedClass>()

    val listTypes by lazy(LazyThreadSafetyMode.NONE) {
        libTypes.listClassNames
                .mapNotNull(this::loadClassErasure)
    }

    val observableFieldTypes by lazy(LazyThreadSafetyMode.NONE) {
        libTypes.observableFields
                .mapNotNull(this::loadClassErasure)
    }

    @JvmOverloads
    fun findCommonParentOf(modelClass1: ModelClass, modelClass2: ModelClass?,
                           failOnError: Boolean = true): ModelClass? {
        var curr: ModelClass? = modelClass1
        while (curr != null && !curr.isAssignableFrom(modelClass2)) {
            curr = curr.superclass
        }
        if (curr == null) {
            if (modelClass1.isObject && modelClass2!!.isInterface) {
                return modelClass1
            } else if (modelClass2!!.isObject && modelClass1.isInterface) {
                return modelClass2
            }

            val primitive1 = modelClass1.unbox()
            val primitive2 = modelClass2.unbox()
            if (modelClass1 != primitive1 || modelClass2 != primitive2) {
                return findCommonParentOf(primitive1, primitive2, failOnError)
            }
        }
        if (failOnError) {
            Preconditions.checkNotNull(curr,
                    "must be able to find a common parent for " + modelClass1 + " and "
                            + modelClass2)
        }
        return curr
    }

    abstract fun loadPrimitive(className: String): ModelClass

    fun getDefaultValue(className: String) = DEFAULT_VALUES[className] ?: "null"

    val classFinderCache = ClassFinderCache { className, imports ->
        if (mInjectedClasses.containsKey(className)) {
            mInjectedClasses[className]
        } else {
            findClassInternal(className, imports)
        }
    }

    private val dataBindingKtxClass by lazy {
        findClass(libTypes.dataBindingKtx, null)
    }

    fun checkDataBindingKtx() {
        Preconditions.checkNotNull(
                dataBindingKtxClass, """Data binding ktx is not enabled.
                |
                |Add dataBinding.addKtx = true to your build.gradle to enable it."""
                .trimMargin()
        )
    }

    fun findClass(className: String, imports: ImportBag?): ModelClass? {
        return classFinderCache.find(className, imports)
    }

    abstract fun findClassInternal(className: String, importBag: ImportBag?): ModelClass

    abstract fun findClass(classType: Class<*>): ModelClass

    abstract fun createTypeUtil(): TypeUtil

    fun injectClass(injectedClass: InjectedClass): ModelClass {
        mInjectedClasses[injectedClass.canonicalName] = injectedClass
        return injectedClass
    }

    private fun loadClassErasure(className: String): ModelClass? {
        val modelClass = findClass(className, null)
        return modelClass?.erasure()
    }

    protected abstract fun findGeneratedAnnotation(): Boolean

    companion object {
        @JvmField
        val GENERATED_ANNOTATION = "javax.annotation.Generated"

        private val MAP_CLASS_NAME = "java.util.Map"

        private val STRING_CLASS_NAME = "java.lang.String"

        private val OBJECT_CLASS_NAME = "java.lang.Object"

        private val VIEW_STUB_CLASS_NAME = "android.view.ViewStub"

        @JvmStatic
        fun getInstance() : ModelAnalyzer = Context.modelAnalyzer!!

        private val DEFAULT_VALUES = mapOf(
                "int" to "0",
                "short" to "0",
                "long" to "0",
                "float" to "0f",
                "double" to "0.0",
                "boolean" to "false",
                "char" to "'\\u0000'",
                "byte" to "0"
        )
    }

}
