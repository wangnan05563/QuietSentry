package com.quietsentry.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity

/**
 * 首次启动的功能介绍页（走马灯）。
 *
 * 放在合法用途确认之后：用户先明确“能不能用”，再看“怎么用”。顺序反过来，
 * 会让人在还没理解使用边界时先被功能吸引，商店审核也更容易判定披露不充分。
 *
 * 不做自动轮播：值守场景用户会慢慢读，定时翻页会把没读完的说明直接推走。
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var dots: LinearLayout
    private lateinit var actionButton: Button
    private val dotViews = mutableListOf<View>()

    private var page = 0

    private val pages = listOf(
        Page("01", R.string.onboard_page1_title, R.string.onboard_page1_body),
        Page("02", R.string.onboard_page2_title, R.string.onboard_page2_body),
        Page("03", R.string.onboard_page3_title, R.string.onboard_page3_body),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        flipper = findViewById(R.id.onboard_flipper)
        dots = findViewById(R.id.onboard_dots)
        actionButton = findViewById(R.id.onboard_action)

        // 三页共用一套子布局，靠代码填充文案：页数少，没必要为每页复制一份 XML
        pages.forEachIndexed { index, item ->
            val view = layoutInflater.inflate(R.layout.item_onboard_page, flipper, false)
            view.findViewById<TextView>(R.id.onboard_index).text = item.index
            view.findViewById<TextView>(R.id.onboard_title).setText(item.title)
            view.findViewById<TextView>(R.id.onboard_body).setText(item.body)
            view.contentDescription = getString(item.title)
            view.findViewById<ImageView>(R.id.onboard_mark).setImageResource(R.drawable.ic_onboard_shield)
            flipper.addView(view)
            addDot()
        }

        findViewById<Button>(R.id.onboard_skip).setOnClickListener { complete() }
        actionButton.setOnClickListener {
            if (page < pages.lastIndex) {
                page++
                flipper.showNext()
                render()
            } else {
                complete()
            }
        }
        render()
    }

    private fun addDot() {
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
            background = getDrawable(R.drawable.onboard_dot)
        }
        dots.addView(dot)
        dotViews += dot
    }

    private fun render() {
        dotViews.forEachIndexed { index, dot ->
            val color = if (index == page) R.color.qs_accent else R.color.qs_text_hint
            dot.backgroundTintList = ColorStateList.valueOf(getColor(color))
        }
        actionButton.setText(
            if (page == pages.lastIndex) R.string.onboard_start else R.string.onboard_next
        )
    }

    private fun complete() {
        OnboardingStore.markSeen(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Page(val index: String, val title: Int, val body: Int)
}
