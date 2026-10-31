package com.quietsentry.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity

/**
 * 首次启动的三屏合法用途确认（FR-01）。
 *
 * 用 ViewFlipper 而不是 ViewPager2：不引入新依赖，三页静态说明也不需要手势滑动。
 * 勾选门前禁止进入主界面——这是应用商店判定“是否显著披露”的关键证据。
 */
class ConsentActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var progressText: TextView
    private lateinit var checkbox: CheckBox
    private lateinit var actionButton: Button

    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consent)

        flipper = findViewById(R.id.consent_flipper)
        progressText = findViewById(R.id.consent_progress)
        checkbox = findViewById(R.id.consent_checkbox)
        actionButton = findViewById(R.id.consent_action)

        checkbox.setOnCheckedChangeListener { _, _ -> render() }
        actionButton.setOnClickListener {
            if (page < LAST_PAGE) {
                page++
                flipper.showNext()
                render()
            } else if (checkbox.isChecked) {
                ConsentStore.accept(this)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
        render()
    }

    private fun render() {
        progressText.text = getString(R.string.consent_progress, page + 1, LAST_PAGE + 1)
        // 勾选框只在最后一页出现：前两页就显示会诱导用户在没读完说明时直接同意
        checkbox.visibility = if (page == LAST_PAGE) View.VISIBLE else View.GONE
        actionButton.text = getString(
            if (page == LAST_PAGE) R.string.consent_start else R.string.consent_next
        )
        actionButton.isEnabled = page < LAST_PAGE || checkbox.isChecked
    }

    private companion object {
        const val LAST_PAGE = 2
    }
}
