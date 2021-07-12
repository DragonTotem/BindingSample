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

import org.junit.Assert.*
import org.junit.Test

class MutableImportBagTest {
    @Test
    fun mutableWithImmutable() {
        assertEquals(MutableImportBag(), ImportBag.EMPTY)
    }

    @Test
    fun mutableWithImmutable_modified() {
        assertNotEquals(MutableImportBag().also {
            it.put("foo", "bar")
        }, ImportBag.EMPTY)
    }

    @Test
    fun normal() {
        val imports = MutableImportBag()
        assertNull(imports.find("Foo"))
        imports.put("Foo", "bar.Foo")
        assertEquals("bar.Foo", imports.find("Foo"))
        assertEquals("java.lang.String", imports.find("String"))
    }

    @Test
    fun equals() {
        val bag1 = MutableImportBag().apply {
            put("foo", "Bar")
            put("bar", "Foo")
        }
        val bag2 = MutableImportBag().apply {
            put("foo", "Bar")
            put("bar", "Foo")
        }
        assertEquals(bag1, bag2)
    }

    @Test
    fun equals_mismatch() {
        val bag1 = MutableImportBag().apply {
            put("foo", "Bar2")
            put("bar", "Foo")
        }
        val bag2 = MutableImportBag().apply {
            put("bar", "Foo")
            put("foo", "Bar")
        }
        assertNotEquals(bag1, bag2)
    }

    @Test
    fun equals_missing() {
        val bag1 = MutableImportBag().apply {
            put("bar", "Foo")
        }
        val bag2 = MutableImportBag().apply {
            put("foo", "Bar")
            put("bar", "Foo")
        }
        assertNotEquals(bag1, bag2)
    }
}