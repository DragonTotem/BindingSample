package com.zbt.databinding

import com.zbt.databinding.databinding.ActivityTest7Binding

/**
 *Author: zbt
 *Time: 2021/6/27 22:27
 *Description: This is Test7Activity
 */
class Test7Activity : BaseActivity<ActivityTest7Binding>() {

    override fun getLayoutId(): Int {
        return R.layout.activity_test7
    }

    override fun initData() {
        val workBean = WorkBean()
        workBean.workName = "张三"
        workBean.workContent = "程序员"
        workBean.workTime = "007"

        dataBinding.workBean = workBean

        dataBinding.btnShow.setOnClickListener {
            if (!dataBinding.viewStub.isInflated) {
                dataBinding.viewStub.viewStub?.inflate()
            }
        }
    }
}
