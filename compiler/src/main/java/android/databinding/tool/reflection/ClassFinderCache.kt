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

package android.databinding.tool.reflection

import android.databinding.tool.util.L

/**
 * A cache object that can index classes based on when it is found and its imports.
 */
class ClassFinderCache(
        private val doFind : ((className : String, imports : ImportBag?) -> ModelClass?)
) {
    private val cache = mutableMapOf<CacheKey, ModelClass>()
    private val importCache = mutableMapOf<ImportBag, ImmutableImportBag>()
    private var hit = 0
    private var miss = 0
    private var missForNull = 0
    fun find(className : String, imports: ImportBag?) : ModelClass? {
        val immutableImports = if(imports == null) {
            null
        } else {
            importCache.getOrPut(imports) {
                imports.toImmutable()
            }
        }
        val key = CacheKey(className = className, imports = immutableImports)
        val existing = cache[key]
        if (existing == null) {
            miss ++
            val found = doFind(className, imports)
            if (found == null) {
                missForNull ++
            } else {
                cache[key] = found
                return found
            }
            return found
        } else {
            hit++
            return existing
        }
    }

    fun logStats() {
        val ratio = (miss * 1f) / (miss + hit)
        val nonNullMiss = miss - missForNull
        val nonNullRatio = (nonNullMiss * 1f) / (nonNullMiss + hit)
        L.w("class finder cache: miss: $miss, hit: $hit, ratio : $ratio, ratio w/o nulls: $nonNullRatio")
    }

    private data class CacheKey(
            val className: String,
            val imports: ImmutableImportBag?
    )
}