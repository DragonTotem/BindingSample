package com.zbt.viewbinding

import com.zbt.viewbinding.base.BaseActivity
import com.zbt.viewbinding.databinding.ActivityMainBinding

/**
 *Author: zbt
 *Time: 2021/6/27 12:24
 *Description: This is SecondActivity
 */
class SecondActivity: BaseActivity<ActivityMainBinding, TestViewModel>() {
    override fun initView() {
        binding.tvTitle.text = "这是SecondActivity"

        val transaction = supportFragmentManager.beginTransaction()
        //也可以只样使用id
        transaction.replace(binding.container.id, SecondFragment())
        transaction.commit()
    }

    override fun initData() {
        println("response: ${viewModel.requestData("SecondActivity")}")
    }
}