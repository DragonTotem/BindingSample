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
package android.databinding.tool.util

import android.databinding.annotationprocessor.ProcessExpressions
import android.databinding.tool.CompilerArguments
import android.databinding.tool.Context
import com.google.gson.GsonBuilder
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass

class GenerationalClassUtil constructor(
        private val inputDir: File,
        private val outputDir : File?
) {

    private constructor(args : CompilerArguments) : this(
            inputDir = args.dependencyArtifactsDir,
            outputDir = args.aarOutDir
    )

    // used when serializing the intermediate. This allows us to ensure that future updates are
    // convenient JSON changes while still using the serialized API.
    @Suppress("PrivatePropertyName")
    private val GSON = GsonBuilder()
            .setLenient()
            .disableHtmlEscaping()
            .serializeNulls()
            .enableComplexMapKeySerialization()
            .create()
    companion object {
        @JvmStatic
        fun create(args: CompilerArguments) = GenerationalClassUtil(args)

        @JvmStatic
        fun get(): GenerationalClassUtil {
            return Context.generationalClassUtil!!
        }
    }

    /**
     * used for java code since it cannot call reified kotlin function.
     */
    fun <T : Any> load(ext : ExtensionFilter, klass : Class<T>) : List<T> {
        return inputDir.walkTopDown().filter {
            it.isFile && it.name.endsWith(ext.ext)
        }.mapNotNull {
            if (ext.isJson) {
                it.bufferedReader(Charsets.UTF_8).use { reader -> GSON.fromJson(reader, klass) }
            } else {
                deserializeObject(it)
            }
        }.toList()
    }

    @Suppress("unused")
    inline fun <reified T : Any> load(ext : ExtensionFilter) : List<T> {
        return load(ext, T::class.java)
    }

    fun write(pkg:String, ext : ExtensionFilter, item: Any) {
        L.d("writing output file for %s, %s into %s", pkg, ext, outputDir)
        try {
            Preconditions.checkNotNull(outputDir,
                    "incremental out directory should be" + " set to aar output directory.")
            outputDir!!.mkdirs()
            val outFile = File(outputDir, "$pkg${ext.ext}")
            if (ext.isJson) {
                outFile.bufferedWriter(Charsets.UTF_8).use {
                    GSON.toJson(item, it)
                }
            } else {
                outFile.outputStream().use {
                    ObjectOutputStream(it).use {
                        it.writeObject(item)
                    }
                }
            }
            L.d("done writing output file %s into %s", pkg, outFile.canonicalPath)
        } catch (t : Throwable) {
            L.e(t, "cannot write file $pkg $ext")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun<T> deserializeObject(file: File) : T? {
        try {
            file.inputStream().use {
                val `in` = IgnoreSerialIdObjectInputStream(it)
                return `in`.readObject() as T
            }

        } catch (t: Throwable) {
            L.e(t, "Could not read Binding properties intermediate file. %s",
                    file.absolutePath)

        }
        var inputStream: InputStream? = null
        try {
            inputStream = FileUtils.openInputStream(file)
            val `in` = IgnoreSerialIdObjectInputStream(inputStream)
            return `in`.readObject() as T

        } catch (e: IOException) {
            L.e(e, "Could not merge in Bindables from %s", file.absolutePath)
        } catch (e: ClassNotFoundException) {
            L.e(e, "Could not read Binding properties intermediate file. %s",
                    file.absolutePath)
        } finally {
            IOUtils.closeQuietly(inputStream)
        }
        return null
    }

    enum class ExtensionFilter(val ext : String, val isJson : Boolean) {
        SETTER_STORE_JSON("-setter_store.json", true),
        BR("-br.bin", false),
        LAYOUT("-layoutinfo.bin", false),
        SETTER_STORE("-setter_store.bin", false);
    }

    private class IgnoreSerialIdObjectInputStream @Throws(IOException::class)
    constructor(`in`: InputStream) : ObjectInputStream(`in`) {

        @Throws(IOException::class, ClassNotFoundException::class)
        override fun readClassDescriptor(): ObjectStreamClass {
            val original = super.readClassDescriptor()
            // hack for https://issuetracker.google.com/issues/71057619
            return if (ProcessExpressions.IntermediateV1::class.java.name == original.name) {
                ObjectStreamClass.lookup(ProcessExpressions.IntermediateV1::class.java)
            } else original
        }
    }
}