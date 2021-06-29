package com.zbt.databinding

import com.zbt.databinding.databinding.ActivityTest1Binding

/**
 *Author: zbt
 *Time: 2021/6/27 17:03
 *Description: This is Test1Activity
 */
class Test1Activity : BaseActivity<ActivityTest1Binding>() {

    override fun getLayoutId(): Int {
        return R.layout.activity_test1
    }

    override fun initData() {
        dataBinding.employeeBean = EmployeeBean("12345", "张三", "zhangsan@163.com")
        dataBinding.workBean = ObservableWorkBean("李四", false)

        dataBinding.btnAttrControl.setOnClickListener {
            dataBinding.workBean?.run {
                this.card.set(!this.card.get())
            }
        }
    }
}