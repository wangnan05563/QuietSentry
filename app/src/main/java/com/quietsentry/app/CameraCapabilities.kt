package com.quietsentry.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder

/**
 * 读系统真实能力而不是假设：旧设备的镜头组合与录制分辨率差异很大，
 * 后续画质档位要按这里的结果动态置灰，避免选了不支持的分辨率直接启动失败。
 */
object CameraCapabilities {

    /** 值守要绑定的那一颗镜头；maxRecordSize 为空表示系统没有暴露录制尺寸（极旧设备） */
    data class Primary(val id: String, val facing: Int, val maxRecordSize: Size?)

    /**
     * 优先后置，其次前置，最后任意可用镜头。
     * 不能直接绑 DEFAULT_BACK_CAMERA：只有前置镜头的旧平板会当场抛异常，
     * 用户看到的却是“值守启动失败”，完全不知道该换设备还是换设置。
     */
    fun primary(context: Context): Primary? {
        val manager = cameraManager(context) ?: return null
        val cameras = manager.cameraIdList.mapNotNull { id ->
            runCatching {
                val chars = manager.getCameraCharacteristics(id)
                Primary(id, chars.get(CameraCharacteristics.LENS_FACING) ?: UNKNOWN_FACING, maxRecordSize(chars))
            }.getOrNull()
        }
        return cameras.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            ?: cameras.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            ?: cameras.firstOrNull()
    }

    /**
     * 外接或朝向未知的镜头不能用默认后置选择器：那会绑到另一颗镜头甚至绑定失败，
     * 只能按朝向精确匹配。
     */
    fun selector(primary: Primary): CameraSelector = when (primary.facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        CameraCharacteristics.LENS_FACING_BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        // 外接镜头不能用默认后置：那会绑到内置镜头，画面与用户预期完全不符
        else -> CameraSelector.Builder().requireLensFacing(primary.facing).build()
    }

    /**
     * 1080p 起步、逐级回退到 720p / 480p。
     * 旧设备常见只支持 720p 甚至 480p 录制，写死 1080p 会让录制准备阶段直接失败；
     * QualitySelector 自带“找不到就降一级”的语义，比自己维护分辨率表更不容易出错。
     */
    fun recorder(context: Context): Recorder.Builder {
        val max = primary(context)?.maxRecordSize
        val qualities = buildList {
            // 只有明确探测到 1080p 以上才申请 FHD，避免在不支持的设备上多做一轮无谓的协商
            if (max == null || max.width >= 1920) add(Quality.FHD)
            add(Quality.HD)
            add(Quality.SD)
        }
        return Recorder.Builder().setQualitySelector(
            QualitySelector.fromOrderedList(
                qualities,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
        )
    }

    fun describe(context: Context): String {
        val manager = cameraManager(context) ?: return "无法获取相机服务"

        return buildString {
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    else -> "外部"
                }
                val maxSize = maxRecordSize(chars)

                appendLine("镜头 $id · $facing · 最大录制 ${maxSize?.width ?: 0}x${maxSize?.height ?: 0}")
            }
        }.trim()
    }

    private fun cameraManager(context: Context): CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private fun maxRecordSize(chars: CameraCharacteristics): Size? =
        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaRecorder::class.java)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }

    private const val UNKNOWN_FACING = -1
}
