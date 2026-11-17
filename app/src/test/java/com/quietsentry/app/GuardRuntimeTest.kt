package com.quietsentry.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 进程内运行态（PRD FR-13）：心跳、降载标记与分片计数都被观看端与首页状态卡读取。
 * 单例状态必须在每个用例前复位，否则用例之间会互相污染。
 */
class GuardRuntimeTest {

    @Before
    fun resetRuntime() {
        GuardRuntime.reset()
    }

    @Test
    fun `开始值守会清零分片计数并记录模式`() {
        GuardRuntime.segmentDone()
        GuardRuntime.segmentDone()

        GuardRuntime.start(atMs = 1_000L, recordOnlyMode = true)

        assertEquals(1_000L, GuardRuntime.startedAtMs)
        assertTrue(GuardRuntime.recordOnly)
        assertEquals(0, GuardRuntime.segmentCount)
    }

    @Test
    fun `心跳按传入时刻刷新`() {
        GuardRuntime.beat(5_000L)

        assertEquals(5_000L, GuardRuntime.heartbeatAt)
    }

    @Test
    fun `每次收敛分片递增计数`() {
        GuardRuntime.start(atMs = 0L, recordOnlyMode = false)

        GuardRuntime.segmentDone()
        GuardRuntime.segmentDone()
        GuardRuntime.segmentDone()

        assertEquals(3, GuardRuntime.segmentCount)
    }

    @Test
    fun `复位清空全部运行态`() {
        GuardRuntime.start(atMs = 9_000L, recordOnlyMode = true)
        GuardRuntime.beat(9_500L)
        GuardRuntime.segmentDone()
        GuardRuntime.throttled = true

        GuardRuntime.reset()

        assertEquals(0L, GuardRuntime.heartbeatAt)
        assertEquals(0L, GuardRuntime.startedAtMs)
        assertFalse(GuardRuntime.recordOnly)
        assertFalse(GuardRuntime.throttled)
        assertEquals(0, GuardRuntime.segmentCount)
        assertEquals(PowerGuard.Reason.NONE, GuardRuntime.stoppedReason)
    }

    @Test
    fun `降载标记可独立切换`() {
        GuardRuntime.throttled = true
        assertTrue(GuardRuntime.throttled)

        GuardRuntime.throttled = false
        assertFalse(GuardRuntime.throttled)
    }
}
