package com.zbt.viewbinding

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.zbt.viewbinding.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initView(binding)
    }

    private fun initView(binding: ActivityMainBinding) {
        binding.tvTitle.text = "viewBindingTitle"
        binding.tvTitle.setOnClickListener {
            startActivity(Intent(this@MainActivity, SecondActivity::class.java))
        }

        val fragmentTransaction = supportFragmentManager.beginTransaction()
        fragmentTransaction.replace(R.id.container, MainFragment())
        fragmentTransaction.commit()
    }
}