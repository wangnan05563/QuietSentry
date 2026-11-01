package com.quietsentry.app

import android.app.ActivityManager
import android.content.Context
import android.util.Size
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/**
 * 端侧检测算力档位（PRD FR-07 / M7 P1 的降级口径）。
 *
 * 为什么按内存与 CPU 核数分档，而不是去读 NNAPI / GPU：
 * 这两个加速能力没有稳定的公共探测 API，同一颗 SoC 在不同 ROM 上的可用性也差异极大，
 * 与其猜一个“支持/不支持”，不如用一定能读到的资源量做保守分级，失败风险最低。
 *
 * 人形检测按档位放开：低端机只跑运动检测，中高端机在运动触发后追加一次人形确认。
 * 模型资产缺失时 CaptureService 会回退为纯运动，这里的开关只表达“是否有算力”。
 */
object DetectionProfile {

    enum class Tier(val labelRes: Int) {
        LOW(R.string.detection_tier_low),
        MID(R.string.detection_tier_mid),
        HIGH(R.string.detection_tier_high),
    }

    data class Profile(
        val tier: Tier,
        val analysisWidth: Int,
        val analysisHeight: Int,
        /** 每 N 帧送检一次：低端机隔帧检测，避免检测本身反过来成为耗电主因 */
        val detectEveryNFrames: Int,
        val humanDetection: Boolean,
    )

    fun of(context: Context): Profile {
        val totalGb = totalMemoryGb(context)
        val cores = Runtime.getRuntime().availableProcessors()
        val tier = when {
            totalGb >= 6.0 && cores >= 8 -> Tier.HIGH
            totalGb >= 3.0 && cores >= 4 -> Tier.MID
            else -> Tier.LOW
        }
        return when (tier) {
            // 低端机把分析路降到 VGA：分块均值算法在 320x240 上已经足够判断“有没有动”，
            // 继续跑 640x480 只会让每帧采样点数翻倍，换不来更高的判定质量
            Tier.LOW -> Profile(tier, 320, 240, detectEveryNFrames = 2, humanDetection = false)
            Tier.MID -> Profile(tier, 640, 480, detectEveryNFrames = 1, humanDetection = true)
            Tier.HIGH -> Profile(tier, 640, 480, detectEveryNFrames = 1, humanDetection = true)
        }
    }

    /** 分析路的分辨率选择器；与录制路独立，降低检测分辨率不会影响成片画质 */
    fun analysisSelector(profile: Profile): ResolutionSelector =
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(profile.analysisWidth, profile.analysisHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

    private fun totalMemoryGb(context: Context): Double {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return 0.0
        val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        return info.totalMem / (1024.0 * 1024.0 * 1024.0)
    }
}
