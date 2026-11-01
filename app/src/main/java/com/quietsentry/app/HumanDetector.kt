package com.quietsentry.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
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
 * 输入类型由模型决定：EfficientDet-Lite0 的官方 metadata 版本是 UINT8 输入，
 * 不能按 0..1 的 float 填；加载时读张量类型再选填充方式，否则会在 run 时抛类型不兼容。
 *
 * 任何失败（资产缺失、ABI 不支持、张量类型/形状不符）都只降级为“没有人形确认”，
 * 运动检测与录制本身不能因此中断。
 */
class HumanDetector private constructor(
    private val interpreter: Interpreter,
    private val inputSize: Int,
    private val inputKind: InputKind,
) : Closeable {

    private enum class InputKind { FLOAT32, UINT8 }

    private val floatInput = if (inputKind == InputKind.FLOAT32) {
        Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
    } else {
        null
    }

    private val byteInput = if (inputKind == InputKind.UINT8) {
        Array(1) { Array(inputSize) { Array(inputSize) { ByteArray(3) } } }
    } else {
        null
    }

    private val pixels = IntArray(inputSize * inputSize)
    private val boxes = Array(1) { Array(MAX_DETECTIONS) { FloatArray(4) } }
    private val classes = Array(1) { FloatArray(MAX_DETECTIONS) }
    private val scores = Array(1) { FloatArray(MAX_DETECTIONS) }
    private val count = FloatArray(1)

    /** 返回是否检测到人形；异常按“未检测到”处理，避免推理失败冒泡到相机分析线程 */
    fun detect(bitmap: Bitmap): Boolean {
        val input = byteInput ?: floatInput ?: return false
        return runCatching {
            val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            var index = 0
            if (byteInput != null) {
                for (row in 0 until inputSize) {
                    for (col in 0 until inputSize) {
                        val color = pixels[index++]
                        // UINT8 模型直接吃 0..255；转 Byte 后按 uint8 解释
                        byteInput[0][row][col][0] = ((color shr 16) and 0xFF).toByte()
                        byteInput[0][row][col][1] = ((color shr 8) and 0xFF).toByte()
                        byteInput[0][row][col][2] = (color and 0xFF).toByte()
                    }
                }
            } else {
                for (row in 0 until inputSize) {
                    for (col in 0 until inputSize) {
                        val color = pixels[index++]
                        floatInput!![0][row][col][0] = ((color shr 16) and 0xFF) / 255f
                        floatInput[0][row][col][1] = ((color shr 8) and 0xFF) / 255f
                        floatInput[0][row][col][2] = (color and 0xFF) / 255f
                    }
                }
            }
            if (scaled !== bitmap) scaled.recycle()

            interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs())
            val detected = count[0].toInt().coerceIn(0, MAX_DETECTIONS)
            (0 until detected).any {
                classes[0][it] == PERSON_CLASS && scores[0][it] >= THRESHOLD
            }
        }.getOrElse {
            Log.w(TAG, "human detection failed", it)
            false
        }
    }

    /**
     * 用零填充输入跑一次前向，确认解释器与张量类型/形状可用。
     * 只服务诊断页：让“人形检测已启用”有推理证据，而不是只看到资产文件存在。
     */
    fun probe(): Boolean {
        val input = byteInput ?: floatInput ?: return false
        return runCatching {
            interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs())
            Log.i(TAG, "human detector probe ok")
            true
        }.getOrElse {
            Log.w(TAG, "human detector probe failed", it)
            false
        }
    }

    override fun close() {
        runCatching { interpreter.close() }
    }

    /**
     * 多输出模型必须走 `runForMultipleInputsOutputs`：TFLite 2.16 没有 `run(Map,Map)` 重载，
     * 传两个 Map 会命中 `run(Object,Object)`，把整个 map 当成一个输出张量，
     * 运行时报 `cannot resolve DataType of HashMap`。
     */
    private fun outputs(): MutableMap<Int, Any> {
        // EfficientDet-Lite0 的四个输出依次是：框、类别、分数、有效数量
        val map = HashMap<Int, Any>(4)
        map[0] = boxes
        map[1] = classes
        map[2] = scores
        map[3] = count
        return map
    }

    companion object {
        private const val TAG = "QuietSentry"
        private const val MODEL_ASSET = "models/efficientdet_lite0.tflite"
        private const val PERSON_CLASS = 0f
        private const val THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 25

        /** 资产缺失或张量不受支持时返回 null：调用方据此继续只用运动检测 */
        fun create(context: Context): HumanDetector? = runCatching {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .put(bytes)
                .apply { rewind() }
            // 两线程推理：单线程更省电但会让分析线程多等十几毫秒，两线程是功耗与延迟的折中
            val interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(2))
            val shape = interpreter.getInputTensor(0).shape()
            val type = interpreter.getInputTensor(0).dataType()
            val kind = when (type) {
                DataType.FLOAT32 -> InputKind.FLOAT32
                DataType.UINT8 -> InputKind.UINT8
                else -> null
            }
            if (shape.size != 4 || kind == null) {
                interpreter.close()
                null
            } else {
                HumanDetector(interpreter, shape[1], kind).also {
                    // 加载成功要有可检索的日志：值守没有画面时，排障第一步就是确认模型是否真的起来了
                    Log.i(TAG, "human detector ready input=${shape.contentToString()} type=$type")
                }
            }
        }.onFailure {
            Log.w(TAG, "human detector unavailable", it)
        }.getOrNull()
    }
}
