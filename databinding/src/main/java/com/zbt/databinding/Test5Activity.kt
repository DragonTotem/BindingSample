package com.zbt.databinding

import android.content.Context
import android.text.Editable
import android.widget.Toast
import androidx.databinding.OnRebindCallback
import androidx.databinding.ViewDataBinding
import com.zbt.databinding.databinding.ActivityTest5Binding

/**
 *Author: zbt
 *Time: 2021/6/27 22:27
 *Description: This is Test5Activity
 */
class Test5Activity : BaseActivity<ActivityTest5Binding>() {

    override fun getLayoutId(): Int {
        return R.layout.activity_test5
    }

    override fun initData() {
        val workBean = ObservableWorkBean("张三", true)
        dataBinding.workBean = workBean
        dataBinding.eventBinding = EventBinding(this, workBean)

        dataBinding.addOnRebindCallback(object : OnRebindCallback<ActivityTest5Binding>() {
            override fun onPreBind(binding: ActivityTest5Binding?): Boolean {
                println("RebindCallback onPreBind")
                return super.onPreBind(binding)
            }

            override fun onBound(binding: ActivityTest5Binding?) {
                super.onBound(binding)
                println("RebindCallback onBound")
            }

            override fun onCanceled(binding: ActivityTest5Binding?) {
                super.onCanceled(binding)
                println("RebindCallback onCanceled")
            }
        })
    }

    class EventBinding(private val context: Context, private val workBean: ObservableWorkBean) {
        fun onNameClick(observableWorkBean: ObservableWorkBean) {
            Toast.makeText(context, "card: ${observableWorkBean.card.get()}", Toast.LENGTH_LONG)
                .show()
        }

        fun onAfterTextChanged(s: Editable) {
            workBean.name.set(s.toString())
        }
    }

    class RebindCallback : OnRebindCallback<ActivityTest5Binding>() {

    }
}
