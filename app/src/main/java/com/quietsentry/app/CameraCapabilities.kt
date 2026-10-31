package com.quietsentry.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder

/**
 * 读系统真实能力而不是假设：旧设备的镜头组合与录制分辨率差异很大，
 * 后续画质档位要按这里的结果动态置灰，避免选了不支持的分辨率直接启动失败。
 */
object CameraCapabilities {

    fun describe(context: Context): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "无法获取相机服务"

        return buildString {
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    else -> "外部"
                }
                val maxSize = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(MediaRecorder::class.java)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }

                appendLine("镜头 $id · $facing · 最大录制 ${maxSize?.width ?: 0}x${maxSize?.height ?: 0}")
            }
        }.trim()
    }
}
