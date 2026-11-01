package com.quietsentry.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 事件时间轴（PRD FR-11）：按日分组、小时分组提示、筛选、标记重要、删除与最近删除。
 *
 * 为什么直接读 SQLite 而不缓存到内存列表：时间轴最长 200 条，查询本身是索引扫描；
 * 但用户可能从通知直达、从最近删除返回、或在播放器里改了锁定状态，
 * 每次回到界面都重新查询才能保证状态一致，避免出现“刚取消锁定列表还显示锁定”。
 */
class EventTimelineActivity : AppCompatActivity() {

    private val store by lazy { EventStore(this) }

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var deletedButton: Button
    private lateinit var adapter: TimelineAdapter

    private var typeFilter: EventType? = null
    private var unreadOnly = false
    private var lockedOnly = false
    private var onlyDeleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 时间轴会直接暴露家里发生的事，先过应用锁；通过后 recreate 再加载列表
        if (!PrivacyGuard.allow(this)) {
            PrivacyGuard.prompt(this) { ok -> if (ok) recreate() else finish() }
            return
        }
        setContentView(R.layout.activity_event_timeline)

        listView = findViewById(R.id.event_list)
        emptyText = findViewById(R.id.empty_text)
        deletedButton = findViewById(R.id.deleted_button)
        adapter = TimelineAdapter()
        listView.adapter = adapter

        findViewById<Button>(R.id.back_button).setOnClickListener { finish() }

        deletedButton.setOnClickListener {
            onlyDeleted = !onlyDeleted
            deletedButton.text = getString(
                if (onlyDeleted) R.string.timeline_back_to_timeline else R.string.timeline_deleted
            )
            reload()
        }

