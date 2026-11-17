package com.quietsentry.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 告警级别与免打扰时段（PRD FR-09）。
 * 级别判错会让声音告警升级成“紧急入侵”，时段判错会让跨午夜的免打扰配置静默失效。
 */
class AlertPolicyTest {

    @Test
    fun `同源连续第三次触发升级为紧急`() {
        assertEquals(AlertLevel.URGENT, AlertPolicy.levelOf(EventType.MOTION, 3))
        assertEquals(AlertLevel.URGENT, AlertPolicy.levelOf(EventType.PERSON, 5))
        assertEquals(AlertLevel.NORMAL, AlertPolicy.levelOf(EventType.MOTION, 2))
        assertEquals(AlertLevel.NORMAL, AlertPolicy.levelOf(EventType.PERSON, 0))
    }

    @Test
    fun `声音事件不升级紧急`() {
        // 持续噪声不能当成入侵，否则夜间告警会变成噪音放大器
        assertEquals(AlertLevel.NORMAL, AlertPolicy.levelOf(EventType.SOUND, 3))
        assertEquals(AlertLevel.NORMAL, AlertPolicy.levelOf(EventType.SOUND, 100))
    }

    @Test
    fun `免打扰时段跨午夜时单独标记`() {
        val quiet = AlertPolicy.Quiet(enabled = true, startMin = 22 * 60, endMin = 7 * 60)

        assertTrue(quiet.crossesMidnight)
        assertEquals("22:00", quiet.startLabel())
        assertEquals("07:00", quiet.endLabel())
    }

    @Test
    fun `同日时段不标记跨午夜`() {
        val quiet = AlertPolicy.Quiet(enabled = true, startMin = 8 * 60, endMin = 22 * 60)

        assertFalse(quiet.crossesMidnight)
        assertEquals("08:00", quiet.startLabel())
        assertEquals("22:00", quiet.endLabel())
    }

    @Test
    fun `起止相同不视为跨午夜`() {
        val quiet = AlertPolicy.Quiet(enabled = true, startMin = 600, endMin = 600)

        assertFalse(quiet.crossesMidnight)
        assertEquals("10:00", quiet.startLabel())
    }

    @Test
    fun `时刻格式化覆盖零点与当天最后一分钟`() {
        assertEquals("00:00", AlertPolicy.Quiet(true, 0, 0).startLabel())
        assertEquals("23:59", AlertPolicy.Quiet(true, 1439, 1439).startLabel())
    }

    @Test
    fun `检测时段同样支持跨午夜标记`() {
        val plan = AlertPolicy.DetectPlan(enabled = true, startMin = 21 * 60, endMin = 6 * 60)

        assertTrue(plan.crossesMidnight)
        assertEquals("21:00", plan.startLabel())
        assertEquals("06:00", plan.endLabel())
    }

    @Test
    fun `默认检测时段覆盖白天`() {
        val plan = AlertPolicy.DetectPlan(enabled = false, startMin = 8 * 60, endMin = 22 * 60)

        assertFalse(plan.crossesMidnight)
        assertEquals("08:00", plan.startLabel())
        assertEquals("22:00", plan.endLabel())
    }
}
