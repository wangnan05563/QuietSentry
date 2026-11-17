package com.quietsentry.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备卡片状态优先级（PRD FR-03）。这些分支决定“离线 / 低电 / 过热 / 存储 / 值守 / 待机”
 * 的展示顺序，任何一条判反都会让用户看到错误的设备健康状态，因此逐条钉住边界。
 */
class DeviceStateTest {

    private val now = 1_700_000_000_000L

    private fun status(
        mode: String = "idle",
        battery: Int = 80,
        charging: Boolean = false,
        thermal: Int = 0,
        storageFreeMb: Long = 8_000,
        watching: Int = 0,
        maxViewers: Int = 5,
        lastEventAt: Long = 0L,
        heartbeatAt: Long = 0L,
        serverTime: Long = 0L,
        throttled: Boolean = false,
    ) = DeviceHttp.Status(
        mode = mode,
        battery = battery,
        charging = charging,
        thermal = thermal,
        storageFreeMb = storageFreeMb,
        watching = watching,
        maxViewers = maxViewers,
        lastEventAt = lastEventAt,
        heartbeatAt = heartbeatAt,
        serverTime = serverTime,
        throttled = throttled,
    )

    @Test
    fun `离线设备给出安全默认值`() {
        val view = DeviceState.evaluate(null, lastEventAt = 0L, seenAt = now, now = now)

        assertEquals(DeviceState.Level.OFFLINE, view.level)
        assertEquals(-1, view.battery)
        assertFalse(view.charging)
        assertEquals(0, view.watching)
        assertEquals(5, view.maxViewers)
        assertFalse(view.alerting)
        assertFalse(view.heartbeatStale)
        assertFalse(view.throttled)
    }

    @Test
    fun `离线但刚有事件时仍然置告警标识`() {
        // 告警是叠加在运行状态上的标识，不能因为拉不到状态就丢掉“刚刚发生过什么”
        val view = DeviceState.evaluate(null, lastEventAt = now - 60_000L, seenAt = now, now = now)

        assertEquals(DeviceState.Level.OFFLINE, view.level)
        assertTrue(view.alerting)
    }

    @Test
    fun `五分钟窗口内在线设备顶到告警态`() {
        val view = DeviceState.evaluate(status(), lastEventAt = now - 60_000L, seenAt = now, now = now)

        assertEquals(DeviceState.Level.ALERT, view.level)
    }

    @Test
    fun `告警窗口按左闭右开判断`() {
        // 299,999ms 还在窗口内，整 5 分钟已经出窗
        val inside = DeviceState.evaluate(status(), lastEventAt = now - 299_999L, seenAt = now, now = now)
        val boundary = DeviceState.evaluate(status(), lastEventAt = now - 300_000L, seenAt = now, now = now)

        assertTrue(inside.alerting)
        assertEquals(DeviceState.Level.ALERT, inside.level)
        assertFalse(boundary.alerting)
        assertEquals(DeviceState.Level.IDLE, boundary.level)
    }

    @Test
    fun `未来时间戳不算告警`() {
        // 两端时钟不同步时 lastEventAt 可能跑到未来，负年龄不能当成“刚刚发生”
        val view = DeviceState.evaluate(status(), lastEventAt = now + 10_000L, seenAt = now, now = now)

        assertFalse(view.alerting)
        assertEquals(DeviceState.Level.IDLE, view.level)
    }

    @Test
    fun `未充电且电量低于百分之二十才算低电`() {
        val low = DeviceState.evaluate(status(battery = 15), lastEventAt = 0L, seenAt = now, now = now)
        val charging = DeviceState.evaluate(
            status(battery = 15, charging = true),
            lastEventAt = 0L,
            seenAt = now,
            now = now,
        )

        assertEquals(DeviceState.Level.LOW_BATTERY, low.level)
        assertEquals(DeviceState.Level.IDLE, charging.level)
    }

