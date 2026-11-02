package com.quietsentry.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 事件播放器的响度历史条（T04059，FadCam 音频轨参照）。
 *
 * 为什么画“历史”而不是瞬时波形：事件是短片段，用户拖动回看时最想知道
 * “哪一段有声音”，从左到右的响度柱天然对齐播放进度，比滚动的瞬时波形更好读。
 * 数据来自 Visualizer 的输出采样，不落盘、不进数据库——播放端实时取，录像端零改动。
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    init {
        // 波形是视觉装饰：响度信息无对应的文字语义，TalkBack 聚焦只会报“未加标签”，
        // 声明为不重要让读屏直接跳过，播放状态仍由播放器控件承载
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 191, 166) // qs_accent 的不透明版：小面积高亮更易读
    }

    /** 环形历史：容量随视图宽度在首次绘制时确定，避免依赖测量时机 */
    private var history = FloatArray(0)
    private var head = 0
    private var filled = 0

    /** push 一个 0..1 的响度值；超出按 1 处理，负值视为无声 */
    fun push(amplitude: Float) {
        if (history.isEmpty()) return
        history[head] = amplitude.coerceIn(0f, 1f)
        head = (head + 1) % history.size
        filled = minOf(filled + 1, history.size)
        invalidate()
    }

    fun clear() {
        filled = 0
        head = 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 每根柱 6px（柱 4px + 缝 2px）：更多柱只是更密，可读性反而下降
        val capacity = (w / 6).coerceAtLeast(16)
        history = FloatArray(capacity)
        head = 0
        filled = 0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (history.isEmpty()) return
        val barWidth = width / history.size.toFloat()
        for (i in 0 until filled) {
            // 从最旧的一根画起：新样本永远出现在最右侧，与播放进度方向一致
            val value = history[(head + i) % history.size]
            val barHeight = (value * (height - 4)).coerceAtLeast(2f)
            val left = i * barWidth + 1f
            canvas.drawRect(
                left, height - barHeight, left + barWidth - 2f, height.toFloat(),
                paint
            )
        }
    }
}
