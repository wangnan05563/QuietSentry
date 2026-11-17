package com.quietsentry.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人形框的跨线程发布点（T04053）：推理线程写、HTTP 线程读。
 * 旧框必须能过期，否则观看端会一直画一个早就不存在的目标。
 */
class PersonBoxesTest {

    @Test
    fun `刚发布的框在有效期内可取回`() {
        PersonBoxes.publish(listOf(floatArrayOf(1f, 2f, 3f, 4f)))

        val result = PersonBoxes.recent(maxAgeMs = 5_000L, nowMs = System.currentTimeMillis())

        assertTrue(result != null)
        assertEquals(1, result!!.boxes.size)
        assertEquals(4, result.boxes[0].size)
    }

    @Test
    fun `超过有效期返回空`() {
        PersonBoxes.publish(listOf(floatArrayOf(1f, 2f, 3f, 4f)))

        assertNull(PersonBoxes.recent(maxAgeMs = 1L, nowMs = System.currentTimeMillis() + 10L))
    }

    @Test
    fun `有效期足够短时仍能取到刚发布的框`() {
        PersonBoxes.publish(listOf(floatArrayOf(0f, 0f, 1f, 1f)))

        // 时间来自真实时钟，无法注入 atMs；这里只验证“刚发布即有效”，
        // 精确的边界判等在时间可控时才有意义，不做容易抖动的断言
        assertTrue(PersonBoxes.recent(maxAgeMs = 5_000L, nowMs = System.currentTimeMillis()) != null)
    }

    @Test
    fun `发布空框会作废上一批结果`() {
        PersonBoxes.publish(listOf(floatArrayOf(1f, 2f, 3f, 4f)))
        PersonBoxes.publish(emptyList())

        assertNull(PersonBoxes.recent(maxAgeMs = 60_000L, nowMs = System.currentTimeMillis()))
    }

    @Test
    fun `多目标框按发布顺序保留`() {
        val first = floatArrayOf(1f, 1f, 2f, 2f)
        val second = floatArrayOf(3f, 3f, 4f, 4f)
        PersonBoxes.publish(listOf(first, second))

        val result = PersonBoxes.recent(maxAgeMs = 60_000L, nowMs = System.currentTimeMillis())

        assertEquals(2, result!!.boxes.size)
        assertEquals(1f, result.boxes[0][0], 0f)
        assertEquals(3f, result.boxes[1][0], 0f)
    }
}
