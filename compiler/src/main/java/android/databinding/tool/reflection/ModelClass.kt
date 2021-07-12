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

import android.databinding.tool.BindableCompat
import android.databinding.tool.ext.toTypeName
import android.databinding.tool.reflection.Callable.CAN_BE_INVALIDATED
import android.databinding.tool.reflection.Callable.DYNAMIC
import android.databinding.tool.reflection.Callable.STATIC
import android.databinding.tool.reflection.Callable.Type
import android.databinding.tool.util.L
import android.databinding.tool.util.StringUtils
import com.squareup.javapoet.TypeName
import java.util.*

@Suppress("EqualsOrHashCode")
abstract class ModelClass {

    /**
     * @return whether this ModelClass represents an array.
     */
    abstract val isArray: Boolean

    /**
     * For arrays, lists, and maps, this returns the contained value. For other types, null
     * is returned.
     *
     * @return The component type for arrays, the value type for maps, and the element type
     * for lists.
     */
    abstract val componentType: ModelClass?

    /**
     * @return Whether or not this ModelClass can be treated as a List. This means
     * it is a java.util.List, or one of the Sparse*Array classes.
     */
    val isList by lazy(LazyThreadSafetyMode.NONE) {
        ModelAnalyzer.getInstance().listTypes.any {
            it.isAssignableFrom(this)
        }
    }

    /**
     * @return whether or not this ModelClass can be considered a Map or not.
     */
    val isMap by lazy(LazyThreadSafetyMode.NONE) {
        ModelAnalyzer.getInstance().mapType.isAssignableFrom(erasure())
    }

    /**
     * @return whether or not this ModelClass is a java.lang.String.
     */
    val isString by lazy(LazyThreadSafetyMode.NONE) {
        "java.lang.String" == typeName.toString()
    }
    /**
     * @return whether or not this ModelClass represents a Reference type.
     */
    abstract val isNullable: Boolean

    /**
     * @return whether or not this ModelClass represents a primitive type.
     */
    abstract val isPrimitive: Boolean

    /**
     * @return whether or not this ModelClass represents a Java boolean
     */
    abstract val isBoolean: Boolean

    /**
     * @return whether or not this ModelClass represents a Java char
     */
    abstract val isChar: Boolean

    /**
     * @return whether or not this ModelClass represents a Java byte
     */
    abstract val isByte: Boolean

    /**
     * @return whether or not this ModelClass represents a Java short
     */
    abstract val isShort: Boolean

    /**
     * @return whether or not this ModelClass represents a Java int
     */
    abstract val isInt: Boolean

    /**
     * @return whether or not this ModelClass represents a Java long
     */
    abstract val isLong: Boolean

    /**
     * @return whether or not this ModelClass represents a Java float
     */
    abstract val isFloat: Boolean

    /**
     * @return whether or not this ModelClass represents a Java double
     */
    abstract val isDouble: Boolean

    /**
     * @return whether or not this has type parameters
     */
    abstract val isGeneric: Boolean

    /**
     * @return a list of Generic type parameters for the class. For example, if the class
     * is List&lt;T>, then the return value will be a list containing T. null is returned
     * if this is not a generic type
     */
    abstract val typeArguments: List<ModelClass>?

    /**
     * @return whether this is a type variable. For example, in List&lt;T>, T is a type variable.
     * However, List&lt;String>, String is not a type variable.
     */
    abstract val isTypeVar: Boolean

    /**
     * @return whether this is a wildcard type argument or not.
     */
    abstract val isWildcard: Boolean

    /**
     * @return whether or not this ModelClass is java.lang.Object and not a primitive or subclass.
     */
    val isObject by lazy(LazyThreadSafetyMode.NONE) {
        "java.lang.Object" == typeName.toString()
    }

    /**
     * @return whether or not this ModelClass is an interface
     */
    abstract val isInterface: Boolean

    /**
     * @return whether or not his is a ViewDataBinding subclass.
     */
    val isViewDataBinding by lazy(LazyThreadSafetyMode.NONE) {
        ModelAnalyzer.getInstance().viewDataBindingType!!.isAssignableFrom(this)
    }

    /**
     * @return whether or not this is a ViewBinding subclass.
     */
    val isViewBinding by lazy(LazyThreadSafetyMode.NONE) {
        ModelAnalyzer.getInstance().viewBindingType!!.isAssignableFrom(this)
    }

