package com.zbt.databinding

import android.util.SparseArray
import androidx.core.util.set
import com.zbt.databinding.databinding.ActivityTest6Binding

/**
 *Author: zbt
 *Time: 2021/6/27 22:27
 *Description: This is Test6Activity
 */
class Test6Activity : BaseActivity<ActivityTest6Binding>() {

    override fun getLayoutId(): Int {
        return R.layout.activity_test6
    }

    override fun initData() {

        dataBinding.array = arrayOf("array")

        val sparseArray = SparseArray<String>()
        sparseArray[0] = "sparseArray"
        dataBinding.sparse = sparseArray

        dataBinding.list = listOf("list")

        val map = mutableMapOf<String, String>()
        map.put("databinding", "map")
        dataBinding.map = map

        val set = mutableSetOf<String>()
        set.add("set")
        dataBinding.set = set

        dataBinding.key = "databinding"
        dataBinding.index = 0
    }
}
