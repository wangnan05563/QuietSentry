package com.quietsentry.app

import java.io.File

/**
 * 事件类型（PRD §7 Event）：类型、时间、置信度、缩略图、关联片段、推送状态、处理状态。
 * PERSON 是 MOTION 的增强结果：只有端侧模型确认画面里有人时才会落成该类型，
 * 模型不可用时事件仍然以 MOTION 入库，回看与告警链路不受影响。
 */
enum class EventType(val raw: String) {
    MOTION("motion"),
    SOUND("sound"),
    PERSON("person");

    companion object {
        fun fromRaw(raw: String): EventType = entries.firstOrNull { it.raw == raw } ?: MOTION
    }
}

/** 时间轴与播放器共用的类型标签：两处各写一套 when 迟早会漏掉新增类型 */
val EventType.labelRes: Int
    get() = when (this) {
        EventType.MOTION -> R.string.timeline_type_motion
        EventType.SOUND -> R.string.timeline_type_sound
        EventType.PERSON -> R.string.timeline_type_person
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
    /**
     * 片段导出到系统相册后的 content:// 定位。停止值守时私有原件会被删除，
     * 只留 File 路径会让回看全部失效，因此导出成功后必须回写可再次打开的 Uri。
     */
    val clipUri: String? = null,
    /** 软删除时间（毫秒）。非空表示在“最近删除”里，保留期内可恢复，超期才物理清除 */
    val deletedAt: Long? = null,
    /**
     * 事件时刻相对所在片段起点的偏移。播放器据此回退到“事件前 5 秒”（PRD FR-07），
     * 直接把整段从头播会让用户先看十几秒无关画面。
     */
    val clipOffsetMs: Long = 0L,
    /**
     * 事件位于片段开头 5 秒内时，所在片段自身无法提供完整前置画面，
     * 需要引用上一片尾部补足。该文件是多个事件共享的素材，不随单个事件删除。
     */
    val prevClip: File? = null,
    val prevClipUri: String? = null,
)
