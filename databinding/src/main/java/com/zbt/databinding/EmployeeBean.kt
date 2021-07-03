package com.zbt.databinding

import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField

/**
 *Author: zbt
 *Time: 2021/6/27 16:46
 *Description: This is EmployeeBean
 */
data class EmployeeBean(val id: String, var name: String, var email: String = "123@163.com")

class ObservableWorkBean(name: String, card: Boolean) {
    var name: ObservableField<String> = ObservableField(name)
    var card: ObservableBoolean = ObservableBoolean(card)
}

class WorkBean : BaseObservable() {
    //如果是 private 修饰符，则在成员变量的 get 方法上添加 @Bindable 注解
    @Bindable
    var workName: String = ""
        get() = field
        set(value) {
            field = value
            notifyPropertyChanged(com.zbt.databinding.BR.workName)
        }

    var workContent: String = ""
        //如果是 private 修饰符，则在成员变量的 get 方法上添加 @Bindable 注解，在kotlin中属性的get()使用get()是不对在BR中生成id
        @Bindable
        get() = field
        set(value) {
            field = value
            /**
             * 如果是notifyChange()，可以不用@Bindable注解，@Bindable主要是在BR文件中生成相应的id，id可以关联相应属性的视图
             */
            // 更新所有字段
            notifyChange()
        }

    var workTime: String = ""
}

class AdapterBean {
    var color: Int = 0xFF4500
}