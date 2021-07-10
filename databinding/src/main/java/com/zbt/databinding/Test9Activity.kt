package com.zbt.databinding

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.databinding.BindingMethod
import androidx.databinding.BindingMethods
import androidx.databinding.adapters.Converters
import androidx.databinding.adapters.ViewBindingAdapter
import com.zbt.databinding.databinding.ActivityTest9Binding


/**
 * Description:
 * @Author: zhuqt13
 * Date: 2021/7/1 14:46
 */
class Test9Activity : BaseActivity<ActivityTest9Binding>() {
    override fun getLayoutId() = R.layout.activity_test9

    override fun initData() {
        dataBinding.activity = this
        dataBinding.tvConverters.background =
            Converters.convertColorToDrawable(Color.parseColor("#0000FF"))
        dataBinding.tvTest.setOnClickListener { }
    }


    fun onViewClick(view: View) {
        println("onViewClick")
    }

    fun onViewClick2(view: View) {
        println("onViewClick2")
    }

}