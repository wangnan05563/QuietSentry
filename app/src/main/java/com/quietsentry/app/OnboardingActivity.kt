package com.quietsentry.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

/**
 * 首次启动的功能介绍页（走马灯）。
 *
 * 放在合法用途确认之后：用户先明确“能不能用”，再看“怎么用”。顺序反过来，
 * 会让人在还没理解使用边界时先被功能吸引，商店审核也更容易判定披露不充分。
 *
 * 不做自动轮播：值守场景用户会慢慢读，定时翻页会把没读完的说明直接推走。
 */
class OnboardingActivity : AppCompatActivity() {

    /** 快速轻扫才会翻页：距离和速度双阈值，避免手指停在页面上小幅拖动被当成翻页 */
    private val swipeMinDistance by lazy { dp(80) }
    private val swipeMinVelocity by lazy { dp(800) }

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) < swipeMinDistance || abs(velocityX) < swipeMinVelocity) return false
                // 水平分量必须明显大于垂直：用户沿纵向滑动时不应带动翻页
                if (abs(velocityX) < abs(velocityY)) return false
                goTo(if (dx < 0) page + 1 else page - 1)
                return true
            }
        })
    }

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
        actionButton.setOnClickListener { goTo(page + 1) }

        // 手势翻页挂在 flipper 上：子页全是静态文字，不消费触摸，事件必然落到这里
        flipper.setOnTouchListener { view, event ->
            gestures.onTouchEvent(event)
            view.performClick()
            true
        }
        render()
    }

    /**
     * 目标页越界或原地时静默忽略：第一页继续右滑、末页继续左滑都停在当前页，
     * 用户不需要从边界手势里“弹回”。末页的下一页动作就是进入配对引导（PRD 4.5）。
     */
    private fun goTo(target: Int) {
        if (target !in pages.indices) {
            if (target > pages.lastIndex) complete(toPairing = true)
            return
        }
        val forward = target > page
        // 翻页动画方向跟随手势：向左滑出下一页，向右滑出上一页，与手指方向一致。
        // 平台只内置了 left 方向的两个 slide 动画，right 方向用的是自建 res/anim 资源
        flipper.inAnimation = AnimationUtils.loadAnimation(
            this, if (forward) R.anim.slide_in_right else android.R.anim.slide_in_left
        )
        flipper.outAnimation = AnimationUtils.loadAnimation(
            this, if (forward) R.anim.slide_out_left else android.R.anim.slide_out_right
        )
        if (forward) flipper.showNext() else flipper.showPrevious()
        page = target
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

    /**
     * 走完引导落到配对引导（末页“开始使用”），跳过则回主界面：
     * 跳过者可能只是复装应用，不需要再被领一遍配对流程。
     */
    private fun complete(toPairing: Boolean = false) {
        OnboardingStore.markSeen(this)
        val next = if (toPairing) PairingActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Page(val index: String, val title: Int, val body: Int)
}
