/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.databinding.tool.writer

import android.databinding.tool.Binding
import android.databinding.tool.BindingTarget
import android.databinding.tool.CallbackWrapper
import android.databinding.tool.InverseBinding
import android.databinding.tool.LayoutBinder
import android.databinding.tool.LibTypes
import android.databinding.tool.expr.Expr
import android.databinding.tool.expr.ExprModel
import android.databinding.tool.expr.FieldAccessExpr
import android.databinding.tool.expr.IdentifierExpr
import android.databinding.tool.expr.LambdaExpr
import android.databinding.tool.expr.ListenerExpr
import android.databinding.tool.expr.ResourceExpr
import android.databinding.tool.expr.TernaryExpr
import android.databinding.tool.expr.localizeGlobalVariables
import android.databinding.tool.expr.shouldLocalizeInCallbacks
import android.databinding.tool.expr.toCode
import android.databinding.tool.ext.br
import android.databinding.tool.ext.capitalizeUS
import android.databinding.tool.ext.decapitalizeUS
import android.databinding.tool.ext.lazyProp
import android.databinding.tool.ext.parseXmlResourceReference
import android.databinding.tool.ext.stripNonJava
import android.databinding.tool.ext.versionedLazy
import android.databinding.tool.processing.ErrorMessages
import android.databinding.tool.reflection.ModelAnalyzer
import android.databinding.tool.reflection.ModelClass
import android.databinding.tool.util.L
import android.databinding.tool.util.Preconditions
import java.util.*

enum class Scope {
    GLOBAL,
    FIELD,
    METHOD,
    FLAG,
    EXECUTE_PENDING_METHOD,
    CONSTRUCTOR_PARAM,
    CALLBACK;
    companion object {
        var currentScope = GLOBAL;
        private val scopeStack = arrayListOf<Scope>()
        fun enter(scope : Scope) {
            scopeStack.add(currentScope)
            currentScope = scope
        }

        fun exit() {
            currentScope = scopeStack.removeAt(scopeStack.size - 1)
        }

        fun reset() {
            scopeStack.clear()
            currentScope = GLOBAL
        }
    }
}

class ExprModelExt {
    val usedFieldNames = hashMapOf<Scope, MutableSet<String>>();
    init {
        Scope.values().forEach { usedFieldNames[it] = hashSetOf<String>() }
    }

    internal val forceLocalize = hashSetOf<Expr>()

    val localizedFlags = arrayListOf<FlagSet>()

    fun localizeFlag(set : FlagSet, name:String) : FlagSet {
        localizedFlags.add(set)
        val result = getUniqueName(name, Scope.FLAG, false)
        set.localName = result
        return set
    }

    @Suppress("UNUSED_PARAMETER") // TODO fix in a followup CL
    fun getUniqueName(base : String, scope : Scope, isPublic : kotlin.Boolean) : String {
        var candidateBase = base
        var candidate = candidateBase
        if (scope == Scope.CALLBACK || scope == Scope.EXECUTE_PENDING_METHOD) {
            candidate = candidate.decapitalizeUS()
        }
        val checkFields = scope != Scope.METHOD
        var i = 0
        while (usedFieldNames[scope]!!.contains(candidate)
                || (checkFields && usedFieldNames[Scope.FIELD]!!.contains(candidate))) {
            i ++
            candidate = candidateBase + i
        }
        usedFieldNames[scope]!!.add(candidate)
        return candidate
    }
}

fun ModelClass.defaultValue() = ModelAnalyzer.getInstance().getDefaultValue(toJavaCode())
fun ExprModel.getUniqueFieldName(base : String, isPublic : kotlin.Boolean) : String = ext.getUniqueName(base, Scope.FIELD, isPublic)
fun ExprModel.getUniqueMethodName(base : String, isPublic : kotlin.Boolean) : String = ext.getUniqueName(base, Scope.METHOD, isPublic)
fun ExprModel.getConstructorParamName(base : String) : String = ext.getUniqueName(base, Scope.CONSTRUCTOR_PARAM, false)
fun ExprModel.localizeFlag(set : FlagSet, base : String) : FlagSet = ext.localizeFlag(set, base)

val Expr.needsLocalField by lazyProp { expr: Expr ->
    expr.canBeEvaluatedToAVariable() && !(expr.isVariable() && !expr.isUsed) && (expr.isDynamic || expr is ResourceExpr)
}

fun Expr.isForcedToLocalize() = model.ext.forceLocalize.contains(this)

// not necessarily unique. Uniqueness is solved per scope
val BindingTarget.readableName by lazyProp { target: BindingTarget ->
    if (target.id == null) {
        "boundView" + indexFromTag(target.tag)
    } else {
        target.id.parseXmlResourceReference().name.stripNonJava()
    }
}

fun BindingTarget.superConversion(variable : String) : String {
    return if (resolvedType != null && resolvedType.extendsViewStub) {
        val libTypes = ModelAnalyzer.getInstance().libTypes
        "new ${libTypes.viewStubProxy}((android.view.ViewStub) $variable)"
    } else if(resolvedType != null && !resolvedType.isViewDataBinding && resolvedType.isViewBinding) {
        // For included layouts, DataBinding used to rely on view tags we attach at compile time.
        // For ViewBinding layouts, there is no such tag also no global inflate method
        // (like DataBindingUtil.inflate). To workaround that issue, for <include> layouts with
        // ViewBinding (no data binding), we find them as views and then bind here.
        // see b/150397979 for details
        "($variable != null) ? " +
          "${resolvedType.toJavaCode()}.bind((android.view.View) $variable) " +
          ": null"

    } else {
        "($interfaceClass) $variable"
    }
}

val BindingTarget.fieldName : String by lazyProp { target: BindingTarget ->
    val name: String
    val isPublic: Boolean
    if (target.id == null) {
        name = "m${target.readableName}"
        isPublic = false
    } else {
        name = target.readableName
        isPublic = true
    }
    target.model.getUniqueFieldName(name, isPublic)
}

val BindingTarget.androidId by lazyProp { target: BindingTarget ->
    val reference = target.id.parseXmlResourceReference()
    if (reference.namespace == "android") {
        "android.R.id.${reference.name}"
    } else {
        "R.id.${reference.name}"
    }
}

val BindingTarget.interfaceClass by lazyProp { target: BindingTarget ->
    if (target.resolvedType != null && target.resolvedType.extendsViewStub) {
        val libTypes = ModelAnalyzer.getInstance().libTypes
        libTypes.viewStubProxy
    } else {
        target.interfaceType
    }
}

val BindingTarget.constructorParamName by lazyProp { target: BindingTarget ->
    target.model.getConstructorParamName(target.readableName)
}

val BindingTarget.isDataBindingLayout:Boolean
  get() = isBinder && resolvedType.isViewDataBinding


// not necessarily unique. Uniqueness is decided per scope
val Expr.readableName by lazyProp { expr: Expr ->
    val stripped = expr.uniqueKey.stripNonJava()
    L.d("readableUniqueName for [%s] %s is %s", System.identityHashCode(expr), expr, stripped)
    stripped
}

