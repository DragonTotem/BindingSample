package com.zbt.viewbinding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.zbt.viewbinding.databinding.FragmentMainBinding

/**
 *Author: zbt
 *Time: 2021/6/27 9:55
 *Description: This is MainFragment
 */
class MainFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding = FragmentMainBinding.inflate(inflater)
        initView(binding)
        return binding.root
    }

    private fun initView(binding: FragmentMainBinding) {
        binding.tvShowText.text = "在MainFragment中设置的"
    }
}