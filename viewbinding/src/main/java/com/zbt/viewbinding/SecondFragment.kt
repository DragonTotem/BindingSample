package com.zbt.viewbinding

import com.zbt.viewbinding.base.BaseFragment
import com.zbt.viewbinding.databinding.FragmentMainBinding

/**
 *Author: zbt
 *Time: 2021/6/27 15:13
 *Description: This is SecondFragment
 */
class SecondFragment : BaseFragment<FragmentMainBinding, TestViewModel>() {
    override fun initView() {
        binding.tvShowText.text = "这是第二个Fragment"
    }

    override fun initData() {
        println("response: ${viewModel.requestData("secondFragment")}")
    }
}