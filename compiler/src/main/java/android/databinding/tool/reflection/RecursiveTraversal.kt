/*
 * Copyright (C) 2019 The Android Open Source Project
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
import java.util.*

/**
 * This class helps with recursive resolution code (like resolving a type, printing java etc) and avoids going into
 * an infinite loop if the type is recursive or contains itself in an inner loop.
 *
 * It keeps track of local objects and detect if a recursion happens, delegating to the callback to return whatever
 * is desired.
 *
 * Directly use this one if you know the scope or if you don't (e.g. function being called multiple times), use
 * [RecursiveResolutionStack].
 */
class RecursionTracker<T>(
        /**
         * Called when error is discovered so that client can provide a better warning or just debug
         */
        private val errorReporter: (T) -> Unit
) {
    // current items in stack
    private val items = ArrayDeque<T>()

    /**
     * Adds the item to the list of tracked items if it is not there already.
     * Returns true if added, false otherwise.
     */
    fun pushIfNew(item: T): Boolean {
        if (items.contains(item)) {
            errorReporter(item)
            return false
        }
        items.push(item)
        return true
    }

    /**
     * Removes the last element from stack and checks it is the expected element to detect buggy code which may not
     * control stack properly.
     */
    @Throws(IllegalStateException::class)
    fun popAndCheck(item: T) {
        val removed = items.pop()
        check(item == removed) {
            "inconsistent reference stack. received $removed expected $item"
        }
    }
}

class RecursiveResolutionStack {
    /**
     * List of items. Using a thread local here to be able to maintain it across multiple calls
     */
    private val items: ThreadLocal<RecursionTracker<Any>> = ThreadLocal.withInitial {
        RecursionTracker<Any> {
            L.d("found recursive type, canceling resolution: %s", it)
        }
    }

    /**
     * Visits the given [referenceObject].
     * If it is not in the stack, calls [process], if it is in the stack, calls [onRecursionDetected].
     */
    fun <T : Any, R> visit(referenceObject: T, process: (T) -> R, onRecursionDetected: (T) -> R): R {
        if (!items.get().pushIfNew(referenceObject)) {
            return onRecursionDetected(referenceObject)
        }
        try {
            return process(referenceObject)
        } finally {
            items.get().popAndCheck(referenceObject)
        }
    }
}