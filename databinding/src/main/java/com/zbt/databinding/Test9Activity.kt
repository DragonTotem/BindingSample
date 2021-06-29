package com.zbt.databinding

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.ObservableArrayList
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*

/**
 *Author: zbt
 *Time: 2021/6/28 21:49
 *Description: This is Test9Activity
 */
class Test9Activity : AppCompatActivity() {

    private val employeeObservableList = ObservableArrayList<EmployeeBean>().apply {
        for (i in 0..32) {
            add(
                EmployeeBean(
                    "${Random().nextInt()}",
                    "张三${Random().nextInt(100)}",
                    "${Random().nextInt(1000)}@163.com}"
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test9)

        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        val employeeAdapter = EmployeeAdapter(employeeObservableList)
        employeeAdapter.notifyDataSetChanged()
        employeeObservableList.addOnListChangedCallback(
            DynamicDataChangeCallback<ObservableArrayList<EmployeeBean>>(
                employeeAdapter
            )
        )
        recyclerView.adapter = employeeAdapter
    }

    fun addItem(view: View) {
        if (employeeObservableList.size >= 1) {
            val employeeBean = EmployeeBean(
                "${Random().nextInt()}",
                "张三${Random().nextInt(100)}",
                "${Random().nextInt(1000)}@163.com}"
            )
            employeeObservableList.add(0, employeeBean)
        }
    }

    fun addItemList(view: View) {
        if (employeeObservableList.size >= 2) {
            val employeeBeans: MutableList<EmployeeBean> = ArrayList<EmployeeBean>()
            for (i in 0..4) {
                val employeeBean = EmployeeBean(
                    "${Random().nextInt()}",
                    "张三${Random().nextInt(100)}",
                    "${Random().nextInt(1000)}@163.com}"
                )
                employeeBeans.add(employeeBean)
            }
            employeeObservableList.addAll(0, employeeBeans)
        }
    }

    fun removeItem(view: View) {
        if (employeeObservableList.size >= 2) {
            employeeObservableList.removeAt(1)
        }
    }

    fun updateItem(view: View) {
        if (employeeObservableList.size >= 2) {
            val employeeBean: EmployeeBean = employeeObservableList[1]
            employeeBean.name = "张三是大爷"
            employeeObservableList[1] = employeeBean
        }
    }
}