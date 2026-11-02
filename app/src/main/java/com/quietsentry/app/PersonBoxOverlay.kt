package com.quietsentry.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

/**
 * 实时页的人形框叠加层（T04053）。
 *
 * 为什么自绘而不是改 ImageView：框必须叠加在画面上方且随画面 letterbox 对齐，
 * ImageView 的 fitCenter 黑边会让整幅坐标系错位；独立 View 与 ImageView 同布局，
 * 按同一 fitCenter 公式换算归一化框，黑边区域不会画出框。
 *
 * 框的来源是摄像头端的稀疏检测（运动触发帧才推理）：显示 3 秒后自动消失，
 * 消失不代表人离开，只代表“最近一次确认的框已过期”——这正是 PRD 功耗口径下框的含义。
 */
class PersonBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_BOX
        strokeWidth = STROKE_WIDTH_DP * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 极淡的填充让人形区域“亮起来”，比纯描边更容易在暗画面里被注意到
        style = Paint.Style.FILL
        color = COLOR_BOX
        alpha = FILL_ALPHA
    }

    private var boxes: List<FloatArray> = emptyList()
    private var frameWidth = 0f
    private var frameHeight = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable {
        boxes = emptyList()
        invalidate()
    }

    /** 帧尺寸只用于宽高比：框坐标是归一化的，比例不对齐会整体偏移 */
    fun showBoxes(newBoxes: List<FloatArray>, width: Int, height: Int) {
        boxes = newBoxes
        frameWidth = width.toFloat()
        frameHeight = height.toFloat()
        invalidate()
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, VISIBLE_MS)
    }

    /** 画面断开时同步清框：旧框悬在“连接中”的兜底快照上没有意义 */
    fun clearBoxes() {
        handler.removeCallbacks(expire)
        boxes = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty() || frameWidth <= 0f || frameHeight <= 0f) return
        // 与 ImageView 的 fitCenter 完全同式：先取最小等比缩放，再居中补边
        val scale = minOf(width / frameWidth, height / frameHeight)
        val offsetX = (width - frameWidth * scale) / 2f
        val offsetY = (height - frameHeight * scale) / 2f
        val rect = RectF()
        for (box in boxes) {
            // 模型可能给出越界的框（坐标裁剪不完美）：收到画布内再做一次收敛
            rect.set(
                (box[0].coerceIn(0f, 1f) * frameWidth) * scale + offsetX,
                (box[1].coerceIn(0f, 1f) * frameHeight) * scale + offsetY,
                (box[2].coerceIn(0f, 1f) * frameWidth) * scale + offsetX,
                (box[3].coerceIn(0f, 1f) * frameHeight) * scale + offsetY,
            )
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, strokePaint)
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(expire)
        super.onDetachedFromWindow()
    }

    private companion object {
        val COLOR_BOX = Color.argb(200, 0, 191, 166) // qs_accent 的半透明版，保持单一强调色
        const val FILL_ALPHA = 36
        const val STROKE_WIDTH_DP = 2f
        const val VISIBLE_MS = 3_000L // 与服务端帧头的新鲜窗口一致（DeviceServer.PERSON_BOX_FRESH_MS）
    }
}
