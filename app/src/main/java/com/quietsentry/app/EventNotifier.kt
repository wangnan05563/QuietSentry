package com.quietsentry.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 事件通知（PRD FR-09）：三级通道、缩略图、点击直达事件。
 *
 * 为什么三级用独立通道而不是同一通道的不同优先级：Android 8 起提醒强度由通道决定，
 * 用户在系统设置里可以只静音“设备提示”而保留“紧急告警”，同通道无法做到这种粒度。
 */
class EventNotifier(private val context: Context) {

    /** 通知被系统关闭时只保留索引，由回看页承担“应用内消息中心”的角色（FR-09 异常降级） */
    fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun ensureChannels() {
        create(CHANNEL_URGENT, R.string.event_channel_urgent, NotificationManager.IMPORTANCE_HIGH)
        create(CHANNEL_NORMAL, R.string.event_channel_normal, NotificationManager.IMPORTANCE_DEFAULT)
        create(CHANNEL_INFO, R.string.event_channel_info, NotificationManager.IMPORTANCE_LOW)
    }

    fun showEvent(event: Event, level: AlertLevel) {
        if (!canNotify()) return
        val thumbnail = event.thumbnail?.takeIf { it.isFile }?.let {
            BitmapFactory.decodeFile(it.absolutePath)
        }
        val builder = NotificationCompat.Builder(context, channelOf(level))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(context.getString(titleOf(event.type)))
            .setContentText(
                context.getString(
                    R.string.event_body,
                    timeText(event.occurredAt),
                    event.confidence,
                    event.triggers
                )
            )
            .setAutoCancel(true)
            .setPriority(priorityOf(level))
            .setContentIntent(openEvent(event.id))
        if (thumbnail != null) {
            // 缩略图同时做大图标与大图：大图标保证折叠态可辨认，大图保证展开态看得清现场
            builder.setLargeIcon(thumbnail)
            builder.setStyle(
                NotificationCompat.BigPictureStyle().bigPicture(thumbnail)
            )
        }
        NotificationManagerCompat.from(context)
            .notify(EVENT_ID_BASE + event.id.toInt(), builder.build())
    }

    /** 网络恢复后的一条摘要，避免离线期间的每条事件都补弹一遍（PRD FR-09） */
    fun showOfflineSummary(count: Int) {
        if (!canNotify()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(context.getString(R.string.offline_summary_title))
            .setContentText(context.getString(R.string.offline_summary, count))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp())
        NotificationManagerCompat.from(context).notify(OFFLINE_SUMMARY_ID, builder.build())
    }

    private fun create(id: String, nameRes: Int, importance: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(id, context.getString(nameRes), importance)
        )
    }

    private fun channelOf(level: AlertLevel): String = when (level) {
        AlertLevel.URGENT -> CHANNEL_URGENT
        AlertLevel.NORMAL -> CHANNEL_NORMAL
        AlertLevel.INFO -> CHANNEL_INFO
    }

    private fun priorityOf(level: AlertLevel): Int = when (level) {
        AlertLevel.URGENT -> NotificationCompat.PRIORITY_HIGH
        AlertLevel.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
        AlertLevel.INFO -> NotificationCompat.PRIORITY_LOW
    }

    private fun titleOf(type: EventType): Int = when (type) {
        EventType.MOTION -> R.string.event_title_motion
        EventType.SOUND -> R.string.event_title_sound
    }

    private fun timeText(at: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

    /**
     * 通知直达事件：携带事件 id，事件详情页（W13）据此打开对应条目。
     * 用事件 id 做 requestCode，不同事件的通知不会互相覆盖 PendingIntent 的 extras。
     */
    private fun openEvent(eventId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        eventId.toInt(),
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_EVENT_ID, eventId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val EXTRA_EVENT_ID = "extra_event_id"

        private const val CHANNEL_URGENT = "events_urgent"
        private const val CHANNEL_NORMAL = "events_normal"
        private const val CHANNEL_INFO = "events_info"

        // 事件通知与常驻值守通知（1001）分段使用 id 区间，避免互相覆盖
        private const val EVENT_ID_BASE = 2000
        private const val OFFLINE_SUMMARY_ID = 2100
    }
}
