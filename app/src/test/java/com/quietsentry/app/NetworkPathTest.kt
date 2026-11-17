package com.quietsentry.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接顺序判定（PRD FR-08）：私网段才算局域网直连，100.64/10 属于自组网。
 * 判错会直接把“需要用户装 Tailscale”的提示变成“同一个 Wi-Fi 却连不上”。
 */
class NetworkPathTest {

    @Test
    fun `十网段与回环属于私网`() {
        assertTrue(NetworkPath.isPrivateAddress("10.0.0.1"))
        assertTrue(NetworkPath.isPrivateAddress("10.255.255.255"))
        assertTrue(NetworkPath.isPrivateAddress("127.0.0.1"))
    }

    @Test
    fun `一七二点一六到三一属于私网`() {
        assertTrue(NetworkPath.isPrivateAddress("172.16.0.1"))
        assertTrue(NetworkPath.isPrivateAddress("172.31.255.255"))
        // 边界两侧：172.15 与 172.32 都是公网段
        assertFalse(NetworkPath.isPrivateAddress("172.15.0.1"))
        assertFalse(NetworkPath.isPrivateAddress("172.32.0.1"))
    }

    @Test
    fun `一九二点一六八属于私网而相邻段不是`() {
        assertTrue(NetworkPath.isPrivateAddress("192.168.1.10"))
        assertFalse(NetworkPath.isPrivateAddress("192.169.1.10"))
    }

    @Test
    fun `公网地址不算局域网`() {
        assertFalse(NetworkPath.isPrivateAddress("8.8.8.8"))
        assertFalse(NetworkPath.isPrivateAddress("223.5.5.5"))
    }

    @Test
    fun `非法地址一律不认`() {
        // 段数不对、越界、非数字都必须返回 false，避免把主机名误判成网段
        assertFalse(NetworkPath.isPrivateAddress("10.0.0"))
        assertFalse(NetworkPath.isPrivateAddress("10.0.0.1.2"))
        assertFalse(NetworkPath.isPrivateAddress("256.1.1.1"))
        assertFalse(NetworkPath.isPrivateAddress("abc.def.ghi.jkl"))
        assertFalse(NetworkPath.isPrivateAddress(""))
    }

    @Test
    fun `一百点六四到一百二十七属于自组网`() {
        assertTrue(NetworkPath.isOverlayAddress("100.64.0.1"))
        assertTrue(NetworkPath.isOverlayAddress("100.127.255.255"))
        // 边界两侧：100.63 与 100.128 不属于 CGNAT 段
        assertFalse(NetworkPath.isOverlayAddress("100.63.0.1"))
        assertFalse(NetworkPath.isOverlayAddress("100.128.0.1"))
    }

    @Test
    fun `局域网地址不算自组网`() {
        assertFalse(NetworkPath.isOverlayAddress("192.168.1.10"))
        assertFalse(NetworkPath.isOverlayAddress("8.8.8.8"))
    }

    @Test
    fun `自组网判定同样拒绝非法地址`() {
        assertFalse(NetworkPath.isOverlayAddress("100.64.0"))
        assertFalse(NetworkPath.isOverlayAddress("100.64.0.256"))
        assertFalse(NetworkPath.isOverlayAddress("tailscale.example"))
    }
}
