/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.databinding.tool.store

import android.databinding.tool.store.SetterStore.AccessorKey
import android.databinding.tool.store.SetterStore.Intermediate
import android.databinding.tool.store.SetterStore.InverseDescription
import android.databinding.tool.store.SetterStore.InverseMethodDescription
import android.databinding.tool.store.SetterStore.MethodDescription
import android.databinding.tool.store.SetterStore.MultiAttributeSetter
import android.databinding.tool.store.SetterStore.MultiValueAdapterKey
import android.databinding.tool.util.L
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.TreeMap

/**
 * Class that holds information about binding adapters.
 *
 * Even though this class implements {@link Intermediate}, it is non-serializable on purpose. Any
 * future changes in {@link Intermediate} format should be done in this object, by incrementing
 * `version` number but in a JSON safe way. The {@link #upgrade()} method can use the version field
 * to make any further changes on the result object.
 * <p>
 * When this class is serialized, it uses JSON.
 * <p>
 * All this effort is done to keep our output consistent so that gradle cache can do its work.
 * <p>
 * This class holds an internal copy for everything added after it is created to ensure that we
 * don't accumulate all adapters from dependencies. When we want to serialize, SetterStore
 * serializes that inner class instead (to persist only the stuff from current module).
 */
@Suppress("ReplacePutWithAssignment")
internal class BindingAdapterStore : Intermediate {
    @Suppress("unused")
    @field:SerializedName("version")
    private var version = 5
    // Intermediate V1
    @field:SerializedName("adapterMethods")
    private val adapterMethods = TreeMap<String, TreeMap<SetterStore.AccessorKey, MethodDescription>>()
    @field:SerializedName("renamedMethods")
    private val renamedMethods = TreeMap<String, TreeMap<String, MethodDescription>>()
    @field:SerializedName("conversionMethods")
    private val conversionMethods = TreeMap<String, TreeMap<String, MethodDescription>>()
    @field:SerializedName("untaggableTypes")
    private val untaggableTypes = TreeMap<String, String>()
    @field:SerializedName("multiValueAdapters")
    private val multiValueAdapters = TreeMap<MultiValueAdapterKey, MethodDescription>()
    // Intermediate V2
    @field:SerializedName("inverseAdapters")
    private val inverseAdapters = TreeMap<String, TreeMap<AccessorKey, InverseDescription>>()
    @field:SerializedName("inverseMethods")
    private val inverseMethods = TreeMap<String, TreeMap<String, InverseDescription>>()
    // Intermediate V3
    @field:SerializedName("twoWayMethods")
    private val twoWayMethods = TreeMap<InverseMethodDescription, String>()

    /**
     * Adapter store that keeps only stuff in this module. Created after this class is sealed.
     */
    @field:Transient
    private var currentModuleStore: BindingAdapterStore? = null
    private val useAndroidX: Boolean

    constructor(
            stores: MutableList<Intermediate>,
            previousStores: List<BindingAdapterStore>,
            useAndroidX: Boolean
    ) : this(useAndroidX) {
        previousStores.forEach {
            merge(it.upgrade() as BindingAdapterStore)
        }
        stores.forEach {
            merge(it.upgrade() as BindingAdapterStore)
        }
    }


    // we only care about androidX for the current process' store, others can stay unprocessed
    constructor(v3: SetterStore.IntermediateV3) : this(false) {
        merge(adapterMethods, v3.adapterMethods)
        merge(renamedMethods, v3.renamedMethods)
        merge(conversionMethods, v3.conversionMethods)
        merge(inverseAdapters, v3.inverseAdapters)
        merge(inverseMethods, v3.inverseMethods)
        untaggableTypes.putAll(v3.untaggableTypes)
        multiValueAdapters.putAll(v3.multiValueAdapters)
        twoWayMethods.putAll(v3.twoWayMethods)
    }

    private constructor(useAndroidX: Boolean) {
        this.useAndroidX = useAndroidX
    }

    private fun String.androidSupportArtifact() = this.startsWith("android.databinding.adapters")

