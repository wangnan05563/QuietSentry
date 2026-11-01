package com.quietsentry.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 观看端管理的设备清单（PRD FR-03）。
 *
 * 为什么不复用 PairingStore：那边存的是“我是谁”的身份与令牌，是相机端的授权记录；
 * 这边存的是“我看哪些设备”，还带用户起的名字、房间、排序和最近在线时间。
 * 卸载设备只需删本机记录，不应影响相机端的授权，因此两者必须是独立存储。
 */
class DeviceRegistry(context: Context) {

    data class Device(
        /** 相机端公钥指纹：重配、换 IP 后仍然是同一台设备 */
        val id: String,
        var name: String,
        var room: String,
        var host: String,
        var port: Int,
        /** 相机端自签证书指纹；为空表示旧设备（仍走 HTTP），兼容升级前的记录 */
        var tlsFingerprint: String = "",
        var token: String,
        var order: Int,
        var lastSeenAt: Long,
        var lastEventAt: Long,
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Device> = read().sortedBy { it.order }.onEach { device ->
        // 进程重启后信任锚需要重新注册：TlsTrust 只活在内存里，不落盘
        if (device.tlsFingerprint.isNotEmpty()) {
            TlsTrust.register(device.host, device.port, device.tlsFingerprint)
        }
    }

    @Synchronized
    fun count(): Int = read().size

    /**
     * 返回 false 表示已达 5 台上限。
     * 存储层不静默挤掉已有设备：移除必须是用户明确选择，否则用户会发现设备“自己消失了”。
     */
    @Synchronized
    fun add(device: Device): Boolean {
        val list = read()
        val existing = list.firstOrNull { it.id == device.id }
        if (existing != null) {
            // 同一台设备重新配对：只刷新连接信息，保留用户起的名字、房间与排序
            existing.host = device.host
            existing.port = device.port
            existing.token = device.token
            existing.lastSeenAt = device.lastSeenAt
            write(list)
            return true
        }
        if (list.size >= MAX_DEVICES) return false
        val nextOrder = (list.maxOfOrNull { it.order } ?: -1) + 1
        list += device.copy(order = nextOrder)
        write(list)
        return true
    }

    @Synchronized
    fun remove(id: String) {
        write(read().filterNot { it.id == id })
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val list = read()
        list.firstOrNull { it.id == id }?.let {
            it.name = trimmed
            write(list)
        }
    }

    @Synchronized
    fun setRoom(id: String, room: String) {
        val list = read()
        list.firstOrNull { it.id == id }?.let {
            it.room = room.trim()
            write(list)
        }
    }

    /**
     * 在线状态每 5 秒轮询一次，逐次落盘会产生大量小写入；
     * 只有变化超过 30 秒才持久化，既保证离线时长大致准确，又不扰动闪存。
     */
    @Synchronized
    fun markSeen(id: String, seenAt: Long, lastEventAt: Long) {
        val list = read()
        val device = list.firstOrNull { it.id == id } ?: return
        val changed = seenAt - device.lastSeenAt >= SEEN_PERSIST_INTERVAL_MS
        device.lastSeenAt = seenAt
        device.lastEventAt = lastEventAt
        if (changed) write(list)
    }

    /** 上移/下移一位；越界时不做任何事，调用方不需要自己判断边界 */
    @Synchronized
    fun move(id: String, delta: Int) {
        val list = read().sortedBy { it.order }
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = index + delta
        if (target !in list.indices) return
        list[index].order = target
        list[target].order = index
        write(list)
    }

    private fun read(): MutableList<Device> {
        val array = runCatching { JSONArray(prefs.getString(KEY_DEVICES, "[]")) }
            .getOrDefault(JSONArray())
        val result = ArrayList<Device>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result += Device(
                id = obj.optString("id"),
                name = obj.optString("name"),
                room = obj.optString("room"),
                host = obj.optString("host"),
                port = obj.optInt("port"),
                tlsFingerprint = obj.optString("tlsFingerprint"),
                token = obj.optString("token"),
                order = obj.optInt("order", i),
                lastSeenAt = obj.optLong("lastSeenAt"),
                lastEventAt = obj.optLong("lastEventAt"),
            )
        }
        return result
    }

    private fun write(devices: List<Device>) {
        val array = JSONArray()
        for (device in devices) {
            array.put(JSONObject().apply {
                put("id", device.id)
                put("name", device.name)
                put("room", device.room)
                put("host", device.host)
                put("port", device.port)
                put("tlsFingerprint", device.tlsFingerprint)
                put("token", device.token)
                put("order", device.order)
                put("lastSeenAt", device.lastSeenAt)
                put("lastEventAt", device.lastEventAt)
            })
        }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    companion object {
        /** PRD FR-03 异常：单台观看端最多管理 5 台设备 */
        const val MAX_DEVICES = 5

        private const val PREFS = "device_registry"
        private const val KEY_DEVICES = "devices"
        private const val SEEN_PERSIST_INTERVAL_MS = 30_000L
    }
}
