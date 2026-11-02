package com.quietsentry.app

/**
 * 最近一次人形确认的框（T04053）。
 *
 * 摄像头端的推理只发生在运动触发帧（PRD 功耗口径），框是稀疏产生的；
 * 观看端拉流时无法自己拿到推理结果，由 MJPEG 帧头把“最近框”捎带给观看端。
 * 这里是两端共用的发布点：CaptureService 发布，DeviceServer 取走写进帧头。
 */
object PersonBoxes {

    data class Result(val atMs: Long, val boxes: List<FloatArray>)

    @Volatile
    private var latest: Result? = null

    /** 推理线程发布；boxes 为空时也覆盖，用于把上一批框作废 */
    fun publish(boxes: List<FloatArray>) {
        latest = Result(System.currentTimeMillis(), boxes)
    }

    /** 只返回新鲜框：人形早已离开的旧框继续下发会让观看端画出一个不存在的目标 */
    fun recent(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): Result? =
        latest?.takeIf { nowMs - it.atMs <= maxAgeMs && it.boxes.isNotEmpty() }
}
