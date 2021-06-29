package com.zbt.databinding

import android.view.View
import androidx.databinding.Observable
import com.zbt.databinding.databinding.ActivityTest2Binding

/**
 *Author: zbt
 *Time: 2021/6/27 22:27
 *Description: This is Test2Activity
 */
class Test2Activity : BaseActivity<ActivityTest2Binding>() {

    private val workBean = WorkBean()

    override fun getLayoutId(): Int {
        return R.layout.activity_test2
    }

    override fun initData() {
        workBean.workName = "dataBinding"
        workBean.workContent = "数据刷新"
        workBean.workTime = "1个小时"

        /**
         * 注册实现androidx.databinding.Observable中的OnPropertyChangedCallback监听器，当可观察对象的数据发生更改时，
         * 监听器就会收到通知，其中 propertyId 就用于标识特定的字段
         */
        workBean.addOnPropertyChangedCallback(object : Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                when (propertyId) {
                    BR.workName -> println("BR.workName changed")
                    BR._all -> println("all changed")
                    else -> println("other changed")
                }
            }
        })

        dataBinding.workBean = workBean
    }

    fun changeNameAndTime(view: View) {
        workBean.workName = "写代码"
        workBean.workTime = "4小时"
    }

    fun changeContentAndTime(view: View) {
        workBean.workContent = "加快速度"
        workBean.workTime = "8小时"
    }

    fun changeTime(view: View) {
        workBean.workTime = "2个工作日"
    }
}
