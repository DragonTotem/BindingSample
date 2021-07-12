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

package android.databinding.annotationprocessor

import android.databinding.annotationprocessor.ProcessBindable.Intermediate
import android.databinding.tool.CompilerArguments
import android.databinding.tool.Context
import android.databinding.tool.DataBindingBuilder
import android.databinding.tool.FeaturePackageInfo
import android.databinding.tool.util.GenerationalClassUtil
import android.databinding.tool.util.L
import java.io.File
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.util.ElementFilter

/**
 * This class is a helper to hold all BR ids that we will generates.
 * <p>
 * It is generated from the set of bindable Intermediate files from dependencies and the
 * properties in this module.
 * <p>
 * This class decides the final values that will be assigned to the BR fields. When used with
 * Features (instant apps), it may read existing classes to ensure that the same ID is used for
 * the same fields between dependent features.
 */
class BindableBag(
        private val compilerArgs: CompilerArguments,
        // BR fields in curretn module
        moduleProperties: Set<String>,
        private val env: ProcessingEnvironment) {
    // The BR fields we will generate.
    val toBeGenerated: List<ModuleBR>
    // The list of package names that are generated. We usually generate for all dependencies
    // as well as the current module but it may change in Features depending on where the dependency
    // is coming from.
    var writtenPackages: List<String>
    // A lookup file to map Field names into integer values.
    val variableIdLookup: BRMapping

    // Information about the Feature module. Currently we only use the package offset id assigned
    // by gradle. That id allows us to avoid id conflicts between different features.
    private var featureInfo = if (compilerArgs.isFeature) {
        loadFeatureInfo(compilerArgs.featureInfoDir)
    } else {
        null
    }

    init {
        val modulePackageProps = createPackageProps(
                pkg = compilerArgs.modulePackage,
                properties = moduleProperties,
                captureValues = false)
        val brFiles = loadPreviousBRFiles(
                generationalClassUtil = Context.generationalClassUtil!!,
                captureValues = true) + modulePackageProps

        val brPackagesToGenerate = if (compilerArgs.isFeature) {
            // only generate BR id for current module + dependencies that are not inherited from
            // other features.
            val featureBRFiles = loadPreviousBRFilesForFeature(captureValues = false)
            featureBRFiles.map { it.pkg }.toSet() + compilerArgs.modulePackage
        } else {
            // generate BR for all dependencies
            brFiles.map { it.pkg }.toSet()
        }
        // shift feature id by 16 bits to have unique ids in each feature
        val idBag = IdBag((featureInfo?.packageId ?: 0).shl(16))
        // first record existing ids
        brFiles.forEach {
            it.properties.values.forEach {
                if (it.value != null) {
                    idBag.associate(it.name, it.value)
                }
            }
        }
        // now assign ids to the remaining ones.
        // we sort them to keep ids as stable as possible between runs
        val brsWithoutValues = brFiles.flatMap { packageProps ->
            packageProps.properties.values.filter {
                it.value == null
            }.map {
                it.name
            }
        }
        idBag.assignIds(brsWithoutValues)

        writtenPackages = brPackagesToGenerate.toList()
        val brMapping = idBag.buildMapping()
        // now assign id to all stuff we'll generate
        toBeGenerated = brPackagesToGenerate.map { brPkg ->
            ModuleBR(brPkg, brMapping)
            // WHEN we have BR per module, the code below should be executed instead
//            val packageProps = brFiles.firstOrNull {
//                it.pkg == brPkg
//            }
//            if (packageProps == null) {
//                L.e("cannot find the package prop but need to generate $brPkg")
//
//            } else {
//                // when we have BR per module, this should be the codegen
//                val pairs = packageProps.properties.values.map {
//                    Pair(it.name, it.value ?: idBag.findId(it.name))
//                } + Pair("_all", 0)
//                ModuleBR(brPkg, pairs)
//            }
        }
        variableIdLookup = brMapping
    }

    /**
     * Convert Bindable list in this module into PackageProps class
     */
    private fun createPackageProps(pkg: String, properties: Set<String>,
                                   captureValues: Boolean): PackageProps {
        val processed = if (captureValues) {
            // load class and extract value
            val typeElement = env.elementUtils.getTypeElement(pkg + ".BR")
            if (typeElement == null) {
                properties.map { Property(it, null) }
            } else {
                val fields = ElementFilter.fieldsIn(typeElement.enclosedElements)
                properties.map { prop ->
                    val value = fields.firstOrNull {
                        it.simpleName.toString() == prop
                    }?.constantValue as? Int // might happen with blaze
                    Property(prop, value)
                }
            }
        } else {
            properties.map { Property(it, null) }
        }
        return PackageProps(pkg, processed.associateBy { it.name })
    }

    /**
     * Load BR files which only exist as dependencies on the current feature so we need to
     * generate their BR classes.
     */
    private fun loadPreviousBRFilesForFeature(captureValues: Boolean): List<PackageProps> {
        val inputFolder = compilerArgs.featureInfoDir ?: return emptyList()
        val util = GenerationalClassUtil(inputFolder, null)
        return loadPreviousBRFiles(util, captureValues)
    }

    /**
     * Read BR classes from dependencies and convert them to PackageProps classes.
     * If captureValues is true, this method will find the actual TypeElement and read the field
     * values. This will allow us to keep those values the same in the current module.
     */
    private fun loadPreviousBRFiles(
            generationalClassUtil: GenerationalClassUtil,
            captureValues: Boolean)
            : List<PackageProps> {
        val brFiles = generationalClassUtil
                .load(GenerationalClassUtil.ExtensionFilter.BR, Intermediate::class.java)
        return brFiles
                .filter { compilerArgs.modulePackage != it.`package` }
                .map {
                    createPackageProps(it.`package`, it.getProperties(), captureValues)
                }
    }

    private fun Intermediate.getProperties(): MutableSet<String> {
        return ProcessBindable.getProperties(this)
    }

    companion object {
        /**
         * loads the feature info from build folder to get its id generator offset.
         */
        fun loadFeatureInfo(featureInfoDir: File?): FeaturePackageInfo {
            var file: File? = null
            if (featureInfoDir != null) {
                file = File(featureInfoDir, DataBindingBuilder.FEATURE_BR_OFFSET_FILE_NAME)
            }
            if (file == null || !file.exists()) {
                L.e("DataBinding compiler args must include the feature info json when" +
                        " compiling a feature")
                return FeaturePackageInfo(0)
            }
            return FeaturePackageInfo.fromFile(file)
        }
    }

    /**
     * holds the information about a package and its properties
     */
    data class PackageProps(val pkg: String, val properties: Map<String, Property>)

    /**
     * holds the information about a Bindable property, possibly with a value if its BR id is
     * already generated.
     */
    data class Property(val name: String, val value: Int?)

    /**
     * Represents the metadata for a specific module BR class with its ids already assigned.
     */
    data class ModuleBR(val pkg: String, val br: BRMapping)

    /**
     * Keeps the final mapping for a BR class.
     * Props are ordered by key so that re-runs of the same code generates the same output.
     */
    data class BRMapping(val props: List<Pair<String, Int>>) {
        val size
            get() = props.size
    }

    /**
     * helper class to generate ids and ensure that same key receives the same id.
     */
    class IdBag(private val newIdOffset: Int) {
        // cannot use bitset because it will grow big due to the offset
        private val usedIds = hashSetOf<Int>()
        private val idMapping = mutableMapOf<String, Int>()

        init {
            associate("_all", 0)
        }

        fun associate(key: String, value: Int) {
            usedIds.add(value)
            idMapping[key] = value
        }

        fun assignIds(keys : List<String>) {
            keys.sorted().forEach {
                findId(it)
            }
        }

        private fun findId(key: String): Int {
            return idMapping.getOrPut(key) {
                var i = newIdOffset
                while (usedIds.contains(i)) {
                    i++
                }
                usedIds.add(i)
                i
            }
        }

        fun buildMapping() = BRMapping(
                idMapping.map {
                    Pair(it.key, it.value)
                }.sortedBy {
                    it.first
                }
        )
    }
}
