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

import android.databinding.annotationprocessor.BindableBag
import android.databinding.tool.CompilerArguments
import android.databinding.tool.LibTypes
import android.databinding.tool.ext.L
import android.databinding.tool.ext.N
import android.databinding.tool.ext.S
import android.databinding.tool.ext.T
import android.databinding.tool.ext.stripNonJava
import android.databinding.tool.reflection.ModelAnalyzer
import android.databinding.tool.store.GenClassInfoLog
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import java.util.Locale
import java.util.HashMap
import javax.lang.model.element.Modifier
import kotlin.collections.ArrayList
import kotlin.collections.List
import kotlin.collections.MutableSet
import kotlin.collections.chunked
import kotlin.collections.emptyList
import kotlin.collections.filter
import kotlin.collections.first
import kotlin.collections.flatMap
import kotlin.collections.forEach
import kotlin.collections.forEachIndexed
import kotlin.collections.getOrPut
import kotlin.collections.isNotEmpty
import kotlin.collections.map
import kotlin.collections.mapIndexed
import kotlin.collections.mutableMapOf
import kotlin.collections.plus
import kotlin.collections.sortedBy
import kotlin.collections.sumBy

class BindingMapperWriterV2(genClassInfoLog: GenClassInfoLog,
                            compilerArgs: CompilerArguments,
                            libTypes: LibTypes,
                            modulePackages: MutableSet<String>) {
    companion object {
        private val VIEW = ClassName
                .get("android.view", "View")
        private val OBJECT = ClassName
                .get("java.lang", "Object")
        private val RUNTIME_EXCEPTION = ClassName
                .get("java.lang", "RuntimeException")
        private val ILLEGAL_ARG_EXCEPTION = ClassName
                .get("java.lang", "IllegalArgumentException")
        private val STRING = ClassName
                .get("java.lang", "String")
        private val INTEGER = ClassName
                .get("java.lang", "Integer")
        private const val LAYOUT_ID_LOOKUP_MAP_NAME = "INTERNAL_LAYOUT_ID_LOOKUP"
        private const val IMPL_CLASS_NAME = "DataBinderMapperImpl"
        private val SPARSE_INT_ARRAY =
                ClassName.get("android.util", "SparseIntArray")
        private val SPARSE_ARRAY =
                ClassName.get("android.util", "SparseArray")
        private val HASH_MAP = ClassName.get(HashMap::class.java)

        @JvmStatic
        fun createMapperQName(modulePackage: String) = "$modulePackage.$IMPL_CLASS_NAME"

        /**
         * The method that converts a local layout id into a view binding might get fairly large
         * due to the switch statement. This constant controls how many layouts are handled in each
         * function. The main entry point basically finds which function can respond to a given
         * id and calls it directly.
         */
        private const val GET_VIEW_BINDING_CHUNK_SIZE = 50

        /**
         * Java has a limit on the # of lines in a code block (function). This constant controls
         * how many statements we add into a code block. [addChunkedStaticBlock] method uses
         * it to divide long static statement blocks into smaller methods if necessary.
         */
        private const val METHOD_BODY_CHUNK_SIZE = 500
    }

    private val rClassMap = mutableMapOf<String, ClassName>()
    private val viewDataBinding = ClassName.bestGuess(libTypes.viewDataBinding)
    private val bindingComponent = ClassName.bestGuess(libTypes.dataBindingComponent)
    private val dataBinderMapper: ClassName = ClassName.bestGuess(libTypes.dataBinderMapper)
    private val testOverride: ClassName = ClassName.get(
            libTypes.bindingPackage,
            MergedBindingMapperWriter.TEST_CLASS_NAME)


    val pkg: String
    val className: String
    // all of the binding layout to binding code mapping we have in this module.
    private val allMappings: List<LocalizedMapping>
    // binding mappings in chunks so that we don't generate giant methods.
    private val chunkedMappings: List<List<LocalizedMapping>>

    init {
        val generateAsTest = compilerArgs.isTestVariant && compilerArgs.isApp
        if (generateAsTest) {
            pkg = testOverride.packageName()
            className = testOverride.simpleName()
        } else {
            pkg = compilerArgs.modulePackage
            className = IMPL_CLASS_NAME
        }
        /**
         * Layout ids might be non-final while generating the mapper for a library.
         * For that case, we generate an internal lookup table that converts an R file into a local
         * known field value.
         */
        val localizedLayoutIdMap = mutableMapOf<String, LocalizedMapping>()

        fun getLocalizedLayoutId(layoutName: String, info: GenClassInfoLog.GenClass)
                : LocalizedMapping {
            if (localizedLayoutIdMap.containsKey(layoutName)) {
                throw IllegalArgumentException("cannot have multiple info containing $layoutName")
            }
            return localizedLayoutIdMap.getOrPut(layoutName) {
                val fieldName = "LAYOUT_${layoutName.stripNonJava().toUpperCase(Locale.US)}"
                // must be > 0
                val id = localizedLayoutIdMap.size + 1
                val spec = FieldSpec.builder(TypeName.INT, fieldName,
                        Modifier.FINAL, Modifier.STATIC, Modifier.PRIVATE)
                        .initializer(L, id)
                        .build()
                LocalizedMapping(
                        layoutName = layoutName,
                        localId = id,
                        localIdField = spec,
                        genClass = info)
            }
        }
        allMappings = genClassInfoLog.mappings()
                .entries
                .sortedBy {
                    it.key
                }
                .map {
                    getLocalizedLayoutId(it.key, it.value)
                }
                .sortedBy { it.localId }
        chunkedMappings = allMappings.chunked(GET_VIEW_BINDING_CHUNK_SIZE)
    }

    val qualifiedName = "$pkg.$className"
    private val dependencyModulePackages = modulePackages.filter {
        it != pkg
    }

    private fun getRClass(pkg: String): ClassName {
        return rClassMap.getOrPut(pkg) {
            ClassName.get(pkg, "R")
        }
    }

    /**
     * Representation of a layout to generated class mapping that also has a localId which is only
     * valid within this class. Because R files are not final during compilation, we use a class
     * local id to identify these and map from R to this id in initialization.
     */
    data class LocalizedMapping(val localId: Int,
                                val layoutName: String,
                                val localIdField: FieldSpec,
                                val genClass: GenClassInfoLog.GenClass)


    fun write(brValueLookup: BindableBag.BRMapping): TypeSpec
            = TypeSpec.classBuilder(className).apply {
        superclass(dataBinderMapper)
        addModifiers(Modifier.PUBLIC)
        if (ModelAnalyzer.getInstance().hasGeneratedAnnotation) {
            addAnnotation(AnnotationSpec.builder(ClassName.get("javax.annotation", "Generated"))
                .addMember("value", S, "Android Data Binding").build())
        }
        addMethods(generateGetViewDataBinder())
        addMethod(generateGetViewArrayDataBinder())
        addMethod(generateGetLayoutId())
        addMethod(generateConvertBrIdToString())
        addMethod(generateCollectDependencies())
        addType(generateInnerBrLookup(brValueLookup))
        addType(generateInnerLayoutIdLookup())
        // must write this at the end
        createLocalizedLayoutIds(this)
    }.build()

    private fun createLocalizedLayoutIds(builder: TypeSpec.Builder) {
        /**
         * generated code looks like:
         * private static final SparseIntArray INTERNAL_LAYOUT_ID_LOOKUP =
         *         new SparseIntArray(99);
         *     static {
         *         INTERNAL_LAYOUT_ID_LOOKUP.put(
         *             foo.bar.R.layout.generic_view, LAYOUT_GENERICVIEW);
         *         ... //for all layouts
         *     }
         */
        builder.apply {
            // create fields
            allMappings.forEach {
                addField(it.localIdField)
            }
            // now create conversion hash map
            // reverse map from ids to values
            val lookupType = SPARSE_INT_ARRAY
            val lookupField = FieldSpec.builder(
                    lookupType,
                    LAYOUT_ID_LOOKUP_MAP_NAME)
                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL, Modifier.STATIC)
                    .initializer("new $T($L)", lookupType, allMappings.size)
                    .build()
            addField(lookupField)
            addChunkedStaticBlock(
                    methodPrefix = "internalPopulateLayoutIdLookup",
                    items = allMappings
            ) {
                addStatement("$N.put($L.layout.$L, $N)", lookupField,
                        getRClass(it.genClass.modulePackage), it.layoutName, it.localIdField)
            }
        }
    }

    private fun generateInnerBrLookup(brValueLookup: BindableBag.BRMapping) = TypeSpec
            .classBuilder("InnerBrLookup").apply {
                /**
                 * generated code looks like:
                 * static final SparseArray<String> sKeys = new SparseArray<String>(214);
                 * static {
                 *     sKeys.put(foo.bar.BR._all, "_all");
                 *     ....//for all BRs
                 */
                addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                val keysTypeName = ParameterizedTypeName.get(
                        SPARSE_ARRAY,
                        STRING
                )
                val keysField = FieldSpec.builder(keysTypeName, "sKeys").apply {
                    addModifiers(Modifier.STATIC, Modifier.FINAL)
                    initializer("new $T($L)", keysTypeName, brValueLookup.size)
                }.build()
                addField(keysField)
                addChunkedStaticBlock(
                        methodPrefix = "internalPopulateBRLookup",
                        items = brValueLookup.props
                ) {
                    addStatement("$N.put($L, $S)",
                            keysField,
                            it.second,
                            it.first)
                }
            }.build()

    private fun generateConvertBrIdToString() = MethodSpec
            .methodBuilder("convertBrIdToString").apply {
                addModifiers(Modifier.PUBLIC)
                addAnnotation(Override::class.java)
                val idParam = ParameterSpec.builder(TypeName.INT, "localId").build()
                addParameter(idParam)
                returns(STRING)
                val tmpResult = "tmpVal"
                addStatement("$T $L = InnerBrLookup.sKeys.get($N)", STRING, tmpResult, idParam)
                addStatement("return $L", tmpResult)
            }.build()

    private fun generateInnerLayoutIdLookup() = TypeSpec
            .classBuilder("InnerLayoutIdLookup").apply {
                /**
                 * generated code looks like:
                 * static final HashMap<String, Integer> sKeys = new HashMap<String, Integer>(214);
                 * static {
                 *     sKeys.put("layout/main_0", foo.bar.R.layout);
                 *     ....//for all data binding layouts
                 */
                addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                val keysTypeName = ParameterizedTypeName.get(
                        HASH_MAP,
                        STRING,
                        INTEGER
                )
                val keysField = FieldSpec.builder(keysTypeName, "sKeys").apply {
                    addModifiers(Modifier.STATIC, Modifier.FINAL)
                    initializer("new $T($L)",
                            keysTypeName,
                            allMappings.sumBy { it.genClass.implementations.size })
                }.build()
                addField(keysField)
                val items = allMappings.flatMap { mapping ->
                    val rClass = getRClass(mapping.genClass.modulePackage)
                    mapping.genClass.implementations.map { impl ->
                        Triple(mapping, rClass, impl)
                    }
                }
                addChunkedStaticBlock(
                        methodPrefix = "internalPopulateLayoutIdLookup",
                        items = items
                ) {
                    val (mapping, rClass, impl) = it
                    addStatement("$N.put($S, $L)",
                            keysField,
                            "${impl.tag}_0",
                            "$rClass.layout.${mapping.layoutName}")
                }
            }.build()

    private fun generateGetLayoutId() = MethodSpec.methodBuilder("getLayoutId").apply {
        addModifiers(Modifier.PUBLIC)
        addAnnotation(Override::class.java)
        val tagParam = ParameterSpec.builder(STRING, "tag").build()
        addParameter(tagParam)
        returns(TypeName.INT)
        beginControlFlow("if ($N == null)", tagParam).apply {
            addStatement("return 0")
        }.endControlFlow()
        val tmpResult = "tmpVal"
        addStatement("$T $L = InnerLayoutIdLookup.sKeys.get($N)", INTEGER, tmpResult, tagParam)
        addStatement("return $L == null ? 0 : $L", tmpResult, tmpResult)
    }.build()

    /**
     * generates a switch case for the given mappings.
     * looks like:
     * switch(internalId) {
     *   case  LAYOUT_foo: {
     *     if ("layout/layout_foo_0".equals(tag)) {
     *       return new Layout58BindingImpl(component, view);
     *     }
     *     throw new IllegalArgumentException("The tag for layout_58 is invalid. Received: " + tag);
     *   }
     *   case  LAYOUT_bar: {
     *     ...
     *   }
     *   ...
     * }
     *
     * This allows us to create partial (smaller) methods if the app has too many layouts.
     */
    private fun appendSwitchGetForViewDataBinder(methodSpec: MethodSpec.Builder,
                                                 mappings: List<LocalizedMapping>,
                                                 componentParam: ParameterSpec,
                                                 viewParam: ParameterSpec,
                                                 tagField: String,
                                                 internalIdField: String) {
        methodSpec.apply {
            beginControlFlow("switch($L)", internalIdField).apply {
                mappings.forEach { localizedLayout ->
                    val layoutIdField = localizedLayout.localIdField
                    val info = localizedLayout.genClass
                    val layoutName = localizedLayout.layoutName
                    beginControlFlow("case  $N:", layoutIdField).apply {
                        // we should check the tag to decide which layout we need to inflate
                        // we do it here because it is ok to pass a non-data-binding layout
                        info.implementations.forEach {
                            beginControlFlow("if ($S.equals($L))",
                                    "${it.tag}_0", tagField).apply {
                                val binderTypeName = ClassName.bestGuess(it.qualifiedName)
                                if (it.merge) {
                                    addStatement("return new $T($N, new $T[]{$N})",
                                            binderTypeName, componentParam, VIEW, viewParam)
                                } else {
                                    addStatement("return new $T($N, $N)",
                                            binderTypeName, componentParam, viewParam)
                                }
                            }.endControlFlow()
                        }
                        addStatement("throw new $T($S + $L)", ILLEGAL_ARG_EXCEPTION,
                                "The tag for $layoutName is invalid. Received: ", tagField)
                    }.endControlFlow()
                }
            }.endControlFlow()
        }
    }

    private fun generateGetViewDataBinder(): List<MethodSpec> {
        val createSubMethods = chunkedMappings.size > 1
        // create inner methods only if we have more than 1 chunk
        val chunks = if (!createSubMethods) emptyList() else chunkedMappings
                .mapIndexed { index, mappings ->
                    MethodSpec.methodBuilder("internalGetViewDataBinding$index").apply {
                        addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                        returns(viewDataBinding)
                        val componentParam = ParameterSpec
                                .builder(bindingComponent, "component").build()
                        val viewParam = ParameterSpec.builder(VIEW, "view").build()
                        val internalIdParam = ParameterSpec
                                .builder(TypeName.INT, "internalId").build()
                        val tagParam = ParameterSpec.builder(OBJECT, "tag").build()
                        addParameter(componentParam)
                        addParameter(viewParam)
                        addParameter(internalIdParam)
                        addParameter(tagParam)
                        appendSwitchGetForViewDataBinder(
                                methodSpec = this,
                                mappings = mappings,
                                componentParam = componentParam,
                                viewParam = viewParam,
                                tagField = tagParam.name,
                                internalIdField = internalIdParam.name
                        )
                        addStatement("return null")
                    }.build()
                }
        val impl = MethodSpec.methodBuilder("getDataBinder").apply {
            addModifiers(Modifier.PUBLIC)
            addAnnotation(Override::class.java)
            returns(viewDataBinding)
            val componentParam = ParameterSpec.builder(bindingComponent, "component").build()
            val viewParam = ParameterSpec.builder(VIEW, "view").build()
            val layoutIdParam = ParameterSpec.builder(TypeName.INT, "layoutId").build()
            addParameter(componentParam)
            addParameter(viewParam)
            addParameter(layoutIdParam)
            val localizedLayoutId = "localizedLayoutId"
            val tagField = "tag"
            addStatement("$T $L = $L.get($N)",
                    TypeName.INT,
                    localizedLayoutId,
                    LAYOUT_ID_LOOKUP_MAP_NAME,
                    layoutIdParam)
            // output looks like:
            // localize layout id from R.layout.XY to local private constant
            // switch(layoutId)
            //    case known_layout_id
            //             verify, generate impl and return
            beginControlFlow("if($L > 0)", localizedLayoutId).apply {
                addStatement("final $T $L = $N.getTag()", OBJECT, tagField, viewParam)
                beginControlFlow("if($L == null)", tagField).apply {
                    addStatement("throw new $T($S)", RUNTIME_EXCEPTION,
                            "view must have a tag")
                }.endControlFlow()
                if (createSubMethods) {
                    // only call the chunk that might have it
                    val methodIndexField = "methodIndex"
                    addStatement("// find which method will have it. -1 is necessary because" +
                            "first id starts with 1")
                    addStatement("$T $L = ($N - 1) / $L",
                            TypeName.INT,
                            methodIndexField,
                            localizedLayoutId,
                            GET_VIEW_BINDING_CHUNK_SIZE)
                    beginControlFlow("switch($N)", methodIndexField).apply {
                        chunks.forEachIndexed { index, methodSpec ->
                            beginControlFlow("case $L:", index).apply {
                                addStatement("return $N($N, $N, $L, $L)",
                                        methodSpec,
                                        componentParam,
                                        viewParam,
                                        localizedLayoutId,
                                        tagField)
                            }
                            endControlFlow()
                        }
                    }
                    endControlFlow()

                } else if (chunkedMappings.isNotEmpty()) {
                    appendSwitchGetForViewDataBinder(
                            methodSpec = this,
                            mappings = chunkedMappings.first(),
                            componentParam = componentParam,
                            viewParam = viewParam,
                            tagField = tagField,
                            internalIdField = localizedLayoutId
                    )
                }
            }.endControlFlow()
            addStatement("return null")
        }.build()
        return chunks + impl
    }

    private fun generateGetViewArrayDataBinder() = MethodSpec.methodBuilder("getDataBinder")
            .apply {
                addModifiers(Modifier.PUBLIC)
                addAnnotation(Override::class.java)
                returns(viewDataBinding)
                val componentParam = ParameterSpec
                        .builder(bindingComponent, "component").build()
                val viewParam = ParameterSpec
                        .builder(ArrayTypeName.of(VIEW), "views").build()
                val layoutIdParam = ParameterSpec
                        .builder(TypeName.INT, "layoutId").build()
                addParameter(componentParam)
                addParameter(viewParam)
                addParameter(layoutIdParam)
                beginControlFlow("if($N == null || $N.length == 0)",
                        viewParam, viewParam).apply {
                    addStatement("return null")
                }.endControlFlow()

                val localizedLayoutId = "localizedLayoutId"
                addStatement("$T $L = $L.get($N)",
                        TypeName.INT,
                        localizedLayoutId,
                        LAYOUT_ID_LOOKUP_MAP_NAME,
                        layoutIdParam)
                // output looks like:
                // localize layout id from R.layout.XY to local private constant
                // switch(layoutId)
                //    case known_layout_id
                //             verify, generate impl and return

                beginControlFlow("if($L > 0)", localizedLayoutId).apply {
                    addStatement("final $T tag = $N[0].getTag()", OBJECT, viewParam)
                    beginControlFlow("if(tag == null)").apply {
                        addStatement("throw new $T($S)", RUNTIME_EXCEPTION,
                                "view must have a tag")
                    }.endControlFlow()
                    beginControlFlow("switch($N)", localizedLayoutId).apply {
                        allMappings.forEach { localizedMapping ->
                            val mergeImpls = localizedMapping.genClass.implementations
                                    .filter { it.merge }
                            if (mergeImpls.isNotEmpty()) {
                                val layoutIdField = localizedMapping.localIdField
                                val layoutName = localizedMapping.layoutName
                                beginControlFlow("case $N:", layoutIdField).apply {
                                    mergeImpls.forEach {
                                        beginControlFlow("if($S.equals(tag))",
                                                "${it.tag}_0").apply {
                                            val binderTypeName = ClassName.bestGuess(
                                                    it.qualifiedName)
                                            addStatement("return new $T($N, $N)",
                                                    binderTypeName, componentParam, viewParam)
                                        }.endControlFlow()
                                    }
                                    addStatement("throw new $T($S + tag)", ILLEGAL_ARG_EXCEPTION,
                                            "The tag for $layoutName is invalid. Received: ")
                                }.endControlFlow()
                            }
                        }
                    }.endControlFlow()
                }.endControlFlow()

                addStatement("return null")
            }.build()

    // generate the code that will load all dependencies that are possibly not visible to the app
    private fun generateCollectDependencies() : MethodSpec {
        return MethodSpec.methodBuilder("collectDependencies").apply {
            addAnnotation(Override::class.java)
            addModifiers(Modifier.PUBLIC)
            val listType = ParameterizedTypeName.get(
                    ClassName.get(ArrayList::class.java),
                    dataBinderMapper
            )
            returns(ParameterizedTypeName.get(
                    ClassName.get(List::class.java),
                    dataBinderMapper
            ))
            addStatement("$T result = new $T($L)",
                    listType, listType, dependencyModulePackages.size)
            dependencyModulePackages.forEach {
                val mapperType = ClassName.get(it, IMPL_CLASS_NAME)
                addStatement("result.add(new $T())", mapperType)
            }
            addStatement("return result")
        }.build()
    }

    /**
     * Helper function to generate static code blocks that avoid java method limit.
     * Based on the given chunk size, this either generates a code section like:
     *
     * static {
     *    // handle item 1
     *    // handle item 2 ...
     * }
     *
     * or if the list if bigger than [chunkSize], it generates something like:
     *
     * private static void prefix1() {
     *    // handle item 1
     *    // handle item 2
     *    ...
     * }
     *
     * private static void prefix2() {
     *    // handle item 1000
     *    // handle item 1001
     *    ...
     * }
     *
     * static {
     *     prefix1()
     *     prefix2()
     *     ...
     * }
     */
    private fun <T> TypeSpec.Builder.addChunkedStaticBlock(
            methodPrefix:String,
            chunkSize : Int = METHOD_BODY_CHUNK_SIZE,
            items: List<T>,
            addItem : CodeBlock.Builder.(T) -> Unit
    ) {
        if (items.isEmpty()) {
            return
        }
        if (items.size <= chunkSize) {
            // dont create any methods
            addStaticBlock(CodeBlock.builder()
                    .apply {
                        items.forEach {
                            this.addItem(it)
                        }
                    }.build())
        } else {
            // divide it into methods to avoid java method size limit
            val methods = items.chunked(chunkSize).mapIndexed { index, chunk ->
                MethodSpec.methodBuilder("$methodPrefix$index")
                        .addModifiers(Modifier.STATIC, Modifier.PRIVATE)
                        .addCode(CodeBlock.builder().apply {
                            chunk.forEach {
                                this.addItem(it)
                            }
                        }.build())
                        .build()
            }
            methods.forEach {
                this.addMethod(it)
            }
            // add 1 static block to call all
            addStaticBlock(CodeBlock.builder().apply {
                methods.forEach {
                    addStatement("$N()", it)
                }
            }.build())
        }
    }
}