    /**
     * @return whether or not this is an Observable type such as ObservableMap, ObservableList,
     * or Observable.
     */
    // open for injected
    open val isObservable: Boolean
        get() {
            val modelAnalyzer = ModelAnalyzer.getInstance()
            return modelAnalyzer.observableType.isAssignableFrom(this) ||
                    modelAnalyzer.observableListType.isAssignableFrom(this) ||
                    modelAnalyzer.observableMapType.isAssignableFrom(this) ||
                    (modelAnalyzer.liveDataType?.isAssignableFrom(this) ?: false) ||
                    (modelAnalyzer.stateFlowType?.isAssignableFrom(this) ?: false)
        }

    /**
     * @return whether or not this is an ObservableField, or any of the primitive versions
     * such as ObservableBoolean and ObservableInt
     */
    val isObservableField by lazy(LazyThreadSafetyMode.NONE) {
        val erasure = erasure()
        ModelAnalyzer.getInstance().observableFieldTypes.any {
            it.isAssignableFrom(erasure)
        }
    }

    /**
     * @return whether or not this is a LiveData
     */
    val isLiveData by lazy(LazyThreadSafetyMode.NONE) {
        ModelAnalyzer.getInstance().liveDataType?.isAssignableFrom(erasure()) ?: false
    }

    /**
     * @return whether or not this is a MutableLiveData
     */
    @Suppress("MemberVisibilityCanBePrivate")
    val isMutableLiveData by lazy(LazyThreadSafetyMode.NONE) {
        ModelAnalyzer.getInstance().mutableLiveDataType?.isAssignableFrom(erasure()) ?: false
    }

    /**
     * @return whether or not this is a StateFlow
     */
    val isStateFlow by lazy(LazyThreadSafetyMode.NONE) {
        val modelAnalyzer = ModelAnalyzer.getInstance()
        val isStateFlow = modelAnalyzer.stateFlowType?.isAssignableFrom(erasure()) ?: false
        if (isStateFlow) {
            modelAnalyzer.checkDataBindingKtx()
        }
        isStateFlow
    }

    /**
     * @return whether or not this is a MutableStateFlow
     */
    val isMutableStateFlow by lazy(LazyThreadSafetyMode.NONE) {
        ModelAnalyzer.getInstance().mutableStateFlowDataType?.isAssignableFrom(erasure()) ?: false
    }

    /**
     * @return the name of the simple getter method when this is an ObservableField or LiveData or
     * Flow or `null` for any other type
     */
    val observableGetterName: String?
        get() = when {
            isObservableField -> "get"
            isLiveData || isStateFlow -> "getValue"
            else -> null
        }

    /**
     * @return the name of the simple setter method when this is an ObservableField or
     * MutableLiveData or MutableStateFlow or `null` for any other type.
     */
    val observableSetterName: String?
        get() = when {
            isObservableField -> "set"
            isMutableLiveData || isMutableStateFlow -> "setValue"
            else -> null
        }

    /**
     * @return whether or not this ModelClass represents a void
     */
    abstract val isVoid: Boolean

    /**
     * If this represents a class, the super class that it extends is returned. If this
     * represents an interface, the interface that this extends is returned.
     * `null` is returned if this is not a class or interface, such as an int, or
     * if it is java.lang.Object or an interface that does not extend any other type.
     *
     * @return The class or interface that this ModelClass extends or null.
     */
    abstract val superclass: ModelClass?

    /**
     * @return A String representation of the class or interface that this represents, not
     * including any type arguments.
     */
    open val canonicalName: String by lazy(LazyThreadSafetyMode.NONE) {
        erasure().toJavaCode()
    }

    /**
     * @return The class or interface name of this type or the primitive type if it isn't a
     * reference type.
     */
    val simpleName: String by lazy(LazyThreadSafetyMode.NONE) {
        canonicalName.substringAfterLast('.')
    }

    /**
     * Since when this class is available. Important for Binding expressions so that we don't
     * call non-existing APIs when setting UI.
     *
     * @return The SDK_INT where this method was added. If it is not a framework method, should
     * return 1.
     */
    open val minApi: Int by lazy(LazyThreadSafetyMode.NONE) {
        SdkUtil.get().getMinApi(this)
    }

