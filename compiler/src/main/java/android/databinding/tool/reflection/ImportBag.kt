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

class MutableImportBag : ImportBag() {
    fun put(alias : String, qName : String) {
        imports.putIfAbsent(alias, qName)
    }
}

class ImmutableImportBag(imports : Map<String, String>? = null) : ImportBag() {
    init {
        if (imports != null) {
            this.imports.putAll(imports)
        }
    }
}

/**
 * A class that can keep a list of imports and also run an equals check against itself.
 *
 * We do import everything in java.lang which is a waste of memory and killer for equals
 * check. Instead, this class optimizes that part automatically.
 *
 * Equals on ImportBag is important because we resolve classes based on imports.
 */
sealed class ImportBag {
    // alias to Import mapping
    protected val imports = mutableMapOf<String, String>()

    fun find(alias: String) : String? {
        return imports[alias] ?: importJavaLang(alias)
    }

    fun contains(alias: String) = find(alias) != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImportBag) return false

        if (imports != other.imports) return false

        return true
    }

    override fun hashCode(): Int {
        return imports.hashCode()
    }

    fun toImmutable() : ImmutableImportBag {
        return if (this is ImmutableImportBag) {
            this
        } else {
            ImmutableImportBag(imports)
        }
    }

    companion object {
        @JvmField
        val EMPTY : ImportBag = ImmutableImportBag()
        @JvmField
        val JAVA_LANG_IMPORTS = setOf(
                "Deprecated",
                "Override",
                "SafeVarargs",
                "SuppressWarnings",
                "Appendable",
                "AutoCloseable",
                "CharSequence",
                "Cloneable",
                "Comparable",
                "Iterable",
                "Readable",
                "Runnable",
                "Thread.UncaughtExceptionHandler",
                "Boolean",
                "Byte",
                "Character",
                "Character.Subset",
                "Character.UnicodeBlock",
                "Class",
                "ClassLoader",
                "Compiler",
                "Double",
                "Enum",
                "Float",
                "InheritableThreadLocal",
                "Integer",
                "Long",
                "Math",
                "Number",
                "Object",
                "Package",
                "Process",
                "ProcessBuilder",
                "Runtime",
                "RuntimePermission",
                "SecurityManager",
                "Short",
                "StackTraceElement",
                "StrictMath",
                "String",
                "StringBuffer",
                "StringBuilder",
                "System",
                "Thread",
                "ThreadGroup",
                "ThreadLocal",
                "Throwable",
                "Void",
                "Thread.State",
                "ArithmeticException",
                "ArrayIndexOutOfBoundsException",
                "ArrayStoreException",
                "ClassCastException",
                "ClassNotFoundException",
                "CloneNotSupportedException",
                "EnumConstantNotPresentException",
                "Exception",
                "IllegalAccessException",
                "IllegalArgumentException",
                "IllegalMonitorStateException",
                "IllegalStateException",
                "IllegalThreadStateException",
                "IndexOutOfBoundsException",
                "InstantiationException",
                "InterruptedException",
                "NegativeArraySizeException",
                "NoSuchFieldException",
                "NoSuchMethodException",
                "NullPointerException",
                "NumberFormatException",
                "ReflectiveOperationException",
                "RuntimeException",
                "SecurityException",
                "StringIndexOutOfBoundsException",
                "TypeNotPresentException",
                "UnsupportedOperationException",
                "AbstractMethodError",
                "AssertionError",
                "ClassCircularityError",
                "ClassFormatError",
                "Error",
                "ExceptionInInitializerError",
                "IllegalAccessError",
                "IncompatibleClassChangeError",
                "InstantiationError",
                "InternalError",
                "LinkageError",
                "NoClassDefFoundError",
                "NoSuchFieldError",
                "NoSuchMethodError",
                "OutOfMemoryError",
                "StackOverflowError",
                "ThreadDeath",
                "UnknownError",
                "UnsatisfiedLinkError",
                "UnsupportedClassVersionError",
                "VerifyError",
                "VirtualMachineError"
        )
        private fun importJavaLang(alias : String) : String? {
            return if (JAVA_LANG_IMPORTS.contains(alias)) {
                "java.lang.$alias"
            } else {
                null
            }
        }
    }
}