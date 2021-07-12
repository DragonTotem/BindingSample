/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.databinding.tool.reflection.annotation

import android.databinding.tool.reflection.ModelClass
import android.databinding.tool.reflection.MutableImportBag
import android.databinding.tool.runProcessorTest
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import com.google.testing.compile.CompilationRule
import com.google.testing.compile.JavaFileObjects
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AnnotationModelAssignabilityTest {
  @JvmField
  @Rule
  val compilation = CompilationRule()

  @JvmField
  @Rule
  val tmpFolder = TemporaryFolder()

  @Test
  fun basic() {
    val myGeneric = JavaFileObjects.forSourceString("foo.bar.GenericClass", """
      package foo.bar;
      public class GenericClass<T> {
          public T value;
          public java.util.List<T> listValue;
      }
    """.trimIndent())
    runProcessorTest(tmpFolder, jfos = *arrayOf(myGeneric)) { context, processingEnvironment ->
      val listOfString = context.requireClass("List<String>")
      val listOfBoolean = context.requireClass("List<Boolean>")
      val listOfObjects = context.requireClass("List<Object>")
      listOfString.assertNotAssignableFrom(listOfBoolean)
      listOfBoolean.assertNotAssignableFrom(listOfString)
      listOfObjects.assertNotAssignableFrom(listOfString)
      listOfString.assertNotAssignableFrom(listOfObjects)

      val incompleteList = context.requireClass("List")
      val incompleteList2 = context.requireClass("List")
      incompleteList.assertAssignableFrom(incompleteList2)
      incompleteList2.assertAssignableFrom(incompleteList)

      // java is fine with assigning List to List<Foo> but we are not
      listOfString.assertNotAssignableFrom(incompleteList)
      // java is also fine with assigning List<String> to List
      incompleteList.assertAssignableFrom(listOfString)

      val incompleteMap = context.requireClass("Map")
      val intStringMap = context.requireClass("Map<Integer, String>")
      incompleteMap.assertAssignableFrom(intStringMap)
      // again, java is fine with assigning Map to Map<Integer, String> but we are not
      intStringMap.assertNotAssignableFrom(incompleteMap)

      val listOfIncompleteMaps = context.requireClass("List<Map>")
      val listOfDefinedMaps = context.requireClass("List<Map<String, String>>")
      listOfIncompleteMaps.assertAssignableFrom(listOfDefinedMaps)
      // java is fine, we are not
      listOfDefinedMaps.assertNotAssignableFrom(listOfIncompleteMaps)

      val incompleteGeneric = context.requireClass("foo.bar.GenericClass")
      incompleteList.assertNotAssignableFrom(
          incompleteGeneric.getField("value").fieldType
      )
      incompleteList.assertAssignableFrom(
          incompleteGeneric.getField("listValue").fieldType
      )
      incompleteMap.assertNotAssignableFrom(
          incompleteGeneric.getField("listValue").fieldType
      )
    }
  }

  private fun ModelClass.getField(name:String) = allFields.first {
    it.name == name
  }

  private fun android.databinding.tool.Context.requireClass(name : String): ModelClass {
    return checkNotNull(
      modelAnalyzer?.findClass(name, IMPORTS)
    ) {
      "cannot find required class for test: $name"
    }
  }

  private fun ModelClass.assertAssignableFrom(other:ModelClass) {
    Truth.assertWithMessage(
      "$this should be assignable from $other"
    ).that(isAssignableFrom(other)).isTrue()
  }

  private fun ModelClass.assertNotAssignableFrom(other:ModelClass) {
    Truth.assertWithMessage(
      "$this should NOT be assignable from $other"
    ).that(isAssignableFrom(other)).isFalse()
  }

  companion object {
    val IMPORTS = MutableImportBag().apply {
      put("List", "java.util.List")
      put("Map", "java.util.Map")
    }
  }
}