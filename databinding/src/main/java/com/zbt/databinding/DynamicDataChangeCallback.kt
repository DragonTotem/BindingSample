package com.zbt.databinding

import androidx.databinding.ObservableList

/**
 *Author: zbt
 *Time: 2021/6/28 22:32
 *Description: This is DynamicDataChangeCallback
 */
class DynamicDataChangeCallback<T>(private val adapter: EmployeeAdapter) :
    ObservableList.OnListChangedCallback<ObservableList<T>>() {
    override fun onChanged(sender: ObservableList<T>?) {
        adapter.notifyDataSetChanged()
    }

    override fun onItemRangeChanged(
        sender: ObservableList<T>?,
        positionStart: Int,
        itemCount: Int
    ) {
        adapter.notifyItemRangeChanged(positionStart, itemCount)
    }

    override fun onItemRangeInserted(
        sender: ObservableList<T>?,
        positionStart: Int,
        itemCount: Int
    ) {
        adapter.notifyItemRangeInserted(positionStart, itemCount)
    }

    override fun onItemRangeMoved(
        sender: ObservableList<T>?,
        fromPosition: Int,
        toPosition: Int,
        itemCount: Int
    ) {
        adapter.notifyItemMoved(fromPosition, itemCount)
        adapter.notifyItemRangeInserted(toPosition, itemCount)
    }

    override fun onItemRangeRemoved(
        sender: ObservableList<T>?,
        positionStart: Int,
        itemCount: Int
    ) {
        adapter.notifyItemRangeRemoved(positionStart, itemCount)
    }
}