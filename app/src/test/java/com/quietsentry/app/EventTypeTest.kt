package com.quietsentry.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 事件类型解析（PRD §7）：数据库里的字符串要能稳定映射回枚举，
 * 未知值必须退回 MOTION 而不是崩溃，否则旧版本数据会让回看页直接打不开。
 */
class EventTypeTest {

    @Test
    fun `已知原始值按名映射`() {
        assertEquals(EventType.MOTION, EventType.fromRaw("motion"))
        assertEquals(EventType.SOUND, EventType.fromRaw("sound"))
        assertEquals(EventType.PERSON, EventType.fromRaw("person"))
    }

    @Test
    fun `未知与空值回退为运动事件`() {
        assertEquals(EventType.MOTION, EventType.fromRaw(""))
        assertEquals(EventType.MOTION, EventType.fromRaw("unknown"))
        // 大小写敏感是有意的：数据库写入口只接受小写，不做模糊匹配掩盖脏数据
        assertEquals(EventType.MOTION, EventType.fromRaw("MOTION"))
    }

    @Test
    fun `每个类型都带非空原始值`() {
        EventType.entries.forEach { type ->
            assertEquals(type, EventType.fromRaw(type.raw))
        }
    }
}