    private fun <K1, K2, V : MethodDescription> Map<K1, Map<K2, V>>
            .filterOutAndroidSupport(): Map<K1, Map<K2, V>> {
        if (!useAndroidX) {
            return this
        }
        return mapValues {
            it.value.filterOutAndroidSupportFromMap()
        }
    }

    private fun <K, V : MethodDescription> Map<K, V>
            .filterOutAndroidSupportFromMap(): Map<K, V> {
        if (!useAndroidX) {
            return this
        }
        return filterValues {
            !it.type.androidSupportArtifact()
        }
    }

    private fun <K : InverseMethodDescription, V> Map<K, V>
            .filterOutAndroidSupportFromMapByKeys(): Map<K, V> {
        if (!useAndroidX) {
            return this
        }
        return filterKeys {
            !it.type.androidSupportArtifact()
        }
    }

    /**
     * Sets this as the instance used by SetterStore which means it will have modifications.
     */
    fun setAsMainStore() {
        currentModuleStore = BindingAdapterStore(useAndroidX)
    }

    private fun merge(other: BindingAdapterStore) {
        merge(adapterMethods, other.adapterMethods.filterOutAndroidSupport())
        merge(renamedMethods, other.renamedMethods.filterOutAndroidSupport())
        merge(conversionMethods, other.conversionMethods.filterOutAndroidSupport())
        multiValueAdapters.putAll(other.multiValueAdapters.filterOutAndroidSupportFromMap())
        untaggableTypes.putAll(if (useAndroidX) {
            other.untaggableTypes.filterNot {
                it.key.androidSupportArtifact() || it.value.androidSupportArtifact()
            }
        } else {
            other.untaggableTypes
        })
        merge(inverseAdapters, other.inverseAdapters.filterOutAndroidSupport())
        merge(inverseMethods, other.inverseMethods.filterOutAndroidSupport())
        twoWayMethods.putAll(other.twoWayMethods.filterOutAndroidSupportFromMapByKeys())
    }

    /**
     * Returns all event attributes from inverse descriptions (from inverse adapters and inverse
     * methods)
     */
    fun collectInverseEvents(): MutableSet<String> {
        val result = mutableSetOf<String>()
        inverseAdapters
                .values
                .forEach {
                    it.values.forEach {
                        result.add(it.event)
                    }
                }
        inverseMethods
                .values
                .forEach {
                    it.values.forEach {
                        result.add(it.event)
                    }
                }
        return result
    }

    fun addRenamedMethod(
            attribute: String,
            declaringClass: String,
            desc: MethodDescription) {
        renamedMethods
                .getOrPut(attribute) { TreeMap() }
                .put(declaringClass, desc)
        currentModuleStore?.addRenamedMethod(attribute, declaringClass, desc)
        L.d("STORE addmethod desc %s", desc)
    }

    fun addInverseBindingMethod(
            attribute: String,
            declaringClass: String,
            desc: InverseDescription) {
        inverseMethods
                .getOrPut(attribute) { TreeMap() }
                .put(declaringClass, desc)
        currentModuleStore?.addInverseBindingMethod(attribute, declaringClass, desc)
        L.d("STORE addInverseMethod desc %s", desc)
    }

    fun addInverseMethod(
            from: InverseMethodDescription,
            to: InverseMethodDescription
    ) {
        val storedToName = twoWayMethods[from]
        if (storedToName != null && to.method != storedToName) {
            throw IllegalArgumentException(String.format(
                    "InverseMethod from %s to %s does not match expected method '%s'",
                    from, to, storedToName))
        }
        val storedFromName = twoWayMethods[to]
        if (storedFromName != null && from.method != storedFromName) {
            throw IllegalArgumentException(String.format(
                    "InverseMethod from %s to %s does not match expected method '%s'",
                    to, from, storedFromName))
        }
        twoWayMethods.put(from, to.method)
        twoWayMethods.put(to, from.method)
        currentModuleStore?.addInverseMethod(from, to)
    }

