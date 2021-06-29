package com.zbt.databinding

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding

/**
 *Author: zbt
 *Time: 2021/6/27 17:15
 *Description: This is BaseActivity
 */
abstract class BaseActivity<DB : ViewDataBinding> : AppCompatActivity() {

    protected lateinit var dataBinding: DB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataBinding = DataBindingUtil.setContentView(this, getLayoutId())

        initData()
    }

    abstract fun getLayoutId(): Int

    abstract fun initData()
}