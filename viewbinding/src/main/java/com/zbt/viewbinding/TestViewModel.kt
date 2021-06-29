package com.zbt.viewbinding

import androidx.lifecycle.ViewModel

/**
 *Author: zbt
 *Time: 2021/6/27 16:19
 *Description: This is BaseViewModel
 */
open class TestViewModel : ViewModel() {

    fun requestData(params: String): String {
        return "$params response"
    }
}