val Expr.fieldName by lazyProp { expr: Expr ->
    expr.model.getUniqueFieldName("m${expr.readableName.capitalizeUS()}", false)
}

val InverseBinding.fieldName by lazyProp { inverseBinding: InverseBinding ->
    val targetName = inverseBinding.target.fieldName;
    val eventName = inverseBinding.eventAttribute.stripNonJava()
    inverseBinding.model.getUniqueFieldName("$targetName$eventName", false)
}

val Expr.listenerClassName by lazyProp { expr: Expr ->
    expr.model.getUniqueFieldName("${expr.resolvedType.simpleName}Impl", false)
}

val Expr.oldValueName by lazyProp { expr: Expr ->
    expr.model.getUniqueFieldName("mOld${expr.readableName.capitalizeUS()}", false)
}

fun Expr.scopedName() : String = when(Scope.currentScope) {
    Scope.CALLBACK -> callbackLocalName
    else -> executePendingLocalName
}

val Expr.callbackLocalName by lazyProp { expr: Expr ->
    if (expr.shouldLocalizeInCallbacks()) "${expr.model.ext.getUniqueName(expr.readableName, Scope.CALLBACK, false)}"
    else expr.toCode().generate()
}

val Expr.executePendingLocalName by lazyProp { expr: Expr ->
    if (expr.isDynamic || expr.needsLocalField) "${expr.model.ext.getUniqueName(expr.readableName, Scope.EXECUTE_PENDING_METHOD, false)}"
    else expr.toCode().generate()
}

val Expr.setterName by lazyProp { expr: Expr ->
    expr.model.getUniqueMethodName("set${expr.readableName.capitalizeUS()}", true)
}

val Expr.onChangeName by lazyProp { expr: Expr ->
    expr.model.getUniqueMethodName("onChange${expr.readableName.capitalizeUS()}", false)
}

val Expr.getterName by lazyProp { expr: Expr ->
    expr.model.getUniqueMethodName("get${expr.readableName.capitalizeUS()}", true)
}

fun Expr.isVariable() = this is IdentifierExpr && this.isDynamic

val Expr.dirtyFlagSet by lazyProp { expr: Expr ->
    FlagSet(expr.invalidFlags, expr.model.flagBucketCount)
}

val Expr.invalidateFlagSet by lazyProp { expr: Expr ->
    FlagSet(expr.id)
}

val Expr.shouldReadFlagSet by versionedLazy { expr: Expr ->
    FlagSet(expr.shouldReadFlags, expr.model.flagBucketCount)
}

val Expr.shouldReadWithConditionalsFlagSet by versionedLazy { expr: Expr ->
    FlagSet(expr.shouldReadFlagsWithConditionals, expr.model.flagBucketCount)
}

val Expr.conditionalFlags by lazyProp { expr: Expr ->
    arrayListOf(FlagSet(expr.getRequirementFlagIndex(false)),
            FlagSet(expr.getRequirementFlagIndex(true)))
}

fun Binding.toAssignmentCode() : String {
    val fieldName: String
    if (this.target.viewClass.
            equals(this.target.interfaceType)) {
        fieldName = "this.${this.target.fieldName}"
    } else {
        fieldName = "((${this.target.viewClass}) this.${this.target.fieldName})"
    }
    return this.toJavaCode(fieldName, "this.mBindingComponent")
}

val LayoutBinder.requiredComponent by lazyProp { layoutBinder: LayoutBinder ->
    val requiredFromBindings = layoutBinder.
            bindingTargets.
            flatMap { it.bindings }.
            firstOrNull { it.bindingAdapterInstanceClass != null }?.bindingAdapterInstanceClass
    val requiredFromInverse = layoutBinder.
            bindingTargets.
            flatMap { it.inverseBindings }.
            firstOrNull { it.bindingAdapterInstanceClass != null }?.bindingAdapterInstanceClass
    requiredFromBindings ?: requiredFromInverse
}

fun Expr.getRequirementFlagSet(expected : Boolean) : FlagSet = conditionalFlags[if(expected) 1 else 0]

fun FlagSet.notEmpty(cb : (suffix : String, value : Long) -> Unit) {
    buckets.withIndex().forEach {
        if (it.value != 0L) {
            cb(getWordSuffix(it.index), buckets[it.index])
        }
    }
}

fun getWordSuffix(wordIndex : Int) : String {
    return if(wordIndex == 0) "" else "_$wordIndex"
}

fun FlagSet.localValue(bucketIndex : Int) =
        if (localName == null) binaryCode(bucketIndex)
        else "$localName${getWordSuffix(bucketIndex)}"

fun FlagSet.binaryCode(bucketIndex : Int) = longToBinary(buckets[bucketIndex])


fun longToBinary(l : Long) = "0x${java.lang.Long.toHexString(l)}L"

fun <T> FlagSet.mapOr(other : FlagSet, cb : (suffix : String, index : Int) -> T) : List<T> {
    val min = Math.min(buckets.size, other.buckets.size)
    val result = arrayListOf<T>()
    for (i in 0..(min - 1)) {
        // if these two can match by any chance, call the callback
        if (intersect(other, i)) {
            result.add(cb(getWordSuffix(i), i))
        }
    }
    return result
}

fun indexFromTag(tag : String) : kotlin.Int {
    val startIndex : kotlin.Int
    if (tag.startsWith("binding_")) {
        startIndex = "binding_".length;
    } else {
        startIndex = tag.lastIndexOf('_') + 1
    }
    return Integer.parseInt(tag.substring(startIndex))
}

class LayoutBinderWriter(val layoutBinder : LayoutBinder, val libTypes: LibTypes) {
    val hasBaseBinder = layoutBinder.enableV2() || layoutBinder.hasVariations()
    val model = layoutBinder.model
    val indices = HashMap<BindingTarget, kotlin.Int>()
    val mDirtyFlags by lazy {
        val fs = FlagSet(BitSet(), model.flagBucketCount);
        Arrays.fill(fs.buckets, -1)
        fs.isDynamic = true
        model.localizeFlag(fs, "mDirtyFlags")
        fs
    }

    val className = layoutBinder.implementationName

    val baseClassName = "${layoutBinder.className}"

    val includedBinders by lazy {
        layoutBinder.bindingTargets.filter { it.isDataBindingLayout }
    }

    val variables by lazy {
        model.exprMap.values.filterIsInstance(IdentifierExpr::class.java).filter { it.isVariable() }
    }

    val callbacks by lazy {
        model.exprMap.values.filterIsInstance(LambdaExpr::class.java)
    }

