package com.zbt.databinding

import com.zbt.databinding.databinding.ActivityTest3Binding
import kotlin.random.Random

/**
 *Author: zbt
 *Time: 2021/6/27 22:27
 *Description: This is Test3Activity
 */
class Test3Activity : BaseActivity<ActivityTest3Binding>() {

    override fun getLayoutId(): Int {
        return R.layout.activity_test3
    }

    override fun initData() {
        val workBean = ObservableWorkBean("张三", false)
        dataBinding.workBean = workBean
        dataBinding.clickHandler = ObservableWorkBeanChangeHandler(workBean)
    }

    class ObservableWorkBeanChangeHandler(val workBean: ObservableWorkBean) {
        fun changeName() {
            /**
             * ObservableField 中的set()方法
             */
            workBean.name.set("李四 ${Random.nextInt(1000)}")
        }

        fun changeCard() {
            workBean.card.set(true)
        }
    }
}