    fun addBindingAdapter(
            attribute: String,
            key: AccessorKey,
            desc: MethodDescription) {
        adapterMethods
                .getOrPut(attribute) { TreeMap() }
                .also {
                    it[key]?.let { existing ->
                        if (existing != desc) {
                            L.w("Binding adapter %s already exists for %s! Overriding %s" +
                                    " with %s", key, attribute, existing, desc)
                        }
                    }
                }
                .put(key, desc)
        currentModuleStore?.addBindingAdapter(attribute, key, desc)
    }

    fun addInverseBindingAdapter(
            attribute: String,
            key: AccessorKey,
            desc: InverseDescription) {
        inverseAdapters
                .getOrPut(attribute) { TreeMap() }
                .also {
                    it[key]?.let { existing ->
                        if (existing != desc) {
                            L.w("Inverse adapter %s already exists for %s! Overriding %s" +
                                    " with %s", key, attribute, existing, desc)
                        }
                    }
                }
                .put(key, desc)
        currentModuleStore?.addInverseBindingAdapter(attribute, key, desc)
    }

    fun addMultiValueAdapter(
            key: MultiValueAdapterKey,
            methodDescription: MethodDescription) {
        multiValueAdapters.put(key, methodDescription)
        currentModuleStore?.addMultiValueAdapter(key, methodDescription)
    }

    fun addUntaggableType(
            types: Array<String>,
            declaredType: String
    ) {
        types.forEach {
            untaggableTypes.put(it, declaredType)
        }
        currentModuleStore?.addUntaggableType(types, declaredType)
    }

    fun addConversionMethod(
            fromType: String,
            toType: String,
            methodDescription: MethodDescription) {
        conversionMethods
                .getOrPut(fromType) { TreeMap() }
                .put(toType, methodDescription)
        currentModuleStore?.addConversionMethod(fromType, toType, methodDescription)
    }

    override fun upgrade(): Intermediate {
        // upgrade based on version
        return this
    }

    fun getCurrentModuleStore() = currentModuleStore

    fun clear(classes: Set<String>) {
        val removedAccessorKeys = ArrayList<AccessorKey>()
        for (adapters in adapterMethods.values) {
            for (key in adapters.keys) {
                val description = adapters[key]
                if (classes.contains(description?.type)) {
                    removedAccessorKeys.add(key)
                }
            }
            removeFromMap(adapters, removedAccessorKeys)
        }

        val removedRenamed = ArrayList<String>()
        for (renamed in renamedMethods.values) {
            for (key in renamed.keys) {
                if (classes.contains(renamed[key]?.type)) {
                    removedRenamed.add(key)
                }
            }
            removeFromMap(renamed, removedRenamed)
        }

        val removedConversions = ArrayList<String>()
        for (convertTos in conversionMethods.values) {
            for (toType in convertTos.keys) {
                val methodDescription = convertTos[toType]
                if (classes.contains(methodDescription?.type)) {
                    removedConversions.add(toType)
                }
            }
            removeFromMap(convertTos, removedConversions)
        }

        val removedUntaggable = ArrayList<String>()
        for (typeName in untaggableTypes.keys) {
            if (classes.contains(untaggableTypes[typeName])) {
                removedUntaggable.add(typeName)
            }
        }
        removeFromMap(untaggableTypes, removedUntaggable)
        currentModuleStore?.clear(classes)
    }

    /**
     * Runs the given filter on all multiValueAdapters and collects all non-null results from
     * the filter.
     */
    fun findMultiValueAdapters(
            filter: (MultiValueAdapterKey, MethodDescription) -> MultiAttributeSetter?)
            : ArrayList<MultiAttributeSetter> {
        val matched = arrayListOf<MultiAttributeSetter>()
        multiValueAdapters.forEach {
            filter(it.key, it.value)?.let {
                matched.add(it)
            }
        }
        return matched
    }

    fun getInverseMethod(description: InverseMethodDescription) = twoWayMethods[description]

    fun isUntaggable(viewType: String) = untaggableTypes.containsKey(viewType)

