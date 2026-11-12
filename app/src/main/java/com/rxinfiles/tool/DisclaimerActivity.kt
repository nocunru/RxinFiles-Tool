package com.rxinfiles.tool

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 启动页 / 免责声明。
 * 用户同意后写入标记，后续启动直接进入主界面。
 */
class DisclaimerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // 已经同意过则直接进入
        if (prefs.getBoolean("disclaimer_accepted", false)) {
            enterMain()
            return
        }

        setContentView(R.layout.activity_disclaimer)

        val versionText = findViewById<TextView>(R.id.versionText)
        versionText.text = "RxinFiles-Tool v${BuildConfig.VERSION_NAME}"

        val agreeCheckBox = findViewById<CheckBox>(R.id.agreeCheckBox)
        val agreeBtn = findViewById<Button>(R.id.agreeBtn)
        val disagreeBtn = findViewById<Button>(R.id.disagreeBtn)
        val themeToggle = findViewById<Button>(R.id.themeToggle)

        themeToggle.setOnClickListener { ThemeStore.toggle(this) }

        agreeCheckBox.setOnCheckedChangeListener { _, checked ->
            agreeBtn.isEnabled = checked
            agreeBtn.alpha = if (checked) 1f else 0.5f
        }

        agreeBtn.setOnClickListener {
            prefs.edit().putBoolean("disclaimer_accepted", true).apply()
            enterMain()
        }

        disagreeBtn.setOnClickListener { finish() }
    }

    private fun enterMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val PREFS = "app_prefs"
    }
}