    /**
     * Returns the JNI description of the method which can be used to lookup it in SDK.
     * @see TypeUtil
     */
    abstract val jniDescription: String

    /**
     * Returns a list of all abstract methods in the type.
     */
    val abstractMethods: List<ModelMethod> by lazy(LazyThreadSafetyMode.NONE) {
        allMethods.filter {
            it.isAbstract
        }
    }

    val isIncomplete: Boolean
        get() {
            if (isTypeVar || isWildcard) {
                return true
            }
            val typeArgs = typeArguments
            if (typeArgs != null) {
                for (typeArg in typeArgs) {
                    if (typeArg.isIncomplete) {
                        return true
                    }
                }
            }
            return false
        }

    /**
     * @return the list of fields in the class and all its superclasses.
     */
    abstract val allFields: List<ModelField>

    /**
     * @return the list of methods in the class and all its superclasses.
     */
    abstract val allMethods: List<ModelMethod>

    // implementation only so that PSI model doesn't break
    open val typeName: TypeName
        get() = toJavaCode().toTypeName(false)

    val isKotlinUnit by lazy(LazyThreadSafetyMode.NONE) {
        "kotlin.Unit" == typeName.toString()
    }

    /**
     * Provides the java code that should be generated for this class when it is used as the declared type
     * of a variable.
     *
     * In data binding, a user declares a type when defining a `&lt;variable&gt;` tag, and they may
     * omit the generic parameter there, e.g. declaring `&lt;variable type="MyGeneric"&gt;` when the
     * actual class is `MyGeneric&lt;T: Foo&gt;`. In that case, we need to keep the type-erased version
     * for public APIs, which gets returned by [toJavaCode]. However, when we declare a variable with
     * the type, e.g. for internal implementations, we need to add the type information,
     * e.g. `MyGeneric&lt;Foo&gt;`, which is returned by this method.
     *
     * See: b/139738910, b/123409929
     */

    open fun toDeclarationCode() = toJavaCode()

    abstract fun toJavaCode(): String

    /**
     * @return whether or not this ModelClass type extends ViewStub.
     */
    val extendsViewStub by lazy(LazyThreadSafetyMode.NONE) {
        ModelAnalyzer.getInstance().viewStubType!!.isAssignableFrom(this)
    }

    /**
     * When this is a boxed type, such as Integer, this will return the unboxed value,
     * such as int. If this is not a boxed type, this is returned.
     *
     * @return The unboxed type of the class that this ModelClass represents or this if it isn't a
     * boxed type.
     */
    abstract fun unbox(): ModelClass

    /**
     * When this is a primitive type, such as boolean, this will return the boxed value,
     * such as Boolean. If this is not a primitive type, this is returned.
     *
     * @return The boxed type of the class that this ModelClass represents or this if it isn't a
     * primitive type.
     */
    abstract fun box(): ModelClass

    /**
     * Returns whether or not the type associated with `that` can be assigned to
     * the type associated with this ModelClass. If this and that only require boxing or unboxing
     * then true is returned.
     *
     * @param that the ModelClass to compare.
     * @return true if `that` requires only boxing or if `that` is an
     * implementation of or subclass of `this`.
     */
    abstract fun isAssignableFrom(that: ModelClass?): Boolean

    /**
     * Returns an array containing all public methods (or protected if allowProtected is true)
     * on the type represented by this ModelClass with the name `name` and can
     * take the passed-in types as arguments. This will also work if the arguments match
     * VarArgs parameter.
     *
     * @param name The name of the method to find.
     * @param args The types that the method should accept.
     * @param staticOnly Whether only static methods should be returned or both instance methods
     * and static methods are valid.
     * @param allowProtected true if the method can be protected as well as public.
     * @param unwrapObservableFields true if the method should check for auto-unwrapping the
     * observable field.
     *
     * @return An array containing all public methods with the name `name` and taking
     * `args` parameters.
     */
    private fun getMethods(name: String, args: List<ModelClass>, staticOnly: Boolean,
                           allowProtected: Boolean, unwrapObservableFields: Boolean): List<ModelMethod> {
        return allMethods.filter { method ->
            (method.isPublic || (allowProtected && method.isProtected))
                    && (!staticOnly || method.isStatic)
                    && name == method.name
                    && method.acceptsArguments(args, unwrapObservableFields)
        }
    }

