package com.quietsentry.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 端侧人形检测（PRD FR-07 P1）。
 *
 * 只在运动触发后确认一次，而不是逐帧跑模型：EfficientDet-Lite0 在 CPU 上单帧要几十毫秒，
 * 常驻推理会把值守从“低功耗录像”变成“持续跑 AI”，与 PRD 的功耗口径冲突。
 *
 * 任何失败（资产缺失、ABI 不支持、张量形状不符）都只降级为“没有人形确认”，
 * 运动检测与录制本身不能因此中断。
 */
class HumanDetector private constructor(
    private val interpreter: Interpreter,
    private val inputSize: Int,
) : Closeable {

    private val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
    private val pixels = IntArray(inputSize * inputSize)
    private val boxes = Array(1) { Array(MAX_DETECTIONS) { FloatArray(4) } }
    private val classes = Array(1) { FloatArray(MAX_DETECTIONS) }
    private val scores = Array(1) { FloatArray(MAX_DETECTIONS) }
    private val count = FloatArray(1)

    /** 返回是否检测到人形；异常按“未检测到”处理，避免推理失败冒泡到相机分析线程 */
    fun detect(bitmap: Bitmap): Boolean = runCatching {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        var index = 0
        for (row in 0 until inputSize) {
            for (col in 0 until inputSize) {
                val color = pixels[index++]
                input[0][row][col][0] = ((color shr 16) and 0xFF) / 255f
                input[0][row][col][1] = ((color shr 8) and 0xFF) / 255f
                input[0][row][col][2] = (color and 0xFF) / 255f
            }
        }
        if (scaled !== bitmap) scaled.recycle()

        // EfficientDet-Lite0 的四个输出依次是：框、类别、分数、有效数量
        interpreter.run(input, mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count))
        val detected = count[0].toInt().coerceIn(0, MAX_DETECTIONS)
        (0 until detected).any {
            classes[0][it] == PERSON_CLASS && scores[0][it] >= THRESHOLD
        }
    }.getOrElse {
        Log.w(TAG, "human detection failed", it)
        false
    }

    /**
     * 用零填充输入跑一次前向，确认解释器与张量形状可用。
     * 只服务诊断页：让“人形检测已启用”有推理证据，而不是只看到资产文件存在。
     */
    fun probe(): Boolean = runCatching {
        interpreter.run(input, mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count))
        Log.i(TAG, "human detector probe ok")
        true
    }.getOrElse {
        Log.w(TAG, "human detector probe failed", it)
        false
    }

    override fun close() {
        runCatching { interpreter.close() }
    }

    companion object {
        private const val TAG = "QuietSentry"
        private const val MODEL_ASSET = "models/efficientdet_lite0.tflite"
        private const val PERSON_CLASS = 0f
        private const val THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 25

        /** 资产缺失时返回 null：调用方据此继续只用运动检测，不把“有模型”写死进 UI */
        fun create(context: Context): HumanDetector? = runCatching {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .put(bytes)
                .apply { rewind() }
            // 两线程推理：单线程更省电但会让分析线程多等十几毫秒，两线程是功耗与延迟的折中
            val interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(2))
            val shape = interpreter.getInputTensor(0).shape()
            if (shape.size != 4) {
                interpreter.close()
                null
            } else {
                HumanDetector(interpreter, shape[1]).also {
                    // 加载成功要有可检索的日志：值守没有画面时，排障第一步就是确认模型是否真的起来了
                    Log.i(TAG, "human detector ready input=${shape.contentToString()}")
                }
            }
        }.onFailure {
            Log.w(TAG, "human detector unavailable", it)
        }.getOrNull()
    }
}
