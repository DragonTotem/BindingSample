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

import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito

@RunWith(JUnit4::class)
class ClassFinderCacheTest {
    private val loggingFinder = LoggingFinder()
    @Test
    fun simple_fail() {
        val cache = ClassFinderCache(loggingFinder::doFind)
        val result = cache.find("foo", null)
        assertThat(loggingFinder.calls, `is`(
                listOf(
                        DoFindCall("foo", null)
                )
        ))
        assertThat(result, nullValue())
    }

    @Test
    fun simple_succeed() {
        val cache = ClassFinderCache(loggingFinder::doFind)
        val fake = loggingFinder.addClassFor("foo")
        val result = cache.find("foo", null)
        assertThat(loggingFinder.calls, `is`(
                listOf(
                        DoFindCall("foo", null)
                )
        ))
        assertThat(result, `is`(fake))
        cache.find("foo", null)
        // should return from cache
        assertThat(loggingFinder.calls, `is`(
                listOf(
                        DoFindCall("foo", null)
                )
        ))
    }

    @Test
    fun import_different() {
        val cache = ClassFinderCache(loggingFinder::doFind)
        val fake = loggingFinder.addClassFor("foo")
        val result = cache.find("foo", null)
        assertThat(loggingFinder.calls, `is`(
                listOf(
                        DoFindCall("foo", null)
                )
        ))
        assertThat(result, `is`(fake))
        val result2 = cache.find("foo", ImportBag.EMPTY)
        assertThat(loggingFinder.calls, `is`(
                listOf(
                        DoFindCall("foo", null),
                        DoFindCall("foo", ImportBag.EMPTY.toImmutable())
                )
        ))
        assertThat(result2, `is`(fake))
    }

    @Test
    fun import_mutated() {
        val cache = ClassFinderCache(loggingFinder::doFind)
        val fake = loggingFinder.addClassFor("foo")
        val imports = MutableImportBag().also {
            it.put("baz", "foo.baz")
        }
        val imports1 = imports.toImmutable()
        val result = cache.find("foo", imports)
        assertThat(loggingFinder.calls, `is`(
                listOf(
                        DoFindCall("foo", imports1)
                )
        ))
        assertThat(result, `is`(fake))

        imports.put("bazar", "foo.bazar")
        val imports2 = imports.toImmutable()
        val result2 = cache.find("foo", imports2)
        assertThat(loggingFinder.calls, `is`(
                listOf(
                        DoFindCall("foo", imports1),
                        DoFindCall("foo", imports2)
                )
        ))
        assertThat(result2, `is`(fake))

        val result3 = cache.find("foo", MutableImportBag().also {
            it.put("baz", "foo.baz")
        })
        // should come from cache
        assertThat(loggingFinder.calls, `is`(
                listOf(
                        DoFindCall("foo", imports1),
                        DoFindCall("foo", imports2)
                )
        ))
        assertThat(result3, `is`(fake))


    }

    class LoggingFinder {
        val calls = arrayListOf<DoFindCall>()
        private val classes = mutableMapOf<String, ModelClass>()
        fun doFind(className: String, imports: ImportBag?): ModelClass? {
            calls.add(DoFindCall(
                    className = className,
                    imports = imports?.toImmutable()
            ))
            return classes[className]
        }

        fun addClassFor(className: String): ModelClass {
            val mock = Mockito.mock(ModelClass::class.java)
            classes[className] = mock
            return mock
        }
    }

    data class DoFindCall(val className: String, val imports: ImmutableImportBag?)
}