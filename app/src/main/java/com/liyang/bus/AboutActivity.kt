package com.liyang.bus

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // Show actual version
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.tvVersion).text = "v$versionName"
        } catch (_: Exception) {}
    }
}
