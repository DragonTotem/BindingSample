package com.zbt.databinding

import android.view.View
import androidx.databinding.ObservableArrayList
import androidx.databinding.ObservableArrayMap
import com.zbt.databinding.databinding.ActivityTest4Binding

/**
 *Author: zbt
 *Time: 2021/6/27 22:27
 *Description: This is Test4Activity
 */
class Test4Activity : BaseActivity<ActivityTest4Binding>() {

    override fun getLayoutId(): Int {
        return R.layout.activity_test4
    }

    override fun initData() {
        val list = ObservableArrayList<String>()
        list.add("12345")
        list.add("67890")

        val map = ObservableArrayMap<String, String>()
        map.put("map", "key shi map")
        map.put("list", "key shi list")

        dataBinding.strs = list
        dataBinding.map = map
        dataBinding.index = 0
        dataBinding.key = "map"
    }

    fun changeIndexAndKey(view: View) {
        dataBinding.index = 1
        dataBinding.key = "list"
    }

    fun changeValue(view: View) {
        dataBinding.strs?.set(1, "abc")
        dataBinding.map?.put("list", "key shi list value changed")
    }
}
