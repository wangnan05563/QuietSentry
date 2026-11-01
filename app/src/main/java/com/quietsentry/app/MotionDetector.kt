package com.quietsentry.app

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * 端侧运动检测（PRD FR-07）：只用低分辨率帧差，不用模型，保证长时间值守的功耗与发热可接受。
 *
 * 为什么按块取均值而不是逐像素：320x240 逐像素每帧近 8 万次运算，检测精度不会更高，
 * 反而在 CPU 上持续吃电；8x6 个块的灰度均值足以反映“画面是否变化”。
 *
 * 为什么整屏同变判为光照突变：开关灯、窗帘被拉开会让所有块一起变化，
 * 这是环境事件而不是有人移动，不能算运动，否则夜间每次开灯都会误报。
 */
class MotionDetector(
    sensitivity: Int,
    private val gridMask: BooleanArray,
    private val thumbnailDir: File?,
    private val humanDetector: HumanDetector?,
    private val onMotion: (confidence: Int, thumbnail: File?, person: Boolean) -> Unit,
) : ImageAnalysis.Analyzer {

    // 灵敏度 1（迟钝）到 5（灵敏）映射为“需要多少比例的检测块发生变化”
    private val minChangedRatio = 0.36f - (sensitivity.coerceIn(1, 5) - 1) * 0.07f

    private val blocks = IntArray(BLOCK_COUNT)
    private var previous = IntArray(BLOCK_COUNT)
    private var hasPrevious = false
    private var hotFrames = 0
    private var lastEventAtMs = 0L
    private var maskedChangedRatio = 0f
    private var fullChangedRatio = 0f

    override fun analyze(image: ImageProxy) {
        try {
            // 同一事件源 60 秒内的连续触发合并为一条，避免一次路人经过刷出十几条事件
            if (System.currentTimeMillis() - lastEventAtMs < MERGE_WINDOW_MS) return
            // 第一帧只作为基线，没有可比对的对象
            if (!sampleBlocks(image)) return

            when {
                // 几乎整屏都在变：判定为光照突变，不产生事件
                fullChangedRatio >= LIGHT_CHANGE_RATIO -> hotFrames = 0
                maskedChangedRatio >= minChangedRatio -> hotFrames++
                else -> hotFrames = 0
            }

            if (hotFrames >= HOT_FRAMES) {
                lastEventAtMs = System.currentTimeMillis()
                hotFrames = 0
                // 置信度用“超出阈值多少”表达：阈值附近只是轻微移动
                val confidence = ((maskedChangedRatio / minChangedRatio) * 50).toInt().coerceIn(1, 100)
                // 缩略图与推理必须在 image.close() 之前取：关闭后 ImageProxy 的画面缓冲会失效
                val bitmap = image.toBitmap()
                // 人形确认只发生在事件触发帧：逐帧推理会让值守变成持续跑模型
                val person = humanDetector?.detect(bitmap) ?: false
                val thumbnail = saveThumbnail(bitmap)
                bitmap.recycle()
                onMotion(confidence, thumbnail, person)
            }
        } catch (e: Exception) {
            // 分析器异常不能影响录制：吞掉异常只会丢掉这一次判定，不会中断采集
        } finally {
            image.close()
        }
    }

    /**
     * 保存触发帧作为事件缩略图（PRD FR-07：事件条目包含缩略图）。
     * 直接压分析流原尺寸（320x240）而不再缩放：画面缓冲已经拿到手，额外缩放只是多一次拷贝；
     * 十几 KB 的 JPEG 足够让回看列表认出“当时发生了什么”。
     * 写盘失败不能影响事件判定，返回 null，由索引层记录一条无缩略图事件。
     * 位图由调用方统一回收：人形推理与缩略图压缩共用同一张，提前回收会让后者拿到空缓冲。
     */
    private fun saveThumbnail(bitmap: Bitmap): File? {
        val dir = thumbnailDir ?: return null
        return try {
            dir.mkdirs()
            val file = File(dir, "evt_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it)
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /** 采样成功返回 true；首次调用（无基线）返回 false。 */
    private fun sampleBlocks(image: ImageProxy): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return false
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        for (row in 0 until ROWS) {
            val top = row * height / ROWS
            val bottom = (row + 1) * height / ROWS
            for (col in 0 until COLS) {
                val left = col * width / COLS
                val right = (col + 1) * width / COLS
                var sum = 0
                var samples = 0
                var y = top
                while (y < bottom) {
                    var x = left
                    while (x < right) {
                        val offset = y * rowStride + x * pixelStride
                        if (offset < buffer.limit()) {
                            sum += buffer.get(offset).toInt() and 0xFF
                            samples++
                        }
                        x += SAMPLE_STEP
                    }
                    y += SAMPLE_STEP
                }
                if (samples > 0) blocks[row * COLS + col] = sum / samples
            }
        }

        if (!hasPrevious) {
            previous = blocks.copyOf()
            hasPrevious = true
            return false
        }

        var maskedChanged = 0
        var allChanged = 0
        for (i in 0 until BLOCK_COUNT) {
            if (abs(blocks[i] - previous[i]) >= BLOCK_DELTA) {
                allChanged++
                if (gridMask[i]) maskedChanged++
            }
        }
        val maskedTotal = gridMask.count { it }.coerceAtLeast(1)
        maskedChangedRatio = maskedChanged.toFloat() / maskedTotal
        fullChangedRatio = allChanged.toFloat() / BLOCK_COUNT
        previous = blocks.copyOf()
        return true
    }

    companion object {
        private const val COLS = 8
        private const val ROWS = 6
        private const val BLOCK_COUNT = COLS * ROWS

        /** 块内采样步长（像素）：太密没意义，太疏会漏掉小目标。
         *  分析路从 320x240 提到 640x480 后像素翻四倍，步长同步放大，
         *  让每帧采样点数与原来的功耗水平持平，长时间值守才不会被检测本身拖垮。 */
        private const val SAMPLE_STEP = 6

        /** 灰度差超过该值的块才算“变了”，低于此值是传感器噪声与自动曝光的抖动 */
        private const val BLOCK_DELTA = 14

        /** 连续热帧数：单帧抖动不算事件，要求画面持续变化约 0.5 秒以上 */
        private const val HOT_FRAMES = 3

        /** 同源合并窗口（PRD FR-07：60 秒） */
        private const val MERGE_WINDOW_MS = 60_000L

        /** 超过该比例的块同时变化视为光照突变 */
        private const val LIGHT_CHANGE_RATIO = 0.7f

        /** 缩略图 JPEG 质量：回看列表只看轮廓，70 已足够且体积约为无损的 1/10 */
        private const val THUMB_QUALITY = 70

        /** 默认全画面参与检测；网格勾选（PRD FR-07）由设置层传入 */
        val ALL_GRID = BooleanArray(BLOCK_COUNT) { true }
    }
}