        findViewById<Spinner>(R.id.type_spinner).apply {
            adapter = ArrayAdapter(
                this@EventTimelineActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.timeline_filter_all_types),
                    getString(R.string.timeline_filter_motion),
                    getString(R.string.timeline_filter_sound)
                )
            )
        }.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                typeFilter = when (position) {
                    1 -> EventType.MOTION
                    2 -> EventType.SOUND
                    else -> null
                }
                reload()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<CheckBox>(R.id.unread_check).setOnCheckedChangeListener { _, checked ->
            unreadOnly = checked
            reload()
        }
        findViewById<CheckBox>(R.id.locked_check).setOnCheckedChangeListener { _, checked ->
            lockedOnly = checked
            reload()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.getItem(position)
            if (row is TimelineAdapter.Row.Item) openEvent(row.event)
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val row = adapter.getItem(position)
            if (row is TimelineAdapter.Row.Item) {
                showActions(row.event)
                true
            } else {
                false
            }
        }

        // 保留期清理放在后台线程：可能要删几百张缩略图与分片，放主线程会卡首帧
        Thread {
            store.purgeDeletedBefore(System.currentTimeMillis() - RETAIN_MS)
        }.start()
        reload()
    }

    override fun onResume() {
        super.onResume()
        // PIN 未通过时 onCreate 已经提前返回，列表与 adapter 都没建好；
        // 这里无条件 reload 会抛 UninitializedPropertyAccessException，应用锁一开回看页就闪退
        if (::adapter.isInitialized) {
            // 播放器里可能标记了锁定/已读，回来必须重新拉一次
            reload()
        }
    }

    private fun reload() {
        val events = store.timeline(typeFilter, unreadOnly, lockedOnly, onlyDeleted)
        adapter.submit(events)
        emptyText.setText(
            if (onlyDeleted) R.string.timeline_deleted_empty else R.string.timeline_empty
        )
        emptyText.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEvent(event: Event) {
        store.markRead(event.id)
        startActivity(
            Intent(this, EventPlayerActivity::class.java)
                .putExtra(EventPlayerActivity.EXTRA_EVENT_ID, event.id)
        )
    }

    private fun showActions(event: Event) {
        if (onlyDeleted) {
            AlertDialog.Builder(this)
                .setTitle(eventTitle(event))
                .setItems(
                    arrayOf(
                        getString(R.string.timeline_action_restore),
                        getString(R.string.timeline_action_purge)
                    )
                ) { _, which ->
                    if (which == 0) {
                        store.restoreDeleted(event.id)
                        Toast.makeText(this, R.string.timeline_restored_toast, Toast.LENGTH_SHORT)
                            .show()
                        reload()
                    } else {
                        confirmPurge(event)
                    }
                }
                .show()
            return
        }
        val lockLabel = getString(
            if (event.locked) R.string.timeline_action_unlock else R.string.timeline_action_lock
        )
        AlertDialog.Builder(this)
            .setTitle(eventTitle(event))
            .setItems(
                arrayOf(lockLabel, getString(R.string.timeline_action_delete))
            ) { _, which ->
                if (which == 0) {
                    store.setLocked(event.id, !event.locked)
                    reload()
                } else {
                    confirmDelete(event)
                }
            }
            .show()
    }

    private fun confirmDelete(event: Event) {
        AlertDialog.Builder(this)
            .setTitle(R.string.timeline_delete_title)
            .setMessage(R.string.timeline_delete_message)
            .setPositiveButton(R.string.timeline_action_delete) { _, _ ->
                store.markDeleted(event.id)
                Toast.makeText(this, R.string.timeline_deleted_toast, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmPurge(event: Event) {
        AlertDialog.Builder(this)
            .setTitle(R.string.timeline_purge_title)
            .setMessage(R.string.timeline_purge_message)
            .setPositiveButton(R.string.timeline_action_purge) { _, _ ->
                store.deleteForever(event.id)
                Toast.makeText(this, R.string.timeline_purged_toast, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun eventTitle(event: Event): String = getString(event.type.labelRes)

    /**
     * 时间轴行模型。分组放在适配器里而不是查询里：
     * 日期边界取决于设备时区，SQLite 只能按毫秒排序，分组必须用同一套时间格式才不会和界面显示错位。
     * 小时标题只为让“同一小时多条”一眼可见，不折叠内容——折叠会让用户误以为事件丢失。
     */
    private class TimelineAdapter : BaseAdapter() {

        sealed class Row {
            data class DayHeader(val label: String) : Row()
            data class HourHeader(val label: String, val count: Int) : Row()
            data class Item(val event: Event) : Row()
        }

        private val rows = ArrayList<Row>()
        private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val hourFormat = SimpleDateFormat("HH:00", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun submit(events: List<Event>) {
            rows.clear()
            val counts = HashMap<String, Int>()
            for (event in events) {
                val key = hourKey(event)
                counts[key] = (counts[key] ?: 0) + 1
            }
            var lastDay: String? = null
            var lastHour: String? = null
            for (event in events) {
                val day = dayFormat.format(Date(event.occurredAt))
                val hour = hourFormat.format(Date(event.occurredAt))
                if (day != lastDay) {
                    rows += Row.DayHeader(day)
                    lastDay = day
                    lastHour = null
                }
                if (hour != lastHour) {
                    rows += Row.HourHeader(hour, counts[hourKey(event)] ?: 1)
                    lastHour = hour
                }
                rows += Row.Item(event)
            }
            notifyDataSetChanged()
        }

        private fun hourKey(event: Event): String =
            dayFormat.format(Date(event.occurredAt)) + "#" + hourFormat.format(Date(event.occurredAt))

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Row = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 3

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.DayHeader -> 0
            is Row.HourHeader -> 1
            is Row.Item -> 2
        }

        override fun isEnabled(position: Int): Boolean = rows[position] is Row.Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is Row.DayHeader -> {
                    (convertView as? TextView ?: TextView(parent.context).apply {
                        setTextColor(0xFFAAAAAA.toInt())
                        textSize = 12f
                        setPadding(12.dp(parent), 12.dp(parent), 12.dp(parent), 4.dp(parent))
                    }).also { it.text = row.label }
                }

                is Row.HourHeader -> {
                    val label = if (row.count > 1) {
                        parent.context.getString(R.string.timeline_hour_count, row.label, row.count)
                    } else {
                        row.label
                    }
                    (convertView as? TextView ?: TextView(parent.context).apply {
                        setTextColor(0xFF777777.toInt())
                        textSize = 11f
                        setPadding(12.dp(parent), 6.dp(parent), 12.dp(parent), 2.dp(parent))
                    }).also { it.text = label }
                }

                is Row.Item -> {
                    val view = convertView ?: android.view.LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_event, parent, false)
                    val holder = view.tag as? EventHolder
                        ?: EventHolder(view).also { view.tag = it }
                    holder.bind(row.event, timeFormat.format(Date(row.event.occurredAt)))
                    view
                }
            }

        private fun Int.dp(parent: ViewGroup): Int =
            (this * parent.resources.displayMetrics.density).toInt()

        private class EventHolder(root: View) {
            private val context = root.context
            private val thumb: ImageView = root.findViewById(R.id.thumb_image)
            private val title: TextView = root.findViewById(R.id.title_text)
            private val meta: TextView = root.findViewById(R.id.meta_text)
            private val flags: TextView = root.findViewById(R.id.flags_text)

            fun bind(event: Event, timeText: String) {
                val bitmap = event.thumbnail?.takeIf { it.isFile }?.let {
                    BitmapFactory.decodeFile(it.absolutePath)
                }
                thumb.setImageBitmap(bitmap)
                title.text = context.getString(event.type.labelRes)
                meta.text = context.getString(
                    R.string.timeline_meta,
                    timeText,
                    event.confidence,
                    event.triggers
                )
                val labels = buildList {
                    if (!event.read) add(context.getString(R.string.timeline_unread))
                    if (event.locked) add(context.getString(R.string.timeline_locked))
                    if (event.clipUri == null && event.clip?.isFile != true) {
                        add(context.getString(R.string.timeline_file_missing))
                    }
                }
                flags.text = labels.joinToString(" · ")
                flags.visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    companion object {
        /** 与 EventStore 的清理口径保持一致（PRD O6：最近删除保留 7 天）。 */
        private val RETAIN_MS = 7L * 24 * 60 * 60 * 1000
    }
}