    @Test
    fun `电量未知不判低电且百分之二十仍是低电边界`() {
        // -1 表示服务端没读到电量，把它当低电会让所有设备都变琥珀色
        val unknown = DeviceState.evaluate(status(battery = -1), lastEventAt = 0L, seenAt = now, now = now)
        // 判定是 in 1..20（含等号），所以 20 仍算低电，21 才回到待机
        val boundary = DeviceState.evaluate(status(battery = 20), lastEventAt = 0L, seenAt = now, now = now)
        val above = DeviceState.evaluate(status(battery = 21), lastEventAt = 0L, seenAt = now, now = now)

        assertEquals(DeviceState.Level.IDLE, unknown.level)
        assertEquals(DeviceState.Level.LOW_BATTERY, boundary.level)
        assertEquals(DeviceState.Level.IDLE, above.level)
    }

    @Test
    fun `热等级三及以上判过热`() {
        val hot = DeviceState.evaluate(status(thermal = 3), lastEventAt = 0L, seenAt = now, now = now)
        val warm = DeviceState.evaluate(status(thermal = 2), lastEventAt = 0L, seenAt = now, now = now)

        assertEquals(DeviceState.Level.OVERHEAT, hot.level)
        assertEquals(DeviceState.Level.IDLE, warm.level)
    }

    @Test
    fun `剩余存储低于五百兆才判存储告警`() {
        val tight = DeviceState.evaluate(status(storageFreeMb = 499), lastEventAt = 0L, seenAt = now, now = now)
        val exact = DeviceState.evaluate(status(storageFreeMb = 500), lastEventAt = 0L, seenAt = now, now = now)
        val unknown = DeviceState.evaluate(status(storageFreeMb = 0), lastEventAt = 0L, seenAt = now, now = now)

        assertEquals(DeviceState.Level.STORAGE, tight.level)
        assertEquals(DeviceState.Level.IDLE, exact.level)
        assertEquals(DeviceState.Level.IDLE, unknown.level)
    }

    @Test
    fun `值守模式优先于待机`() {
        val view = DeviceState.evaluate(status(mode = "guard"), lastEventAt = 0L, seenAt = now, now = now)

        assertEquals(DeviceState.Level.GUARDING, view.level)
    }

    @Test
    fun `值守心跳超时才判疑似中断`() {
        val missing = DeviceState.evaluate(
            status(mode = "guard", heartbeatAt = 0L, serverTime = now),
            lastEventAt = 0L,
            seenAt = now,
            now = now,
        )
        val stale = DeviceState.evaluate(
            status(mode = "guard", heartbeatAt = now - 91_000L, serverTime = now),
            lastEventAt = 0L,
            seenAt = now,
            now = now,
        )
        val fresh = DeviceState.evaluate(
            status(mode = "guard", heartbeatAt = now - 90_000L, serverTime = now),
            lastEventAt = 0L,
            seenAt = now,
            now = now,
        )

        assertTrue(missing.heartbeatStale)
        assertTrue(stale.heartbeatStale)
        // 阈值是“超过 90 秒”，正好 90 秒不算中断
        assertFalse(fresh.heartbeatStale)
    }

    @Test
    fun `待机设备不检查心跳`() {
        // 待机本来就不巡检，用同一阈值会把所有待机设备误标成中断
        val view = DeviceState.evaluate(
            status(mode = "idle", heartbeatAt = 0L, serverTime = now),
            lastEventAt = 0L,
            seenAt = now,
            now = now,
        )

        assertFalse(view.heartbeatStale)
    }

    @Test
    fun `事件时间取本地与服务端的较新者`() {
        val serverEvent = now - 10_000L
        val localEvent = now - 60_000L
        val view = DeviceState.evaluate(
            status(lastEventAt = serverEvent),
            lastEventAt = localEvent,
            seenAt = now,
            now = now,
        )

        assertEquals(serverEvent, view.lastEventAt)
    }

    @Test
    fun `服务端字段原样透传`() {
        val view = DeviceState.evaluate(
            status(watching = 3, maxViewers = 4, throttled = true),
            lastEventAt = 0L,
            seenAt = now,
            now = now,
        )

        assertEquals(3, view.watching)
        assertEquals(4, view.maxViewers)
        assertTrue(view.throttled)
        assertEquals(now, view.seenAt)
        assertEquals(80, view.battery)
    }
}
