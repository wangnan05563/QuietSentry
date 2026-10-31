package com.quietsentry.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 事件的本地索引（PRD FR-07 / FR-11 / FR-13 断网补传）。
 *
 * 为什么用 SQLite 而不是直接扫缩略图目录：事件需要按类型、时间、已读/锁定、同步状态组合筛选，
 * 目录扫描在几千条后会明显变慢；而索引与文件分离，也允许文件先被配额清理、索引仍保留可追溯记录。
 */
class EventStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {

    /**
     * 写入或合并一次触发。同类型 60 秒内已有事件时只累加触发次数并抬高置信度，
     * 不新建条目——否则一次路人经过会在回看时间轴上刷出十几条几乎相同的事件。
     *
     * 检测器内部也做了 60 秒节流，这里是索引层的兜底：不同检测器实现、进程重启、
     * 或未来新增的人形检测只要调用本方法，都能得到一致的合并行为。
     */
    @Synchronized
    fun record(
        type: EventType,
        confidence: Int,
        thumbnail: File?,
        occurredAt: Long = System.currentTimeMillis(),
    ): RecordOutcome {
        val db = writableDatabase
        val merged = findMergeCandidate(db, type, occurredAt)
        if (merged != null) {
            // 缩略图保留首次触发的那一帧：它才是“事件起点”的画面，后续合并帧不代表事件开始
            val values = ContentValues().apply {
                put(COL_CONFIDENCE, maxOf(confidence, merged.confidence))
                put(COL_TRIGGERS, merged.triggers + 1)
                put(COL_UPDATED_AT, System.currentTimeMillis())
            }
            db.update(TABLE, values, "$COL_ID=?", arrayOf(merged.id.toString()))
            return RecordOutcome(merged.id, merged = true)
        }

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_TYPE, type.raw)
            put(COL_OCCURRED_AT, occurredAt)
            put(COL_CONFIDENCE, confidence)
            put(COL_THUMBNAIL, thumbnail?.absolutePath)
            put(COL_STATUS, EventStatus.DETECTING.raw)
            put(COL_READ, 0)
            put(COL_LOCKED, 0)
            put(COL_SYNCED, 0)
            put(COL_TRIGGERS, 1)
            put(COL_CREATED_AT, now)
            put(COL_UPDATED_AT, now)
        }
        return RecordOutcome(db.insert(TABLE, null, values), merged = false)
    }

    /** 单条读取：通知需要完整的事件对象（类型、置信度、缩略图、触发次数）。 */
    @Synchronized
    fun get(id: Long): Event? {
        writableDatabase.query(
            TABLE,
            COLUMNS,
            "$COL_ID=?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toEvent() else null
        }
    }

    /**
     * 分片正常收敛后把“检测中”事件挂到片段上并标记为“已录制”。
     * 只有文件真实可读才流转：分片录制失败时保持 DETECTING，回看页据此提示“文件不可用”（FR-11 异常）。
     */
    @Synchronized
    fun markRecorded(ids: Collection<Long>, clip: File) {
        if (ids.isEmpty() || !clip.isFile || clip.length() == 0L) return
        val values = ContentValues().apply {
            put(COL_CLIP, clip.absolutePath)
            put(COL_STATUS, EventStatus.RECORDED.raw)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        val clause = ids.joinToString(",")
        // 只流转仍处于“检测中”的行：同一事件可能因 60 秒合并被多次入队，
        // 后来的分片不该把事件起点已经挂好的片段改成更晚的片段
        writableDatabase.update(
            TABLE,
            values,
            "$COL_ID IN ($clause) AND $COL_STATUS=?",
            arrayOf(EventStatus.DETECTING.raw)
        )
    }

    /** 待补传事件（FR-13：断网时本地排队，恢复后补传索引）；真实上传链路属 M4。 */
    @Synchronized
    fun pendingSync(limit: Int = 50): List<Event> =
        query("$COL_SYNCED=0", null, "$COL_OCCURRED_AT ASC", limit)

    /** 补传成功后由上传链路回写；未接入网络前不会被调用，因此事件会一直保持待补传。 */
    @Synchronized
    fun markSynced(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val values = ContentValues().apply {
            put(COL_SYNCED, 1)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        val clause = ids.joinToString(",")
        writableDatabase.update(TABLE, values, "$COL_ID IN ($clause)", null)
    }

    /** 事件时间轴数据源（FR-11），最新的在前；已进“最近删除”的不参与主时间轴。 */
    @Synchronized
    fun recent(limit: Int = 100): List<Event> =
        query("$COL_DELETED_AT IS NULL", null, "$COL_OCCURRED_AT DESC", limit)

    /**
     * 时间轴数据源（FR-11）：按日分组由界面层完成，这里只负责筛选与排序。
     * onlyDeleted=true 用于“最近删除”页；软删除的事件在保留期内不出现在主时间轴。
     */
    @Synchronized
    fun timeline(
        type: EventType?,
        unreadOnly: Boolean,
        lockedOnly: Boolean,
        onlyDeleted: Boolean = false,
        limit: Int = 200,
    ): List<Event> {
        val cond = StringBuilder("$COL_DELETED_AT IS " + if (onlyDeleted) "NOT NULL" else "NULL")
        val args = ArrayList<String>()
        if (type != null) {
            cond.append(" AND $COL_TYPE=?")
            args += type.raw
        }
        if (unreadOnly) cond.append(" AND $COL_READ=0")
        if (lockedOnly) cond.append(" AND $COL_LOCKED=1")
        return query(cond.toString(), args.toTypedArray(), "$COL_OCCURRED_AT DESC", limit)
    }

    /** 软删除：先移到“最近删除”，给用户 7 天反悔窗口（PRD FR-11 删除后进入最近删除） */
    @Synchronized
    fun markDeleted(id: Long) {
        val values = ContentValues().apply {
            put(COL_DELETED_AT, System.currentTimeMillis())
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, values, "$COL_ID=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun restoreDeleted(id: Long) {
        val values = ContentValues().apply {
            putNull(COL_DELETED_AT)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, values, "$COL_ID=?", arrayOf(id.toString()))
    }

    /**
     * “最近删除”里的彻底删除：索引与私有文件一起清掉。
     * clip_uri 指向相册副本，用户可能已在相册里单独保留，这里不能跟着删。
     */
    @Synchronized
    fun deleteForever(id: Long) {
        val event = get(id) ?: return
        event.thumbnail?.delete()
        event.clip?.delete()
        writableDatabase.delete(TABLE, "$COL_ID=?", arrayOf(id.toString()))
    }

    /**
     * 清除超过保留期的“最近删除”。锁定的条目即使被删也保留索引：
     * 用户标记重要的事件不应因为一次误删就彻底消失（PRD FR-11 标记重要）。
     *
     * 先删落盘文件再删索引：只删索引会让缩略图和私有分片永远留在应用目录里没人回收。
     * clip_uri 指向相册副本，不属于应用私有空间，不能在这里动。
     */
    @Synchronized
    fun purgeDeletedBefore(cutoffMs: Long): Int {
        query(
            "$COL_DELETED_AT IS NOT NULL AND $COL_DELETED_AT<? AND $COL_LOCKED=0",
            arrayOf(cutoffMs.toString()),
            "$COL_OCCURRED_AT ASC",
            Int.MAX_VALUE
        ).forEach { event ->
            event.thumbnail?.delete()
            event.clip?.delete()
        }
        return writableDatabase.delete(
            TABLE,
            "$COL_DELETED_AT IS NOT NULL AND $COL_DELETED_AT<? AND $COL_LOCKED=0",
            arrayOf(cutoffMs.toString())
        )
    }

    /**
     * 片段导出到相册后回写定位（PRD FR-13 / T03769）：私有原件会被删除，
     * 只保留 File 路径会让回看永远停在“文件不可用”。
     */
    @Synchronized
    fun markExported(clipPath: String, uri: String) {
        if (clipPath.isEmpty() || uri.isEmpty()) return
        val values = ContentValues().apply {
            put(COL_CLIP_URI, uri)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, values, "$COL_CLIP=?", arrayOf(clipPath))
    }

    /** 通知点击直达时把该条事件标记已读；时间轴上点击同样调用。 */
    @Synchronized
    fun markRead(id: Long) {
        val values = ContentValues().apply {
            put(COL_READ, 1)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, values, "$COL_ID=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun setLocked(id: Long, locked: Boolean) {
        val values = ContentValues().apply {
            put(COL_LOCKED, if (locked) 1 else 0)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, values, "$COL_ID=?", arrayOf(id.toString()))
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
        db.execSQL(CREATE_TYPE_INDEX)
        db.execSQL(CREATE_SYNC_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1→v2 只加列：事件记录已经进入用户可见的回看页，升级时整表重建会丢用户的历史索引，
        // 这与“索引可再生”的早期假设不再成立
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_CLIP_URI TEXT")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_DELETED_AT INTEGER")
        }
    }

    private data class MergeCandidate(val id: Long, val confidence: Int, val triggers: Int)

    private fun findMergeCandidate(
        db: SQLiteDatabase,
        type: EventType,
        occurredAt: Long,
    ): MergeCandidate? {
        val columns = arrayOf(COL_ID, COL_CONFIDENCE, COL_TRIGGERS)
        val windowStart = occurredAt - MERGE_WINDOW_MS
        db.query(
            TABLE,
            columns,
            "$COL_TYPE=? AND $COL_OCCURRED_AT>=?",
            arrayOf(type.raw, windowStart.toString()),
            null,
            null,
            "$COL_OCCURRED_AT DESC",
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return MergeCandidate(
                id = cursor.getLong(0),
                confidence = cursor.getInt(1),
                triggers = cursor.getInt(2)
            )
        }
    }

    private fun query(
        selection: String?,
        selectionArgs: Array<String>?,
        orderBy: String,
        limit: Int,
    ): List<Event> {
        writableDatabase.query(
            TABLE,
            COLUMNS,
            selection,
            selectionArgs,
            null,
            null,
            orderBy,
            limit.toString()
        ).use { cursor ->
            val result = ArrayList<Event>(cursor.count)
            while (cursor.moveToNext()) {
                result += cursor.toEvent()
            }
            return result
        }
    }

    private fun Cursor.toEvent(): Event = Event(
        id = getLong(getColumnIndexOrThrow(COL_ID)),
        type = EventType.fromRaw(getString(getColumnIndexOrThrow(COL_TYPE))),
        occurredAt = getLong(getColumnIndexOrThrow(COL_OCCURRED_AT)),
        confidence = getInt(getColumnIndexOrThrow(COL_CONFIDENCE)),
        thumbnail = getString(getColumnIndexOrThrow(COL_THUMBNAIL))?.let(::File),
        clip = getString(getColumnIndexOrThrow(COL_CLIP))?.let(::File),
        status = EventStatus.entries.firstOrNull {
            it.raw == getString(getColumnIndexOrThrow(COL_STATUS))
        } ?: EventStatus.DETECTING,
        read = getInt(getColumnIndexOrThrow(COL_READ)) != 0,
        locked = getInt(getColumnIndexOrThrow(COL_LOCKED)) != 0,
        synced = getInt(getColumnIndexOrThrow(COL_SYNCED)) != 0,
        triggers = getInt(getColumnIndexOrThrow(COL_TRIGGERS)),
        clipUri = getString(getColumnIndexOrThrow(COL_CLIP_URI)),
        deletedAt = getLong(getColumnIndexOrThrow(COL_DELETED_AT)).takeIf { it > 0L },
    )

    companion object {
        private const val DB_NAME = "events.db"
        private const val DB_VERSION = 2
        private const val TABLE = "events"

        private const val COL_ID = "_id"
        private const val COL_TYPE = "type"
        private const val COL_OCCURRED_AT = "occurred_at"
        private const val COL_CONFIDENCE = "confidence"
        private const val COL_THUMBNAIL = "thumbnail"
        private const val COL_CLIP = "clip"
        private const val COL_STATUS = "status"
        private const val COL_READ = "read"
        private const val COL_LOCKED = "locked"
        private const val COL_SYNCED = "synced"
        private const val COL_TRIGGERS = "triggers"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"
        private const val COL_CLIP_URI = "clip_uri"
        private const val COL_DELETED_AT = "deleted_at"

        private val COLUMNS = arrayOf(
            COL_ID, COL_TYPE, COL_OCCURRED_AT, COL_CONFIDENCE, COL_THUMBNAIL,
            COL_CLIP, COL_STATUS, COL_READ, COL_LOCKED, COL_SYNCED, COL_TRIGGERS,
            COL_CLIP_URI, COL_DELETED_AT
        )

        /** 同源合并窗口（PRD FR-07：60 秒） */
        private val MERGE_WINDOW_MS = TimeUnit.SECONDS.toMillis(60)

        private const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TYPE TEXT NOT NULL,
                $COL_OCCURRED_AT INTEGER NOT NULL,
                $COL_CONFIDENCE INTEGER NOT NULL,
                $COL_THUMBNAIL TEXT,
                $COL_CLIP TEXT,
                $COL_STATUS TEXT NOT NULL,
                $COL_READ INTEGER NOT NULL DEFAULT 0,
                $COL_LOCKED INTEGER NOT NULL DEFAULT 0,
                $COL_SYNCED INTEGER NOT NULL DEFAULT 0,
                $COL_TRIGGERS INTEGER NOT NULL DEFAULT 1,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL,
                $COL_CLIP_URI TEXT,
                $COL_DELETED_AT INTEGER
            )
        """

        // 合并查询与时间轴查询都按「类型 + 发生时间」走，没有索引时每次触发都要全表扫
        private const val CREATE_TYPE_INDEX =
            "CREATE INDEX idx_events_type_occurred ON $TABLE($COL_TYPE, $COL_OCCURRED_AT)"

        private const val CREATE_SYNC_INDEX =
            "CREATE INDEX idx_events_synced ON $TABLE($COL_SYNCED, $COL_OCCURRED_AT)"
    }
}