    fun write(minSdk: kotlin.Int): String {
        Scope.reset()
        layoutBinder.resolveWhichExpressionsAreUsed()
        calculateIndices();
        return kcode("package ${layoutBinder.`package`};") {
            nl("import ${layoutBinder.modulePackage}.R;")
            nl("import ${layoutBinder.modulePackage}.BR;")
            nl("import ${libTypes.nonNull};")
            nl("import ${libTypes.nullable};")
            nl("import android.view.View;")
            val classDeclaration = if (hasBaseBinder) {
                "$className extends $baseClassName"
            } else {
                "$className extends ${libTypes.viewDataBinding}"
            }
            nl("@SuppressWarnings(\"unchecked\")")
            annotateWithGenerated()
            block("public class $classDeclaration ${buildImplements()}") {
                nl(declareIncludeViews())
                nl(declareViews())
                nl(declareVariables())
                nl(declareBoundValues())
                nl(declareListeners())
                try {
                    Scope.enter(Scope.GLOBAL)
                    nl(declareInverseBindingImpls())
                } finally {
                    Scope.exit()
                }
                nl(declareConstructor(minSdk))
                nl(declareInvalidateAll())
                nl(declareHasPendingBindings())
                nl(declareSetVariable())
                nl(variableSettersAndGetters())
                nl(declareSetLifecycleOwnerOverride())
                nl(onFieldChange())
                try {
                    Scope.enter(Scope.GLOBAL)
                    nl(executePendingBindings())
                } finally {
                    Scope.exit()
                }

                nl(declareListenerImpls())
                try {
                    Scope.enter(Scope.CALLBACK)
                    nl(declareCallbackImplementations())
                } finally {
                    Scope.exit()
                }

                nl(declareDirtyFlags())
                if (!hasBaseBinder) {
                    nl(declareFactories())
                }
                nl(flagMapping())
                nl("//end")
            }
        }.generate()
    }

    fun buildImplements(): String {
        return if (callbacks.isEmpty()) {
            ""
        } else {
            "implements " + callbacks.map { it.callbackWrapper.cannonicalListenerName }.distinct().joinToString(", ")
        }
    }

    fun calculateIndices() {
        val taggedViews = layoutBinder.bindingTargets.filter {
            it.isUsed && it.tag != null && !it.isDataBindingLayout
        }
        taggedViews.filter {
            it.includedLayout == null
        }.forEach {
            indices.put(it, indexFromTag(it.tag))
        }
        // put any included layouts after the normal views
        taggedViews.filter {
            it.includedLayout != null
        }.forEach {
            indices.put(it, maxIndex() + 1)
        }
        val indexStart = maxIndex() + 1
        layoutBinder.bindingTargets.filter {
            it.isUsed && !taggedViews.contains(it)
        }.withIndex().forEach {
            indices.put(it.value, it.index + indexStart)
        }
    }