    /**
     * Finds the list of renamed methods that has the given attribute and pass the given filter.
     */
    fun findRenamed(
            attribute: String,
            filter: (String) -> Boolean
    ): MutableList<String> {
        val maps = renamedMethods[attribute]
        return maps?.let {
            maps.entries
                    .asSequence()
                    .filter {
                        filter(it.key)
                    }.map {
                        it.value.method
                    }.toMutableList()
        } ?: arrayListOf()
    }

    /**
     * Runs the given function on all inverse methods that has the given attribute.
     */
    fun forEachInverseMethod(
            attribute: String,
            func: (String, InverseDescription) -> Unit?
    ) {
        inverseMethods[attribute]?.forEach {
            func(it.key, it.value)
        }
    }

    /**
     * Runs the given function on all conversion methods that has the given attribute.
     * If the function returns a non-null value, returns that value.
     */
    fun findFirstConversionMethod(
            func: (String, Map<String, MethodDescription>) -> MethodDescription?
    ): MethodDescription? {
        conversionMethods.forEach {
            func(it.key, it.value)?.let {
                return it
            }
        }
        return null
    }

    /**
     * Runs the given function on all adapter methods that has the given attribute.
     */
    fun forEachAdapterMethod(
            attribute: String,
            func: (AccessorKey, MethodDescription) -> Unit?
    ) {
        adapterMethods[attribute]?.forEach {
            func(it.key, it.value)
        }
    }

    /**
     * Runs the given function on all inverse adapters that has the given attribute.
     */
    fun forEachInverseAdapterMethod(
            attribute: String,
            func: (AccessorKey, InverseDescription) -> Unit?
    ) {
        inverseAdapters[attribute]?.forEach {
            func(it.key, it.value)
        }
    }

    private fun <K, V> removeFromMap(map: MutableMap<K, V>, keys: MutableList<K>) {
        for (key in keys) {
            map.remove(key)
        }
        keys.clear()
    }

    fun createInstanceAdapters(): HashMap<String, MutableList<String>> {
        val adapters = HashSet<String>()
        for (methods in adapterMethods.values) {
            for (method in methods.values) {
                if (!method.isStatic) {
                    adapters.add(method.type)
                }
            }
        }
        for (method in multiValueAdapters.values) {
            if (!method.isStatic) {
                adapters.add(method.type)
            }
        }
        for (methods in inverseAdapters.values) {
            for (method in methods.values) {
                if (!method.isStatic) {
                    adapters.add(method.type)
                }
            }
        }
        val result = HashMap<String, MutableList<String>>()
        for (adapter in adapters) {
            val simpleName = simpleName(adapter)
            var list: MutableList<String>? = result[simpleName]
            if (list == null) {
                list = ArrayList()
                result[simpleName] = list
            }
            list.add(adapter)
        }
        for (list in result.values) {
            if (list.size > 1) {
                list.sort()
            }
        }
        return result
    }

    @Throws(IOException::class)
    private fun writeObject(@Suppress("UNUSED_PARAMETER") oos: ObjectOutputStream) {
        throw UnsupportedOperationException("use gson to serialize this")
    }

    @Throws(ClassNotFoundException::class, IOException::class)
    private fun readObject(@Suppress("UNUSED_PARAMETER") ois: ObjectInputStream) {
        throw UnsupportedOperationException("use gson to serialize this")
    }

    companion object {
        @JvmStatic
        fun simpleName(className: String): String {
            val dotIndex = className.lastIndexOf('.')
            return if (dotIndex < 0) {
                className
            } else {
                className.substring(dotIndex + 1)
            }
        }

        private inline fun <reified K, reified V, reified D> merge(first: TreeMap<K, TreeMap<V, D>>,
                                                                   second: Map<K, Map<V, D>>) {
            second.forEach { key, values ->
                values.let { secondItems ->
                    val firstVals = first
                            .getOrPut(key) { TreeMap() }
                    secondItems.forEach { v, d ->
                        firstVals.putIfAbsent(v, d)
                    }
                }
            }
        }
    }
}