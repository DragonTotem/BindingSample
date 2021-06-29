package com.zbt.databinding

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.zbt.databinding.databinding.ItemViewBinding

/**
 *Author: zbt
 *Time: 2021/6/28 22:07
 *Description: This is EmployeeAdapter
 */
class EmployeeAdapter(private val employeeBeans: List<EmployeeBean>) :
    RecyclerView.Adapter<EmployeeAdapter.EmployeeViewHolder>() {

    inner class EmployeeViewHolder(val binding: ItemViewBinding) :
        RecyclerView.ViewHolder(binding.root) {
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmployeeViewHolder {
        return EmployeeViewHolder(
            DataBindingUtil.inflate(
                LayoutInflater.from(parent.context),
                R.layout.item_view,
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: EmployeeViewHolder, position: Int) {
        holder.binding.employeeBean = employeeBeans[position]
    }

    override fun getItemCount(): Int {
        return employeeBeans.size
    }
}