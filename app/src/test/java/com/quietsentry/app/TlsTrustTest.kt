package com.quietsentry.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自签证书信任锚（PRD FR-14）：首次配对按指纹 pin 住，换证书必须视为另一台设备。
 * 这里只验证“注册与查询”这一层；真正的证书校验在 TLS 握手时由 TrustManager 执行。
 */
class TlsTrustTest {

    @Test
    fun `注册后可按主机与端口取回指纹`() {
        TlsTrust.register("10.0.0.21", 8443, "sha256-abc")

        assertEquals("sha256-abc", TlsTrust.fingerprintFor("10.0.0.21", 8443))
        assertNotNull(TlsTrust.factoryFor("10.0.0.21", 8443))
    }

    @Test
    fun `端口为零或指纹为空时拒绝注册`() {
        // 旧版本设备没有 TLS 字段，不能因此登记一个空信任锚
        TlsTrust.register("10.0.0.22", 0, "sha256-abc")
        TlsTrust.register("10.0.0.23", 8443, "")

        assertNull(TlsTrust.fingerprintFor("10.0.0.22", 0))
        assertNull(TlsTrust.fingerprintFor("10.0.0.23", 8443))
        assertNull(TlsTrust.factoryFor("10.0.0.23", 8443))
    }

    @Test
    fun `同一主机端口只保留首次注册的指纹`() {
        // 已配对设备重复注册不应静默换成新指纹：换指纹意味着对方换了证书，必须走重新配对
        TlsTrust.register("10.0.0.24", 8443, "first")
        TlsTrust.register("10.0.0.24", 8443, "second")

        assertEquals("first", TlsTrust.fingerprintFor("10.0.0.24", 8443))
    }

    @Test
    fun `不同端口各自独立`() {
        TlsTrust.register("10.0.0.25", 8443, "a")
        TlsTrust.register("10.0.0.25", 9443, "b")

        assertEquals("a", TlsTrust.fingerprintFor("10.0.0.25", 8443))
        assertEquals("b", TlsTrust.fingerprintFor("10.0.0.25", 9443))
        assertNull(TlsTrust.fingerprintFor("10.0.0.25", 7443))
    }

    @Test
    fun `未注册的主机查询返回空`() {
        assertNull(TlsTrust.fingerprintFor("192.168.1.99", 8443))
        assertNull(TlsTrust.factoryFor("192.168.1.99", 8443))
    }
}
