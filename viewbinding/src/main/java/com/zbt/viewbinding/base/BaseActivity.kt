package com.zbt.viewbinding.base

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding
import java.lang.reflect.ParameterizedType

/**
 *Author: zbt
 *Time: 2021/6/27 11:27
 *Description: This is BaseActivity
 */
abstract class BaseActivity<VB : ViewBinding, VM : ViewModel> : AppCompatActivity() {

    lateinit var binding: VB
    lateinit var viewModel: VM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val parameterizedType = this.javaClass.genericSuperclass as ParameterizedType
        val types = parameterizedType.actualTypeArguments
        val vb = types[0] as Class<VB>
        binding = vb.getMethod("inflate", LayoutInflater::class.java)
            .invoke(null, layoutInflater) as VB

        viewModel = ViewModelProvider(this, ViewModelProvider.NewInstanceFactory())
            .get(types[1] as Class<VM>)

        setContentView(binding.root)

        initView()
        initData()
    }

    protected abstract fun initView()
    protected abstract fun initData()
}