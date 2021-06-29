package com.zbt.databinding

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.widget.TextView
import android.widget.Toast
import androidx.databinding.BindingConversion
import androidx.databinding.BindingMethod
import androidx.databinding.BindingMethods
import com.zbt.databinding.databinding.ActivityTest8Binding


/**
 *Author: zbt
 *Time: 2021/6/28 21:19
 *Description: This is Test8Activity
 */
class Test8Activity : BaseActivity<ActivityTest8Binding>() {

    companion object {
//        /**
//         * 更改原生的
//         */
//        @JvmStatic
//        @BindingAdapter("android:text")
//        fun setText(tv: TextView, text: String) {
//            tv.text = "$text 是大佬"
//        }

//        /**
//         * 自定义
//         */
//        @JvmStatic
//        @BindingAdapter("text")
//        fun printText(tv: TextView, text: String) {
//            println("获取到的text：$text")
//        }

//        @BindingConversion
//        fun conversionString(text: String): String? {
//            return "$text-conversionString"
//        }
    }

    override fun getLayoutId() = R.layout.activity_test8

    override fun initData() {
        val employeeBean = EmployeeBean("12345", "张三", "12345@163.com")
        dataBinding.employeeBean = employeeBean

        dataBinding.tvtToast.setOnClickListener {
            val workBean = WorkBean()
            dataBinding.workBean = workBean
            workBean.workName = "码字"
        }
    }
}

@BindingConversion
fun convertStringToColor(str: String): Int {
    return when (str) {
        "红色" -> Color.parseColor("#FF1493")
        "橙色" -> Color.parseColor("#0000FF")
        else -> Color.parseColor("#FF4500")
    }
}

@BindingMethods(
    BindingMethod(
        type = TextView::class,
        attribute = "showToast",
        method = "showToast"
    )
)
class TextViewToast(context: Context, attrs: AttributeSet) :
    androidx.appcompat.widget.AppCompatTextView(context, attrs) {
    fun showToast(s: String?) {
        if (TextUtils.isEmpty(s)) {
            return
        }
        Toast.makeText(context, s, Toast.LENGTH_LONG).show()
    }
}