    /**
     * Returns all public instance methods with the given name and number of parameters.
     *
     * @param name The name of the method to find.
     * @param numParameters The number of parameters that the method should take
     * @return An array containing all public methods with the given name and number of parameters.
     */
    fun getMethods(name: String, numParameters: Int): List<ModelMethod> {
        return allMethods.filter { method ->
            method.isPublic &&
                    !method.isStatic &&
                    name == method.name &&
                    method.parameterTypes.size == numParameters
        }
    }

    /**
     * Returns the public method with the name `name` with the parameters that
     * best match args. `staticOnly` governs whether a static or instance method
     * will be returned. If no matching method was found, null is returned.
     *
     * @param name The method name to find
     * @param args The arguments that the method should accept
     * @param staticOnly true if the returned method must be static or false if it does not
     * matter.
     * @param allowProtected true if the method can be protected as well as public.
     * @param unwrapObservableFields true if the method should check for auto-unwrapping the
     * observable field.
     */
    @JvmOverloads
    fun getMethod(name: String,
                  args: List<ModelClass>,
                  staticOnly: Boolean,
                  allowProtected: Boolean,
                  unwrapObservableFields: Boolean = false
    ): ModelMethod? {
        val methods = getMethods(name = name,
                args = args,
                staticOnly = staticOnly,
                allowProtected = allowProtected,
                unwrapObservableFields = unwrapObservableFields)
        L.d("looking methods for %s. static only ? %s . method count: %d", name, staticOnly,
                methods.size)
        for (method in methods) {
            L.d("method: %s, %s", method.name, method.isStatic)
        }
        if (methods.isEmpty()) {
            return null
        }
        var bestMethod = methods[0]
        for (i in 1 until methods.size) {
            if (methods[i].isBetterArgMatchThan(bestMethod, args)) {
                bestMethod = methods[i]
            }
        }
        return bestMethod
    }

    /**
     * Returns this class type without any generic type arguments.
     * @return this class type without any generic type arguments.
     */
    abstract fun erasure(): ModelClass

    /**
     * Returns the getter method or field that the name refers to.
     * @param name The name of the field or the body of the method name -- can be name(),
     * getName(), or isName().
     * @param staticOnly Whether this should look for static methods and fields or instance
     * versions
     * @return the getter method or field that the name refers to or null if none can be found.
     */
    fun findGetterOrField(name: String, staticOnly: Boolean): Callable? {
        if ("length" == name && isArray) {
            return Callable(Type.FIELD, name, null,
                    ModelAnalyzer.getInstance().loadPrimitive("int"), 0, 0, null, null)
        }
        val capitalized = StringUtils.capitalize(name)
        val methodNames = arrayOf("get" + capitalized!!, "is$capitalized", name)
        for (methodName in methodNames) {
            val methods = getMethods(methodName, ArrayList(), staticOnly, false, false)
            for (method in methods) {
                if (method.isPublic && (!staticOnly || method.isStatic) &&
                        !method.getReturnType(Arrays.asList(*method.parameterTypes)).isVoid) {
                    var flags = DYNAMIC
                    if (method.isStatic) {
                        flags = flags or STATIC
                    }
                    val bindable: BindableCompat?
                    if (method.isBindable) {
                        flags = flags or CAN_BE_INVALIDATED
                        bindable = method.bindableAnnotation
                    } else {
                        // if method is not bindable, look for a backing field
                        val backingField = getField(name, true, method.isStatic)
                        L.d("backing field for method %s is %s", method.name,
                                if (backingField == null) "NOT FOUND" else backingField.name)
                        if (backingField != null && backingField.isBindable) {
                            flags = flags or CAN_BE_INVALIDATED
                            bindable = backingField.bindableAnnotation
                        } else {
                            bindable = null
                        }
                    }
                    val setterMethod = findSetter(method, name)
                    val setterName = setterMethod?.name
                    return Callable(Type.METHOD, methodName,
                            setterName, method.getReturnType(null), method.parameterTypes.size,
                            flags, method, bindable)
                }
            }
        }

        // could not find a method. Look for a public field
        var publicField: ModelField?
        if (staticOnly) {
            publicField = getField(name, false, true)
        } else {
            // first check non-static
            publicField = getField(name, false, false)
            if (publicField == null) {
                // check for static
                publicField = getField(name, false, true)
            }
        }
        if (publicField == null) {
            return null
        }
        val fieldType = publicField.fieldType
        var flags = 0
        var setterFieldName: String? = name
        if (publicField.isStatic) {
            flags = flags or STATIC
        }
        if (!publicField.isFinal) {
            setterFieldName = null
            flags = flags or DYNAMIC
        }
        if (publicField.isBindable) {
            flags = flags or CAN_BE_INVALIDATED
        }
        return Callable(Callable.Type.FIELD, name, setterFieldName, fieldType, 0, flags, null,
                publicField.bindableAnnotation)
    }

