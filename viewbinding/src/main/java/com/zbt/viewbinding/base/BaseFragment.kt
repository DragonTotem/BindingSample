package com.zbt.viewbinding.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding
import java.lang.reflect.ParameterizedType

/**
 *Author: zbt
 *Time: 2021/6/27 11:28
 *Description: This is BaseFragment
 */
abstract class BaseFragment<VB : ViewBinding, VM : ViewModel> : Fragment() {

    protected lateinit var binding: VB
    protected lateinit var viewModel: VM

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val parameterizedType = javaClass.genericSuperclass as ParameterizedType
        val types = parameterizedType.actualTypeArguments
        val vb = types[0] as Class<VB>
        binding = vb.getMethod("inflate", LayoutInflater::class.java)
            .invoke(null, layoutInflater) as VB

        viewModel = ViewModelProvider(this, ViewModelProvider.NewInstanceFactory())
            .get(types[1] as Class<VM>)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        initData()
    }

    abstract fun initView()
    abstract fun initData()
}