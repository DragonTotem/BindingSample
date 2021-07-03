package com.zbt.databinding

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    private fun <T : Activity> startActivityByClass(c: Class<T>) {
        startActivity(Intent(this, c))
    }

    fun gotoActivity1(view: View) {
        startActivityByClass(Test1Activity::class.java)
    }

    fun gotoActivity2(view: View) {
        startActivityByClass(Test2Activity::class.java)
    }

    fun gotoActivity3(view: View) {
        startActivityByClass(Test3Activity::class.java)
    }

    fun gotoActivity4(view: View) {
        startActivityByClass(Test4Activity::class.java)
    }

    fun gotoActivity5(view: View) {
        startActivityByClass(Test5Activity::class.java)
    }

    fun gotoActivity6(view: View) {
        startActivityByClass(Test6Activity::class.java)
    }

    fun gotoActivity7(view: View) {
        startActivityByClass(Test7Activity::class.java)
    }


    fun gotoActivity8(view: View) {
        startActivityByClass(Test8Activity::class.java)
    }

    fun gotoActivity9(view: View) {
        startActivityByClass(Test9Activity::class.java)
    }

    fun gotoActivity10(view: View) {
        startActivityByClass(Test10Activity::class.java)
    }

}