    fun findInstanceGetter(name: String): ModelMethod? {
        val capitalized = StringUtils.capitalize(name)
        val methodNames = arrayOf("get" + capitalized!!, "is$capitalized", name)
        for (methodName in methodNames) {
            val methods = getMethods(methodName, ArrayList(), false, false, false)
            for (method in methods) {
                if (method.isPublic && !method.isStatic &&
                        !method.getReturnType(Arrays.asList(*method.parameterTypes)).isVoid) {
                    return method
                }
            }
        }
        return null
    }

    private fun getField(name: String, allowPrivate: Boolean, isStatic: Boolean): ModelField? {
        val fields = allFields
        for (field in fields) {
            val nameMatch = name == field.name || name == stripFieldName(field.name)
            if (nameMatch && field.isStatic == isStatic &&
                    (allowPrivate || field.isPublic)) {
                return field
            }
        }
        return null
    }

    private fun findSetter(getter: ModelMethod, originalName: String): ModelMethod? {
        val capitalized = StringUtils.capitalize(originalName)
        val possibleNames: Array<String>
        possibleNames = when {
            originalName == getter.name -> arrayOf(originalName, "set" + capitalized!!)
            getter.name.startsWith("is") -> arrayOf("set" + capitalized!!, "setIs$capitalized")
            else -> arrayOf("set" + capitalized!!)
        }
        for (name in possibleNames) {
            val methods = findMethods(name, getter.isStatic)
            val param = getter.getReturnType(null)
            for (method in methods) {
                val parameterTypes = method.parameterTypes
                if (parameterTypes != null && parameterTypes.size == 1 &&
                        parameterTypes[0] == param &&
                        method.isStatic == getter.isStatic) {
                    return method
                }
            }
        }
        return null
    }

    /**
     * Finds public methods that matches the given name exactly. These may be resolved into
     * listener methods during Expr.resolveListeners.
     */
    fun findMethods(name: String, staticOnly: Boolean): List<ModelMethod> {
        return allMethods.filter { method ->
            method.isPublic &&
                    method.name == name &&
                    (!staticOnly || method.isStatic)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other is ModelClass) {
            val thisTypeName = typeName
            val thatTypeName = other.typeName
            return thisTypeName == thatTypeName
        }
        return false
    }

    companion object {
        @JvmField
        val BOX_MAPPING = mapOf(
                Int::class.javaPrimitiveType!! to java.lang.Integer::class.java,
                Long::class.javaPrimitiveType!! to java.lang.Long::class.java,
                Short::class.javaPrimitiveType!! to java.lang.Short::class.java,
                Byte::class.javaPrimitiveType!! to java.lang.Byte::class.java,
                Char::class.javaPrimitiveType!! to java.lang.Character::class.java,
                Double::class.javaPrimitiveType!! to java.lang.Double::class.java,
                Float::class.javaPrimitiveType!! to java.lang.Float::class.java,
                Boolean::class.javaPrimitiveType!! to java.lang.Boolean::class.java
        )

        private fun stripFieldName(fieldName: String): String {
            // TODO: Make this configurable through IntelliJ
            if (fieldName.length > 2) {
                val start = fieldName[2]
                if (fieldName.startsWith("m_") && Character.isJavaIdentifierStart(start)) {
                    return Character.toLowerCase(start) + fieldName.substring(3)
                }
            }
            if (fieldName.length > 1) {
                val start = fieldName[1]
                val fieldIdentifier = fieldName[0]
                val strip: Boolean
                strip = if (fieldIdentifier == '_') {
                    true
                } else fieldIdentifier == 'm' && Character.isJavaIdentifierStart(start) &&
                        !Character.isLowerCase(start)
                if (strip) {
                    return Character.toLowerCase(start) + fieldName.substring(2)
                }
            }
            return fieldName
        }
    }
}
