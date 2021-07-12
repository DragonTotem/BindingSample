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

package android.databinding.tool.util

import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.IllegalStateException
import java.nio.file.Files

class SymbolTableUtilTest {

    @Test
    fun testParsingEmptyLocalFile() {
        val testFile = Files.createTempFile("local", "R.txt")
        Files.write(testFile, """""".toByteArray())

        var found = false
        try {
            parseLocalRTxt(testFile.toFile())
        } catch (exception: IllegalStateException) {
            found = true
            assertTrue(exception.message!!.contains("Incorrect package-aware R.txt format."))
        }
        assertTrue(found)
    }

    @Test
    fun testParsingCorrectLocalFile() {
        val testFile = Files.createTempFile("local", "R.txt")
        Files.write(
                testFile,
                """
                // This is a comment expected in package-aware R.txt
                local
                string first
                int second
                styleable parent child1 child2
                """.trimIndent().toByteArray())

        val result = parseLocalRTxt(testFile.toFile())

        assertEquals(result.rPackage, "")
        assertEquals(result.resources.keySet().size, 4) // 3 + "text"
        assertEquals(result.resources.values().size, 6) // "first" counted twice

        assertTrue(result.contains("string", "first"))
        assertTrue(result.contains("int", "second"))
        assertTrue(result.contains("styleable", "parent"))
        assertTrue(result.contains("styleable", "parent_child1"))
        assertTrue(result.contains("styleable", "parent_child2"))
        assertTrue(result.contains("text", "first"))
    }

    @Test
    fun testParsingCorrectDependencyRTxt() {
        val testFile = Files.createTempFile("dependency", "R.txt")
        Files.write(
                testFile,
                """
                com.test.lib
                string first
                """.trimIndent().toByteArray())

        val result = parsePackageAwareRTxt(testFile.toFile())

        assertEquals(result.rPackage, "com.test.lib")
        assertEquals(result.resources.keySet().size, 2) // string -> string, text
        assertEquals(result.resources.values().toSet().size, 1) // "first" twice
        assertTrue(result.contains("string", "first"))
        assertTrue(result.contains("text", "first"))
    }

    @Test
    fun testParsingMultipleFiles() {
        val localFile = Files.createTempFile("local", "R.txt")
        Files.write(
                localFile,
                """
                // This is a comment expected in package-aware R.txt
                local
                string first
                """.trimIndent().toByteArray())

        val dependencyFile = Files.createTempFile("dependency", "R.txt")
        Files.write(
                dependencyFile,
                """
                com.test.lib
                string second
                """.trimIndent().toByteArray())

        val result =
                parseRTxtFiles(localFile.toFile(), ImmutableList.of(dependencyFile.toFile()), null)

        assertEquals(result.symbolTables!!.size, 2)
        assertEquals(result.symbolTables!![0].rPackage, "")
        assertEquals(result.symbolTables!![1].rPackage, "com.test.lib")

        assertEquals(result.getRPackagePrefix(null, "string", "first"), "")
        assertEquals(result.getRPackagePrefix(null, "string", "second"), "com.test.lib.")
        assertEquals(result.getRPackagePrefix("android", "string", "not_found"), "android.")
    }

    @Test
    fun testParsingMergedDependencies() {
        val mergedR = Files.createTempFile("merged", "R.txt")
        Files.write(
                mergedR,
                """
                    com.test.mid.lib
                    string foo
                    string bar

                    com.test.leaf.lib
                    string foo_bar

                    com.test.empty.lib

                    com.test.other.lib
                    attr hi


                """.trimIndent().toByteArray())

        val result = ImmutableList.builder<SymbolTable>()
        parseMergedPackageAwareRTxt(mergedR.toFile(), result)

        val symbolTables = result.build()
        assertEquals(symbolTables.size, 4)
        assertEquals(symbolTables[0].rPackage, "com.test.mid.lib")
        assertEquals(symbolTables[0].resources.size(), 4) // string -> string, text
        assertTrue(symbolTables[0].contains("string", "bar"))
        assertTrue(symbolTables[0].contains("text", "bar"))

        assertEquals(symbolTables[2].rPackage, "com.test.empty.lib")
        assertEquals(symbolTables[2].resources.size(), 0)

        assertEquals(symbolTables[3].rPackage, "com.test.other.lib")
        assertEquals(symbolTables[3].resources.size(), 1)
        assertTrue(symbolTables[3].contains("attr", "hi"))

    }

    @Test
    fun testParsingNoFiles() {
        val result = parseRTxtFiles(null, null, null)
        assertEquals(result, EMPTY_RESOURCES)

        assertEquals(result.getRPackagePrefix(null, "string", "hello"), "")
    }

    @Test
    fun testIncorrectResources() {
        val testFile = Files.createTempFile("local", "R.txt")
        Files.write(testFile, "default string first".toByteArray())

        var found = false
        try {
            readResources(testFile.toFile().readLines().iterator())
        } catch (exception: IllegalStateException) {
            found = true
            assertEquals(exception.message, "Illegal line in R.txt: 'default string first'")
        }
        assertTrue(found)
    }

    @Test
    fun testDataBindingKeyWords() {
        val testFile = Files.createTempFile("dependency", "R.txt")
        Files.write(
                testFile,
                """
                com.test.lib
                string first
                array my_list
                dimen dimension
                color foo
                """.trimIndent().toByteArray())

        val result = parsePackageAwareRTxt(testFile.toFile())

        assertEquals(result.resources.size(), 11)
        assertTrue(result.contains("string", "first"))
        assertTrue(result.contains("text", "first"))
        assertTrue(result.contains("array", "my_list"))
        assertTrue(result.contains("intArray", "my_list"))
        assertTrue(result.contains("stringArray", "my_list"))
        assertTrue(result.contains("typedArray", "my_list"))
        assertTrue(result.contains("dimen", "dimension"))
        assertTrue(result.contains("dimenOffset", "dimension"))
        assertTrue(result.contains("dimenSize", "dimension"))
        assertTrue(result.contains("color", "foo"))
        assertTrue(result.contains("colorStateList", "foo"))

    }

    @Test
    fun testEmptyResources() {
        val testFile = Files.createTempFile("local", "R.txt")
        Files.write(testFile, "".toByteArray())

        val result = readResources(testFile.toFile().readLines().iterator())
        assertTrue(result.isEmpty)
    }
}
