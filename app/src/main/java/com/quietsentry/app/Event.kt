package com.quietsentry.app

import java.io.File

/**
 * 事件类型（PRD §7 Event）：类型、时间、置信度、缩略图、关联片段、推送状态、处理状态。
 * 当前只落地 P0 的运动与声音；人形/遮挡/系统类型在人形检测（P1）与健康模块接入时再扩展。
 */
enum class EventType(val raw: String) {
    MOTION("motion"),
    SOUND("sound");

    companion object {
        fun fromRaw(raw: String): EventType = entries.firstOrNull { it.raw == raw } ?: MOTION
    }
}

/**
 * 事件状态（PRD FR-07 结果与状态：事件从“检测中”进入“已录制”）。
 * 必须与“已读/已锁定”分开：前者是录制结果，后者是用户操作，混在一起会让回看页无法区分
 * “索引存在但片段没录成”和“录成了但我还没看”。
 */
enum class EventStatus(val raw: String) {
    DETECTING("detecting"),
    RECORDED("recorded")
}

/**
 * 写入索引的结果。merged=true 表示落进了 60 秒窗口内的既有事件：
 * 调用方据此不再重复提醒（PRD FR-09 同设备同类型 60 秒内只推一次摘要）。
 */
data class RecordOutcome(val id: Long, val merged: Boolean)

/**
 * 事件条目。thumbnail 与 clip 用 File 而非 String：回看页要判断文件是否还在
 * （StorageKeeper 会按配额清理缩略图），保留路径也就保留了“文件不可用”的提示依据。
 */
data class Event(
    val id: Long,
    val type: EventType,
    val occurredAt: Long,
    val confidence: Int,
    val thumbnail: File?,
    val clip: File?,
    val status: EventStatus,
    val read: Boolean,
    val locked: Boolean,
    val synced: Boolean,
    /** 60 秒窗口内被合并的触发次数：1 表示单次触发，越大说明事件越持续 */
    val triggers: Int,
)