    fun declareIncludeViews() = kcode("") {
        nl("@Nullable")
        nl("private static final ${libTypes.viewDataBinding}.IncludedLayouts sIncludes;")
        nl("@Nullable")
        nl("private static final android.util.SparseIntArray sViewsWithIds;")
        nl("static {") {
            val hasBinders = layoutBinder.bindingTargets.any { it.isUsed && it.isDataBindingLayout }
            if (!hasBinders) {
                tab("sIncludes = null;")
            } else {
                val numBindings = layoutBinder.bindingTargets.filter { it.isUsed }.count()
                tab("sIncludes = new ${libTypes.viewDataBinding}.IncludedLayouts($numBindings);")
                val includeMap = HashMap<BindingTarget, ArrayList<BindingTarget>>()
                layoutBinder.bindingTargets.filter { it.isUsed && it.isDataBindingLayout }.forEach {
                    val includeTag = it.tag
                    val parent = layoutBinder.bindingTargets.firstOrNull {
                        it.isUsed && !it.isBinder && includeTag.equals(it.tag)
                    } ?: throw IllegalStateException("Could not find parent of include file")
                    var list = includeMap[parent]
                    if (list == null) {
                        list = ArrayList<BindingTarget>()
                        includeMap.put(parent, list)
                    }
                    list.add(it)
                }

                // sort keys for consistent output
                includeMap.keys.sortedBy { indices[it] }.forEach {
                    val index = indices[it]
                    tab("sIncludes.setIncludes($index, ") {
                        tab("new String[] {${
                        includeMap[it]!!.map {
                            "\"${it.includedLayout}\""
                        }.joinToString(", ")
                        }},")
                        tab("new int[] {${
                        includeMap[it]!!.map {
                            "${indices[it]}"
                        }.joinToString(", ")
                        }},")
                        tab("new int[] {${
                        includeMap[it]!!.map {
                            (it.includedLayoutPackage ?: layoutBinder.modulePackage).let { pkg ->
                                "$pkg.R.layout.${it.includedLayout}"
                            }
                        }.joinToString(",\n                ")
                        }});")
                    }
                }
            }
            val viewsWithIds = layoutBinder.bindingTargets.filter {
                it.isUsed && !it.isDataBindingLayout && (!it.supportsTag() || (it.id != null && (it.tag == null || it.includedLayout != null)))
            }
            if (viewsWithIds.isEmpty()) {
                tab("sViewsWithIds = null;")
            } else {
                tab("sViewsWithIds = new android.util.SparseIntArray();")
                viewsWithIds.forEach {
                    tab("sViewsWithIds.put(${it.androidId}, ${indices[it]});")
                }
            }
        }
        nl("}")
    }

    fun maxIndex(): kotlin.Int {
        val maxIndex = indices.values.max()
        if (maxIndex == null) {
            return -1
        } else {
            return maxIndex
        }
    }

    fun declareConstructor(minSdk: kotlin.Int) = kcode("") {
        val bindingCount = maxIndex() + 1
        val parameterType: String
        val superParam: String
        if (layoutBinder.isMerge) {
            parameterType = "View[]"
            superParam = "root[0]"
        } else {
            parameterType = "View"
            superParam = "root"
        }
        val rootTagsSupported = minSdk >= 14
        if (hasBaseBinder) {
            nl("")
            nl("public $className(@Nullable ${libTypes.dataBindingComponent} bindingComponent, @NonNull $parameterType root) {") {
                tab("this(bindingComponent, root, mapBindings(bindingComponent, root, $bindingCount, sIncludes, sViewsWithIds));")
            }
            nl("}")
            nl("private $className(${libTypes.dataBindingComponent} bindingComponent, $parameterType root, Object[] bindings) {") {
                tab("super(bindingComponent, $superParam, ${model.observables.size}") {
                    layoutBinder.sortedTargets.filter { it.id != null }.forEach {
                        tab(", ${fieldConversion(it)}")
                    }
                    tab(");")
                }
            }
        } else {
            nl("public $baseClassName(@NonNull ${libTypes.dataBindingComponent} bindingComponent, @NonNull $parameterType root) {") {
                tab("super(bindingComponent, $superParam, ${model.observables.size});")
                tab("final Object[] bindings = mapBindings(bindingComponent, root, $bindingCount, sIncludes, sViewsWithIds);")
            }
        }
        if (layoutBinder.requiredComponent != null) {
            tab("ensureBindingComponentIsNotNull(${layoutBinder.requiredComponent}.class);")
        }
        val taggedViews = layoutBinder.sortedTargets.filter { it.isUsed }
        taggedViews.forEach {
            if (!hasBaseBinder || it.id == null) {
                tab("this.${it.fieldName} = ${fieldConversion(it)};")
            }
            if (!it.isBinder) {
                if (it.resolvedType != null && it.resolvedType.extendsViewStub) {
                    tab("this.${it.fieldName}.setContainingBinding(this);")
                }
                if (it.supportsTag() && it.tag != null &&
                        (rootTagsSupported || it.tag.startsWith("binding_"))) {
                    val originalTag = it.originalTag;
                    var tagValue = "null"
                    if (originalTag != null && !originalTag.startsWith("@{")) {
                        tagValue = "\"$originalTag\""
                        if (originalTag.startsWith("@")) {
                            var packageName = layoutBinder.modulePackage
                            if (originalTag.startsWith("@android:")) {
                                packageName = "android"
                            }
                            val slashIndex = originalTag.indexOf('/')
                            val resourceId = originalTag.substring(slashIndex + 1)
                            tagValue = "this.${it.fieldName}.getResources().getString($packageName.R.string.$resourceId)"
                        }
                    }
                    if (it.includedLayout == null) {
                        tab("this.${it.fieldName}.setTag($tagValue);")
                    }
                } else if (it.tag != null && !it.tag.startsWith("binding_") &&
                        it.originalTag != null) {
                    L.e(ErrorMessages.ROOT_TAG_NOT_SUPPORTED, it.originalTag)
                }
            }
            if (it.isDataBindingLayout) {
                tab("setContainedBinding(this.${it.fieldName});")
            }
        }
        tab("setRootTag(root);")
        tab(declareCallbackInstances())
        tab("invalidateAll();");
        nl("}")
    }

    fun declareCallbackInstances() = kcode("// listeners") {
        callbacks.groupBy { it.callbackWrapper.minApi }
                .forEach {
                    if (it.key > 1) {
                        block("if(getBuildSdkInt() < ${it.key})") {
                            it.value.forEach { lambda ->
                                nl("${lambda.fieldName} = null;")
                            }
                        }
                        block("else") {
                            it.value.forEach { lambda ->
                                nl("${lambda.fieldName} = ${lambda.generateConstructor()};")
                            }
                        }
                    } else {
                        it.value.forEach { lambda ->
                            nl("${lambda.fieldName} = ${lambda.generateConstructor()};")
                        }
                    }
                }
    }

    fun declareCallbackImplementations() = kcode("// callback impls") {
        callbacks.groupBy { it.callbackWrapper }.forEach {
            val wrapper = it.key
            val lambdas = it.value
            // special case kotlin unit. b/78662035
            val returnKotlinUnit = wrapper.method.returnType.isKotlinUnit
            val shouldReturn = !wrapper.method.returnType.isVoid && !returnKotlinUnit
            if (shouldReturn) {
                lambdas.forEach {
                    it.callbackExprModel.ext.forceLocalize.add(it.expr)
                }
            }
            // b: 123260053
            fun checkCanReturn(
                lambda: LambdaExpr
            ) {
                if (shouldReturn) {
                    // we only check here for void or not instead of checking assignability
                    // The right thing would be to check assignability but we might break some
                    // existing users by adding more checks hence this is a simple workaround to
                    // detect a common problem which causes stack overflow due to missing value.
                    // everything else will be regular compilation errors for bad return type.
                    val canReturn = !lambda.expr.resolvedType.isVoid
                    if (!canReturn) {
                        L.e(
                            ErrorMessages.callbackReturnTypeMismatchError(
                                wrapper.method.name,
                                wrapper.method.returnType.toString(),
                                lambda.toString(),
                                lambda.expr.resolvedType.toString()
                            )
                        )
                    }
                }
            }
            block("public final ${wrapper.method.returnType.canonicalName} ${wrapper.listenerMethodName}(${wrapper.allArgsWithTypes()})") {
                Preconditions.check(lambdas.size > 0, "bindings list should not be empty")
                if (lambdas.size == 1) {
                    val lambda = lambdas[0]
                    lambda.inErrorScope {
                        checkCanReturn(lambda)
                        nl(lambda.callbackExprModel.localizeGlobalVariables(lambda))
                        nl(lambda.executionPath.toCode())
                        if (shouldReturn) {
                            nl("return ${lambda.expr.scopedName()};")
                        } else if (returnKotlinUnit) {
                            nl("return null;")
                        }
                    }
                } else {
                    block("switch(${CallbackWrapper.SOURCE_ID})") {
                        lambdas.forEach { lambda ->
                            lambda.inErrorScope {
                                checkCanReturn(lambda)
                                block("case ${lambda.callbackId}:") {
                                    nl(lambda.callbackExprModel.localizeGlobalVariables(lambda))
                                    nl(lambda.executionPath.toCode())
                                    when {
                                        shouldReturn -> nl("return ${lambda.expr.scopedName()};")
                                        returnKotlinUnit -> nl("return null;")
                                        else -> nl("break;")
                                    }
                                }
                            }
                        }
                        if (shouldReturn) {
                            block("default:") {
                                nl("return ${wrapper.method.returnType.defaultValue()};")
                            }
                        }
                    }
                }
            }
        }
    }

    fun fieldConversion(target: BindingTarget): String {
        if (!target.isUsed) {
            return "null"
        } else {
            val index = indices[target] ?: throw IllegalStateException("Unknown binding target")
            val variableName = "bindings[$index]"
            return target.superConversion(variableName)
        }
    }

    fun declareInvalidateAll() = kcode("") {
        nl("@Override")
        block("public void invalidateAll()") {
            val fs = FlagSet(layoutBinder.model.invalidateAnyBitSet,
                    layoutBinder.model.flagBucketCount);
            block("synchronized(this)") {
                for (i in (0..(mDirtyFlags.buckets.size - 1))) {
                    tab("${mDirtyFlags.localValue(i)} = ${fs.localValue(i)};")
                }
            }
            includedBinders.filter { it.isUsed }.forEach { binder ->
                nl("${binder.fieldName}.invalidateAll();")
            }
            nl("requestRebind();");
        }
    }

    fun declareHasPendingBindings() = kcode("") {
        nl("@Override")
        nl("public boolean hasPendingBindings() {") {
            if (mDirtyFlags.buckets.size > 0) {
                tab("synchronized(this) {") {
                    val flagCheck = 0.rangeTo(mDirtyFlags.buckets.size - 1).map {
                        "${mDirtyFlags.localValue(it)} != 0"
                    }.joinToString(" || ")
                    tab("if ($flagCheck) {") {
                        tab("return true;")
                    }
                    tab("}")
                }
                tab("}")
            }
            includedBinders.filter { it.isUsed }.forEach { binder ->
                tab("if (${binder.fieldName}.hasPendingBindings()) {") {
                    tab("return true;")
                }
                tab("}")
            }
            tab("return false;")
        }
        nl("}")
    }

    fun declareSetVariable() = kcode("") {
        nl("@Override")
        block("public boolean setVariable(int variableId, @Nullable Object variable) ") {
            nl("boolean variableSet = true;")
            variables.forEachIndexed { i, expr ->
                val elseStr: String
                if (i == 0) {
                    elseStr = ""
                } else {
                    elseStr = "else "
                }
                block("${elseStr}if (${expr.name.br()} == variableId)") {
                    nl("${expr.setterName}((${expr.resolvedType.toJavaCode()}) variable);")
                }
            }
            if (variables.isNotEmpty()) {
                block("else") {
                    nl("variableSet = false;")
                }
            }
            tab("return variableSet;")
        }
    }

    fun variableSettersAndGetters() = kcode("") {
        variables.forEach {
            if (it.userDefinedType != null) {
                val argType = it.resolvedType.toJavaCode()
                block("public void ${it.setterName}(${if (it.resolvedType.isPrimitive) "" else "@Nullable "}$argType ${it.readableName})") {
                    val used = it.isIsUsedInCallback || it.isUsed
                    if (used && it.isObservable) {
                        nl(it.getUpdateRegistrationCall(it.id, it.readableName))
                    }
                    nl("this.${it.fieldName} = ${it.readableName};")
                    if (used) {
                        // set dirty flags!
                        val flagSet = it.invalidateFlagSet
                        block("synchronized(this)") {
                            mDirtyFlags.mapOr(flagSet) { suffix, index ->
                                nl("${mDirtyFlags.localName}$suffix |= ${flagSet.localValue(index)};")
                            }
                        }
                        nl("notifyPropertyChanged(${it.name.br()});")
                        nl("super.requestRebind();")
                    }
                }
                // if there are variations or we are using v2, we'll use base class to generate the
                // fields and their getters
                if (!hasBaseBinder) {
                    nl("")
                    if (!it.resolvedType.isPrimitive) {
                        nl("@Nullable")
                    }
                    block("public $argType ${it.getterName}()") {
                        nl("return ${it.fieldName};")
                    }
                }
            }
        }
    }

    fun declareSetLifecycleOwnerOverride() = kcode("") {
        val includes = includedBinders.filter { it.isUsed }
        if (includes.isNotEmpty()) {
            nl("@Override")
            block("public void setLifecycleOwner(@Nullable ${libTypes.lifecycleOwner} lifecycleOwner)") {
                nl("super.setLifecycleOwner(lifecycleOwner);")
                includes.forEach { binder ->
                    nl("${binder.fieldName}.setLifecycleOwner(lifecycleOwner);");
                }
            }
        }
    }

    fun onFieldChange() = kcode("") {
        nl("@Override")
        nl("protected boolean onFieldChange(int localFieldId, Object object, int fieldId) {") {
            tab("switch (localFieldId) {") {
                model.observables.forEach {
                    tab("case ${it.id} :") {
                        tab("return ${it.onChangeName}((${it.resolvedType.toJavaCode()}) object, fieldId);")
                    }
                }
            }
            tab("}")
            tab("return false;")
        }
        nl("}")
        nl("")

        model.observables.forEach {
            block("private boolean ${it.onChangeName}(${it.resolvedType.toJavaCode()} ${it.readableName}, int fieldId)") {
                block("if (fieldId == ${"".br()})") {
                    val flagSet: FlagSet
                    if (it is FieldAccessExpr && it.resolvedType.observableGetterName != null) {
                        flagSet = it.bindableDependents.map { expr -> expr.invalidateFlagSet }
                                .foldRight(it.invalidateFlagSet) { l, r -> l.or(r) }
                    } else {
                        flagSet = it.invalidateFlagSet
                    }

                    block("synchronized(this)") {
                        mDirtyFlags.mapOr(flagSet) { suffix, index ->
                            tab("${mDirtyFlags.localName}$suffix |= ${flagSet.localValue(index)};")
                        }
                    }
                    nl("return true;")
                }

                val accessedFields: List<FieldAccessExpr> = it.parents.filterIsInstance(FieldAccessExpr::class.java)
                accessedFields.filter { it.isUsed && it.hasBindableAnnotations() }
                        .flatMap { expr -> expr.dirtyingProperties.map { Pair(it, expr) } }
                        .groupBy { it.first }
                        .forEach {
                            // If two expressions look different but resolve to the same method,
                            // we are not yet able to merge them. This is why we merge their
                            // flags below.
                            block("else if (fieldId == ${it.key})") {
                                block("synchronized(this)") {
                                    val flagSet = it.value.foldRight(FlagSet()) { l, r -> l.second.invalidateFlagSet.or(r) }

                                    mDirtyFlags.mapOr(flagSet) { _, index ->
                                        tab("${mDirtyFlags.localValue(index)} |= ${flagSet.localValue(index)};")
                                    }
                                }
                                nl("return true;")
                            }

                        }
                nl("return false;")
            }
            nl("")
        }
    }

    fun declareViews() = kcode("// views") {
        layoutBinder.sortedTargets.filter {it.isUsed && (!hasBaseBinder || it.id == null)}.forEach {
            val access = if (!hasBaseBinder && it.id != null) {
                "public"
            } else {
                "private"
            }
            nl(if (it.includedLayout == null) "@NonNull" else "@Nullable")
            nl("$access final ${it.interfaceClass} ${it.fieldName};")
        }
    }

    fun declareVariables() = kcode("// variables") {
        //if it has variations or we are using v2, fields are declared in the base class as well as
        // getters
        if (!hasBaseBinder) {
            variables.forEach {
                nl("@Nullable")
                nl("private ${it.resolvedType.toJavaCode()} ${it.fieldName};")
            }
        }
        callbacks.forEach {
            val wrapper = it.callbackWrapper
            if (!wrapper.klass.isPrimitive) {
                nl("@Nullable")
            }
            nl("private final ${wrapper.klass.canonicalName} ${it.fieldName}").app(";")
        }
    }

    fun declareBoundValues() = kcode("// values") {
        layoutBinder.sortedTargets.filter { it.isUsed }
                .flatMap { it.bindings }
                .filter { it.requiresOldValue() }
                .flatMap { it.componentExpressions.toList() }
                .groupBy { it }
                .forEach {
                    val expr = it.key
                    nl("private ${expr.resolvedType.toJavaCode()} ${expr.oldValueName};")
                }
    }

    fun declareListeners() = kcode("// listeners") {
        model.exprMap.values.filter {
            it is ListenerExpr
        }.groupBy { it }.forEach {
            val expr = it.key as ListenerExpr
            nl("private ${expr.listenerClassName} ${expr.fieldName};")
        }
    }

    fun declareInverseBindingImpls() = kcode("// Inverse Binding Event Handlers") {
        layoutBinder.sortedTargets.filter { it.isUsed }.forEach { target ->
            target.inverseBindings.forEach { inverseBinding ->
                val invClass: String
                val param: String
                if (inverseBinding.isOnBinder) {
                    invClass = libTypes.propertyChangedInverseListener
                    param = "BR.${inverseBinding.eventAttribute}"
                } else {
                    invClass = libTypes.inverseBindingListener
                    param = ""
                }
                block("private $invClass ${inverseBinding.fieldName} = new $invClass($param)") {
                    nl("@Override")
                    block("public void onChange()") {
                        if (inverseBinding.inverseExpr != null) {
                            val valueExpr = inverseBinding.variableExpr
                            val getterCall = inverseBinding.getterCall
                            nl("// Inverse of ${inverseBinding.expr}")
                            nl("//         is ${inverseBinding.inverseExpr}")
                            nl("${valueExpr.resolvedType.toJavaCode()} ${valueExpr.name} = ${getterCall.toJava("mBindingComponent", target.fieldName)};")
                            nl(inverseBinding.callbackExprModel.localizeGlobalVariables(valueExpr))
                            nl(inverseBinding.executionPath.toCode())
                        } else {
                            block("synchronized($className.this)") {
                                val flagSet = inverseBinding.chainedExpressions.fold(FlagSet(), { initial, expr ->
                                    initial.or(FlagSet(expr.id))
                                })
                                mDirtyFlags.mapOr(flagSet) { _, index ->
                                    tab("${mDirtyFlags.localValue(index)} |= ${flagSet.binaryCode(index)};")
                                }
                            }
                            nl("requestRebind();")
                        }
                    }
                }.app(";")
            }
        }
    }

    fun declareDirtyFlags() = kcode("// dirty flag") {
        model.ext.localizedFlags.forEach { flag ->
            flag.notEmpty { suffix, value ->
                nl("private")
                app(" ", if (flag.isDynamic) null else "static final");
                app(" ", " ${flag.type} ${flag.localName}$suffix = ${longToBinary(value)};")
            }
        }
    }

    fun flagMapping() = kcode("/* flag mapping") {
        if (model.flagMapping != null) {
            val mapping = model.flagMapping
            for (i in mapping.indices) {
                tab("flag $i (${longToBinary(1L + i)}): ${model.findFlagExpression(i)}")
            }
        }
        nl("flag mapping end*/")
    }

    fun executePendingBindings() = kcode("") {
        nl("@Override")
        block("protected void executeBindings()") {
            val tmpDirtyFlags = FlagSet(mDirtyFlags.buckets)
            tmpDirtyFlags.localName = "dirtyFlags";
            for (i in (0 until mDirtyFlags.buckets.size)) {
                nl("${tmpDirtyFlags.type} ${tmpDirtyFlags.localValue(i)} = 0;")
            }
            block("synchronized(this)") {
                for (i in (0 until mDirtyFlags.buckets.size)) {
                    nl("${tmpDirtyFlags.localValue(i)} = ${mDirtyFlags.localValue(i)};")
                    nl("${mDirtyFlags.localValue(i)} = 0;")
                }
            }
            model.pendingExpressions.filter { it.needsLocalField }.forEach {
                nl("${it.resolvedType.toDeclarationCode()} ${it.executePendingLocalName} = ${if (it.isVariable()) it.fieldName else it.defaultValue};")
            }
            L.d("writing executePendingBindings for %s", className)
            do {
                val batch = ExprModel.filterShouldRead(model.pendingExpressions)
                val justRead = arrayListOf<Expr>()
                L.d("batch: %s", batch)
                while (!batch.none()) {
                    val readNow = batch.filter { it.shouldReadNow(justRead) }
                    if (readNow.isEmpty()) {
                        throw IllegalStateException("do not know what I can read. bailing out ${batch.joinToString("\n")}")
                    }
                    L.d("new read now. batch size: %d, readNow size: %d", batch.size, readNow.size)
                    nl(readWithDependants(readNow, justRead, batch, tmpDirtyFlags))
                    batch.removeAll(justRead)
                }
                nl("// batch finished")
            } while (model.markBitsRead())
            // verify everything is read.
            val batch = ExprModel.filterShouldRead(model.pendingExpressions)
            if (batch.isNotEmpty()) {
                L.e("could not generate code for %s. This might be caused by circular dependencies."
                        + "Please report on b.android.com. %d %s %s", layoutBinder.layoutname,
                        batch.size, batch[0], batch[0].toCode().generate())
            }
            //
            layoutBinder.sortedTargets.filter { it.isUsed }
                    .flatMap { it.bindings }
                    .groupBy {
                        tmpDirtyFlags.mapOr(it.expr.dirtyFlagSet) { _, index ->
                            "(${tmpDirtyFlags.localValue(index)} & ${it.expr.dirtyFlagSet.localValue(index)}) != 0"
                        }.joinToString(" || ")
                    }.forEach {
                block("if (${it.key})") {
                    it.value.groupBy { Math.max(1, it.minApi) }.forEach {
                        val setterValues = kcode("") {
                            it.value.forEach { binding ->
                                nl(binding.toAssignmentCode()).app(";")
                            }
                        }
                        nl("// api target ${it.key}")
                        if (it.key > 1) {
                            block("if(getBuildSdkInt() >= ${it.key})") {
                                nl(setterValues)
                            }
                        } else {
                            nl(setterValues)
                        }
                    }
                }
            }


            layoutBinder.sortedTargets.filter { it.isUsed }
                    .flatMap { it.bindings }
                    .filter { it.requiresOldValue() }
                    .groupBy {
                        tmpDirtyFlags.mapOr(it.expr.dirtyFlagSet) { _, index ->
                            "(${tmpDirtyFlags.localValue(index)} & ${it.expr.dirtyFlagSet.localValue(index)}) != 0"
                        }.joinToString(" || ")
                    }.forEach {
                block("if (${it.key})") {
                    it.value.groupBy { it.expr }.map { it.value.first() }.forEach {
                        it.componentExpressions.forEach { expr ->
                            nl("this.${expr.oldValueName} = ${expr.toCode().generate()};")
                        }
                    }
                }
            }
            includedBinders.filter { it.isUsed }.forEach { binder ->
                nl("executeBindingsOn(${binder.fieldName});")
            }
            layoutBinder.sortedTargets.filter {
                it.isUsed && it.resolvedType != null && it.resolvedType.extendsViewStub
            }.forEach {
                block("if (${it.fieldName}.getBinding() != null)") {
                    nl("executeBindingsOn(${it.fieldName}.getBinding());")
                }
            }
        }
    }

    fun readWithDependants(expressionList: List<Expr>, justRead: MutableList<Expr>,
                           batch: MutableList<Expr>, tmpDirtyFlags: FlagSet,
                           inheritedFlags: FlagSet? = null): KCode = kcode("") {
        expressionList.groupBy { it.shouldReadFlagSet }.forEach {
            val flagSet = it.key
            val needsIfWrapper = inheritedFlags == null || !flagSet.bitsEqual(inheritedFlags)
            val expressions = it.value
            val ifClause = "if (${tmpDirtyFlags.mapOr(flagSet) { _, index ->
                "(${tmpDirtyFlags.localValue(index)} & ${flagSet.localValue(index)}) != 0"
            }.joinToString(" || ")
            })"
            val readCode = kcode("") {
                val dependants = ArrayList<Expr>()
                expressions.groupBy { condition(it) }.forEach {
                    val condition = it.key
                    val assignedValues = it.value.filter { it.needsLocalField && !it.isVariable() }
                    if (!assignedValues.isEmpty()) {
                        val assignment = kcode("") {
                            assignedValues.forEach { expr: Expr ->
                                tab("// read $expr")
                                tab("${expr.executePendingLocalName}").app(" = ", expr.toFullCode()).app(";")
                            }
                        }
                        if (condition != null) {
                            tab("if ($condition) {") {
                                app("", assignment)
                            }
                            tab("}")
                        } else {
                            app("", assignment)
                        }
                        it.value.filter { it.isObservable }.forEach { expr: Expr ->
                            tab(expr.getUpdateRegistrationCall(expr.id, expr.executePendingLocalName))
                        }
                    }

                    it.value.forEach { expr: Expr ->
                        justRead.add(expr)
                        L.d("%s / readWithDependants %s", className, expr);
                        L.d("flag set:%s . inherited flags: %s. need another if: %s", flagSet, inheritedFlags, needsIfWrapper);

                        // if I am the condition for an expression, set its flag
                        expr.dependants.filter {
                            !it.isConditional && it.dependant is TernaryExpr &&
                                    (it.dependant as TernaryExpr).pred == expr
                        }.map { it.dependant }.groupBy {
                            // group by when those ternaries will be evaluated (e.g. don't set conditional flags for no reason)
                            val ternaryBitSet = it.shouldReadFlagsWithConditionals
                            val isBehindTernary = ternaryBitSet.nextSetBit(model.invalidateAnyFlagIndex) == -1
                            if (!isBehindTernary) {
                                val ternaryFlags = it.shouldReadWithConditionalsFlagSet
                                "if(${tmpDirtyFlags.mapOr(ternaryFlags) { _, index ->
                                    "(${tmpDirtyFlags.localValue(index)} & ${ternaryFlags.localValue(index)}) != 0"
                                }.joinToString(" || ")})"
                            } else {
                                // TODO if it is behind a ternary, we should set it when its predicate is elevated
                                // Normally, this would mean that there is another code path to re-read our current expression.
                                // Unfortunately, this may not be true due to the coverage detection in `expr#markAsReadIfDone`, this may never happen.
                                // for v1.0, we'll go with always setting it and suffering an unnecessary calculation for this edge case.
                                // we can solve this by listening to elevation events from the model.
                                ""
                            }
                        }.forEach {
                            val hasAnotherIf = it.key != ""
                            val cond: (KCode.() -> Unit) = {
                                it.apply {
                                    val predicate = if (expr.resolvedType.isNullable) {
                                        "Boolean.TRUE.equals(${expr.executePendingLocalName})"
                                    } else {
                                        expr.executePendingLocalName
                                    }
                                    block("if($predicate)") {
                                        it.value.forEach {
                                            val set = it.getRequirementFlagSet(true)
                                            mDirtyFlags.mapOr(set) { _, index ->
                                                tab("${tmpDirtyFlags.localValue(index)} |= ${set.localValue(index)};")
                                            }
                                        }
                                    }
                                    block("else") {
                                        it.value.forEach {
                                            val set = it.getRequirementFlagSet(false)
                                            mDirtyFlags.mapOr(set) { _, index ->
                                                tab("${tmpDirtyFlags.localValue(index)} |= ${set.localValue(index)};")
                                            }
                                        }
                                    }
                                }
                            }
                            if (hasAnotherIf) {
                                block(it.key, cond)
                            } else {
                                cond()
                            }
                        }
                        val chosen = expr.dependants.filter {
                            val dependant = it.dependant
                            batch.contains(dependant) &&
                                    dependant.shouldReadFlagSet.andNot(flagSet).isEmpty &&
                                    dependant.shouldReadNow(justRead)
                        }
                        if (chosen.isNotEmpty()) {
                            dependants.addAll(chosen.map { it.dependant })
                        }
                    }
                }
                if (dependants.isNotEmpty()) {
                    val nextInheritedFlags = if (needsIfWrapper) flagSet else inheritedFlags
                    nl(readWithDependants(dependants, justRead, batch, tmpDirtyFlags, nextInheritedFlags))
                }
            }

            if (needsIfWrapper) {
                block(ifClause) {
                    nl(readCode)
                }
            } else {
                nl(readCode)
            }
        }
    }

    fun condition(expr: Expr): String? {
        if (expr.canBeEvaluatedToAVariable() && !expr.isVariable()) {
            // create an if case for all dependencies that might be null
            val nullables = expr.dependencies.filter {
                it.isMandatory && it.other.resolvedType.isNullable
            }.map { it.other }
            if (!expr.isEqualityCheck && nullables.isNotEmpty()) {
                return nullables.map { "${it.executePendingLocalName} != null" }.joinToString(" && ")
            } else {
                return null
            }
        } else {
            return null
        }
    }

    fun declareListenerImpls() = kcode("// Listener Stub Implementations") {
        model.exprMap.values.filter {
            it.isUsed && it is ListenerExpr
        }.groupBy { it }.forEach {
            val expr = it.key as ListenerExpr
            val listenerType = expr.resolvedType;
            val extendsImplements: String
            if (listenerType.isInterface) {
                extendsImplements = "implements"
            } else {
                extendsImplements = "extends"
            }
            nl("public static class ${expr.listenerClassName} $extendsImplements $listenerType{") {
                if (expr.target.isDynamic) {
                    tab("private ${expr.target.resolvedType.toJavaCode()} value;")
                    tab("public ${expr.listenerClassName} setValue(${expr.target.resolvedType.toJavaCode()} value) {") {
                        tab("this.value = value;")
                        tab("return value == null ? null : this;")
                    }
                    tab("}")
                }
                val listenerMethod = expr.method
                val parameterTypes = listenerMethod.parameterTypes
                val returnType = listenerMethod.getReturnType(parameterTypes.toList())
                tab("@Override")
                tab("public $returnType ${listenerMethod.name}(${
                parameterTypes.withIndex().map {
                    "${it.value.toJavaCode()} arg${it.index}"
                }.joinToString(", ")
                }) {") {
                    val obj = if (expr.target.isDynamic) {
                        "this.value"
                    } else {
                        expr.target.toCode().generate()
                    }
                    val returnSuffix = if(returnType.isKotlinUnit) {
                        "return null;"
                    } else {
                        ""
                    }
                    val returnPrefix = if (!returnType.isVoid && !returnType.isKotlinUnit) {
                        "return "
                    } else {
                        ""
                    }
                    val args = parameterTypes.withIndex().joinToString(", ") {
                        "arg${it.index}"
                    }
                    tab("$returnPrefix$obj.${expr.name}($args); $returnSuffix")
                }
                tab("}")
            }
            nl("}")
        }
    }

    fun declareFactories() = kcode("") {
        nl("@NonNull")
        block("public static $baseClassName inflate(@NonNull android.view.LayoutInflater inflater, @Nullable android.view.ViewGroup root, boolean attachToRoot)") {
            nl("return inflate(inflater, root, attachToRoot, ${libTypes.dataBindingUtil}.getDefaultComponent());")
        }
        nl("@NonNull")
        block("public static $baseClassName inflate(@NonNull android.view.LayoutInflater inflater, @Nullable android.view.ViewGroup root, boolean attachToRoot, @Nullable ${libTypes.dataBindingComponent} bindingComponent)") {
            nl("return ${libTypes.dataBindingUtil}.<$baseClassName>inflate(inflater, ${layoutBinder.modulePackage}.R.layout.${layoutBinder.layoutname}, root, attachToRoot, bindingComponent);")
        }
        if (!layoutBinder.isMerge) {
            nl("@NonNull")
            block("public static $baseClassName inflate(@NonNull android.view.LayoutInflater inflater)") {
                nl("return inflate(inflater, ${libTypes.dataBindingUtil}.getDefaultComponent());")
            }
            nl("@NonNull")
            block("public static $baseClassName inflate(@NonNull android.view.LayoutInflater inflater, @Nullable ${libTypes.dataBindingComponent} bindingComponent)") {
                nl("return bind(inflater.inflate(${layoutBinder.modulePackage}.R.layout.${layoutBinder.layoutname}, null, false), bindingComponent);")
            }
            nl("@NonNull")
            block("public static $baseClassName bind(@NonNull android.view.View view)") {
                nl("return bind(view, ${libTypes.dataBindingUtil}.getDefaultComponent());")
            }
            nl("@NonNull")
            block("public static $baseClassName bind(@NonNull android.view.View view, @Nullable ${libTypes.dataBindingComponent} bindingComponent)") {
                block("if (!\"${layoutBinder.tag}_0\".equals(view.getTag()))") {
                    nl("throw new RuntimeException(\"view tag isn't correct on view:\" + view.getTag());")
                }
                nl("return new $baseClassName(bindingComponent, view);")
            }
        }
    }

    /**
     * When called for a library compilation, we do not generate real implementations.
     * This code is only kept for backward compatibility reasons as we move to v2. If you change
     * anything here, make sure it is also changed in BaseLayoutBinderWriter.
     */
    @Deprecated("v2 uses BaseLayoutBinderWriter")
    public fun writeBaseClass(forLibrary: Boolean, variations: List<LayoutBinder>) : String =
            kcode("package ${layoutBinder.`package`};") {
                Scope.reset()
                nl("import ${libTypes.bindable};")
                nl("import ${libTypes.dataBindingUtil};")
                nl("import ${libTypes.viewDataBinding};")
                nl("import ${libTypes.nonNull};")
                nl("import ${libTypes.nullable};")
                annotateWithGenerated()
                nl("public abstract class $baseClassName extends ViewDataBinding {")
                layoutBinder.sortedTargets.filter{it.id != null}.forEach {
                    if (variations.count{lb -> lb.sortedTargets.any {bt -> bt.isUsed && bt.id == it.id && bt.includedLayout == null}} == variations.size) {
                        tab("@NonNull")
                    }
                    else {
                        tab("@Nullable")
                    }
                    tab("public final ${it.interfaceClass} ${it.fieldName};")
                }
                nl("")
                tab("// variables") {
                    variables.forEach {
                        nl("protected ${it.resolvedType.toJavaCode()} ${it.fieldName};")
                    }
                }

                tab("protected $baseClassName(@Nullable ${libTypes.dataBindingComponent} bindingComponent, @Nullable android.view.View root_, int localFieldCount") {
                    layoutBinder.sortedTargets.filter{it.id != null}.forEach {
                        tab(", ${it.interfaceClass} ${it.constructorParamName}")
                    }
                }
                tab(") {") {
                    tab("super(bindingComponent, root_, localFieldCount);")
                    layoutBinder.sortedTargets.filter{it.id != null}.forEach {
                        tab("this.${it.fieldName} = ${it.constructorParamName};")
                        if (it.isDataBindingLayout) {
                            tab("setContainedBinding(this.${it.fieldName});")
                        }
                    }
                }
                tab("}")
                tab("//getters and abstract setters") {
                    variables.forEach {
                        val typeCode = it.resolvedType.toJavaCode()
                        nl("public abstract void ${it.setterName}(${if (it.resolvedType.isPrimitive) "" else "@Nullable "}$typeCode ${it.readableName});")
                        nl("")
                        if (!it.resolvedType.isPrimitive) {
                            nl("@Nullable")
                        }
                        block("public $typeCode ${it.getterName}()") {
                            nl("return ${it.fieldName};")
                        }
                        nl("")
                    }
                }
                tab("@NonNull")
                tab("public static $baseClassName inflate(@NonNull android.view.LayoutInflater inflater, @Nullable android.view.ViewGroup root, boolean attachToRoot) {") {
                    tab("return inflate(inflater, root, attachToRoot, ${libTypes.dataBindingUtil}.getDefaultComponent());")
                }
                tab("}")
                tab("@NonNull")
                tab("public static $baseClassName inflate(@NonNull android.view.LayoutInflater inflater) {") {
                    tab("return inflate(inflater, ${libTypes.dataBindingUtil}.getDefaultComponent());")
                }
                tab("}")
                tab("@NonNull")
                tab("public static $baseClassName bind(@NonNull android.view.View view) {") {
                    if (forLibrary) {
                        tab("return null;")
                    } else {
                        tab("return bind(view, ${libTypes.dataBindingUtil}.getDefaultComponent());")
                    }
                }
                tab("}")
                tab("@NonNull")
                tab("public static $baseClassName inflate(@NonNull android.view.LayoutInflater inflater, @Nullable android.view.ViewGroup root, boolean attachToRoot, @Nullable ${libTypes.dataBindingComponent} bindingComponent) {") {
                    if (forLibrary) {
                        tab("return null;")
                    } else {
                        tab("return DataBindingUtil.<$baseClassName>inflate(inflater, ${layoutBinder.modulePackage}.R.layout.${layoutBinder.layoutname}, root, attachToRoot, bindingComponent);")
                    }
                }
                tab("}")
                tab("@NonNull")
                tab("public static $baseClassName inflate(@NonNull android.view.LayoutInflater inflater, @Nullable ${libTypes.dataBindingComponent} bindingComponent) {") {
                    if (forLibrary) {
                        tab("return null;")
                    } else {
                        tab("return DataBindingUtil.<$baseClassName>inflate(inflater, ${layoutBinder.modulePackage}.R.layout.${layoutBinder.layoutname}, null, false, bindingComponent);")
                    }
                }
                tab("}")
                tab("@NonNull")
                tab("public static $baseClassName bind(@NonNull android.view.View view, @Nullable ${libTypes.dataBindingComponent} bindingComponent) {") {
                    if (forLibrary) {
                        tab("return null;")
                    } else {
                        tab("return ($baseClassName)bind(bindingComponent, view, ${layoutBinder.modulePackage}.R.layout.${layoutBinder.layoutname});")
                    }
                }
                tab("}")
                nl("}")
            }.generate()
}

/**
 * Runs the given block in the error scope of this scope provider such that any exception thrown
 * will be scoped to that expression for error reporting.
 */
private inline fun android.databinding.tool.processing.scopes.ScopeProvider.inErrorScope(
    crossinline block : () -> Unit
) {
    try {
        android.databinding.tool.processing.Scope.enter(this)
        block()
    } finally {
        android.databinding.tool.processing.Scope.exit()
    }

}
