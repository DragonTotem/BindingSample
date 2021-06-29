package com.zbt.databinding

import androidx.databinding.ObservableList
import androidx.databinding.ObservableMap
import java.util.*
import kotlin.collections.HashMap

/**
 * Description:
 * @Author: zhuqt13
 * Date: 2021/6/28 14:22
 */
class ObservableHashMap<K, V> : HashMap<K, V>(), ObservableMap<K, V> {
    override fun addOnMapChangedCallback(callback: ObservableMap.OnMapChangedCallback<out ObservableMap<K, V>, K, V>?) {
        TODO("Not yet implemented")
    }

    override fun removeOnMapChangedCallback(callback: ObservableMap.OnMapChangedCallback<out ObservableMap<K, V>, K, V>?) {
        TODO("Not yet implemented")
    }
}

class ObservableLinkedList<T> : LinkedList<T>(), ObservableList<T> {
    override fun addOnListChangedCallback(callback: ObservableList.OnListChangedCallback<out ObservableList<T>>?) {
        TODO("Not yet implemented")
    }

    override fun removeOnListChangedCallback(callback: ObservableList.OnListChangedCallback<out ObservableList<T>>?) {
        TODO("Not yet implemented")
    }
}