/*
 * Copyright (C) 2021 The Android Open Source Project
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
@file:JvmName("SymbolTableUtil")
package android.databinding.tool.util

import android.databinding.tool.expr.ResourceExpr
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMultimap
import java.io.File

private const val STYLEABLE: String = "styleable"
val EMPTY_RESOURCES: Resources = Resources(null)

data class Resources(val symbolTables: ImmutableList<SymbolTable>?) {

    /**
     * Returns the prefix to be used in R class references in Java/Kotlin. For example: "android."
     * for "android.R.color.white", "com.my.lib." for "com.my.lib.R.string.text" and an empty string
     * for default R class reference e.g. "R.attr.my_attr".
     *
     * This method accepts data-binding specific "types", e.g. using "text" (for "string" resources)
     * or "intArray" ( for "array" resources).
     */
    fun getRPackagePrefix(packageName: String?, type: String, name: String): String {
        when {
            "android" == packageName -> {
                return "android."
            }
            symbolTables != null -> {
                for (table in symbolTables) {
                    if (table.contains(type, name)) {
                        // For local module references, return empty prefix as the R class will use
                        // the same local package.
                        if (table.rPackage.isEmpty()) return ""
                        // For any non-local references, use the full package plus a "." for the
                        // prefix.
                        return "${table.rPackage}."
                    }
                }
                throw RuntimeException("Resource not found: $type $name.")
            }
            else -> {
                // If we don't have a list of resources, it means the local R class contains all
                // resources, both local and from dependencies, so just use the local R class.
                return  ""
            }
        }
    }
}

data class SymbolTable constructor(
        val rPackage: String,
        val resources: ImmutableMultimap<String, String>) {

    fun contains(type: String, name: String) : Boolean {
        return resources[type].orEmpty().contains(name)
    }
}

fun parseRTxtFiles(
        localRFile: File?,
        dependenciesRFiles: List<File>?,
        mergedDependenciesRFile: File?
) : Resources {
    // If not using non-transitive R, return empty Resources (only local R class should be used)
    if (localRFile == null) return EMPTY_RESOURCES
    if (dependenciesRFiles != null && mergedDependenciesRFile != null) {
        error("Unexpected error: Both listed and merged dependencies R files present.")
    }

    val symbolTables = ImmutableList.builder<SymbolTable>()
    // local resources at the front of the list
    symbolTables.add(parseLocalRTxt(localRFile))
    when {
        dependenciesRFiles != null -> {
            // then add the rest of the dependencies, in order
            dependenciesRFiles.forEach { symbolTables.add(parsePackageAwareRTxt(it)) }
        }
        mergedDependenciesRFile != null -> {
            parseMergedPackageAwareRTxt(mergedDependenciesRFile, symbolTables)
        }
        else -> {
            error("Unexpected error: Missing dependency resources")
        }
    }

    return Resources(symbolTables.build())
}

fun parseLocalRTxt(file: File): SymbolTable {
    file.useLines {
        val iterator: Iterator<String> = it.iterator()
        // First line is a comment
        if (!iterator.hasNext())
            error("Incorrect package-aware R.txt format. " +
                    "Failed to parse file: ${file.absolutePath}")
        iterator.next()
        // Second line is local package we can ignore
        if (!iterator.hasNext())
            error("Resource list needs to contain the local package. " +
                    "Failed to parse file: ${file.absolutePath}")
        val localPackage = iterator.next()
        if (localPackage != "local")
            error("Illegal local package '$localPackage' in file ${file.absolutePath}")
        // Finally we can start parsing the resources
        val resources = try {
            readResources(iterator)
        } catch (e: java.lang.IllegalStateException) {
            throw IllegalStateException("Failed to parse file: ${file.absolutePath}", e)
        }
        // Local table should use a blank package to use default R class
        return SymbolTable("", resources)
    }
}

fun parsePackageAwareRTxt(file: File) : SymbolTable {
    // R.txt is verified before being written, no need to re-verify package or resource names.
    file.useLines {
        val iterator: Iterator<String> = it.iterator()
        // First line contains the package
        if (!iterator.hasNext())
            error("Resource list needs to contain the local package. " +
                    "Failed to parse file: ${file.absolutePath}")
        val pckg: String =  iterator.next()
        val resources = try {
            readResources(iterator)
        } catch (e: IllegalStateException) {
            throw IllegalStateException("Failed to parse file: ${file.absolutePath}", e)
        }
        return SymbolTable(pckg, resources)
    }
}

fun parseMergedPackageAwareRTxt(file: File, symbolTables: ImmutableList.Builder<SymbolTable>) {
    file.useLines {
        val iterator: Iterator<String> = it.iterator()
        // As a workaround for KAPT resolving files at configuration time, dependencies' R files are
        // merged into one file and then separated by an empty line, for example:
        // com.mid.lib
        // string foo
        //
        // com.leaf.lib1
        // string bar
        //
        // com.empty.lib
        //
        // com.final.lib
        // string hello
        //
        // Each dependency's chunk will start with a line with that R package, followed by the list
        // of resources defined in that dependency (non-transitive) - or empty if there were no
        // resources.

        // Loop through all the lines.
        while (iterator.hasNext()) {
            // First line contains the dependency's package
            if (!iterator.hasNext())
                error("Resource list needs to contain the local package. " +
                        "Failed to parse file: ${file.absolutePath}")
            val pckg: String = iterator.next()
            val resources = try {
                // Until the next empty line, this method will parse the resources from the current
                // dependency.
                readResources(iterator)
            } catch (e: IllegalStateException) {
                throw IllegalStateException("Failed to parse file: ${file.absolutePath}", e)
            }
            symbolTables.add(SymbolTable(pckg, resources))
        }
    }
}

fun readResources(lines: Iterator<String>) : ImmutableMultimap<String, String> {
    val resources = ImmutableMultimap.builder<String, String>()
    // Loop through the resources for a single dependency. The package has already been consumed,
    // so read the resources until an empty line or EOF.
    while (lines.hasNext()) {
        val line = lines.next()
        if (line.isEmpty())
            // No more resources within this dependency.
            return resources.build()
        val chunks = line.split(" ")
        // Format is <type> <name> for all resources apart from Styleables.
        if (chunks.size < 2 || (chunks[0] != STYLEABLE && chunks.size != 2))
            error("Illegal line in R.txt: '$line'")
        addResource(chunks[0], sanitizeName(chunks[1]), resources)
        if (chunks[0] == STYLEABLE) {
            // For styleables the format is <type> <name> <child1> <child2> ... <childN>
            // The resulting children need to be added as Styleable <parent>_<child>.
            // It's possible for a styleable to not have children at all.
            val parent = sanitizeName(chunks[1])
            for (i in 2 until chunks.size) {
                // Styleable children exist in the R class as R.styleable.parent_child.
                resources.put(STYLEABLE, "${parent}_${sanitizeName(chunks[i])}")
            }
        }
    }
    return resources.build()
}

fun addResource(
        type: String,
        name: String,
        resourcesBuilder: ImmutableMultimap.Builder<String, String>) {

    // Some expressions in data-binding correspond to different names in the R class. To be able
    // to verify them, we need to add the resource to all matching expressions, e.g:
    //  - "string" to "text"
    //  - "array" to "intArray" AND "stringArray" AND "typedArray"
    ResourceExpr.R_OBJECT_TO_RESOURCE_TYPE[type]?.forEach {
        resourcesBuilder.put(it, name)
    }

    resourcesBuilder.put(type, name)
}

private fun sanitizeName(name: String): String {
    return name.replace('.', '_').replace(':', '_')
}
