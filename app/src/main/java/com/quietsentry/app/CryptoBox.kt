package com.quietsentry.app

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配对用的最小密码学工具集（PRD §4.2：角色关系建立在密钥授权上，不建账号）。
 *
 * 为什么用 ECDH + HKDF 而不是直接传一个“会话密码”：局域网里任何一台设备都能嗅探明文，
 * 一次性配对码只负责“确认对端身份”，真正的会话密钥必须双方各自算出、从不上网传输。
 * 用 JDK 自带的 EC/AES 实现，不引入第三方密码库，减少打包体积与供应链面。
 */
object CryptoBox {

    private const val CURVE = "secp256r1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32

    private val random = SecureRandom()

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(CURVE), random)
        }.generateKeyPair()

    fun encodePublicKey(key: PublicKey): String =
        Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    fun decodePublicKey(encoded: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(encoded, Base64.NO_WRAP))
        )

    fun encodePrivateKey(key: PrivateKey): String =
        Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    fun decodePrivateKey(encoded: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(Base64.decode(encoded, Base64.NO_WRAP))
        )

    /**
     * 双方用同一对公私钥算出的共享密钥相同，这就是“会话密钥”的种子。
     * salt 取双方公钥拼接的哈希：同一对设备反复配对也能得到不同密钥，避免密钥复用。
     */
    fun sharedSecret(privateKey: PrivateKey, peer: PublicKey, salt: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH").apply {
            init(privateKey)
            doPhase(peer, true)
        }
        return hkdf(agreement.generateSecret(), salt, KEY_BYTES)
    }

    /** 对端公钥指纹：配对界面上让用户核对“扫到的设备”就是眼前这台 */
    fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return digest.take(8).joinToString(":") { "%02X".format(it) }
    }

    /** 输出 IV(12B) + 密文与 GCM tag；解密失败返回 null，调用方按“认证失败”处理 */
    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(key: ByteArray, packet: ByteArray): ByteArray? {
        if (packet.size <= IV_BYTES) return null
        return runCatching {
            val iv = packet.copyOfRange(0, IV_BYTES)
            val body = packet.copyOfRange(IV_BYTES, packet.size)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(body)
            }
        }.getOrNull()
    }

    fun randomToken(bytes: Int = 16): String {
        val raw = ByteArray(bytes).also(random::nextBytes)
        return Base64.encodeToString(raw, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun randomCode(): String = "%06d".format(random.nextInt(1_000_000))

    private fun hkdf(ikm: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // 单块 expand 足够：本项目只需要 32 字节会话密钥
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val info = "quietsentry-session".toByteArray(Charsets.UTF_8)
        return mac.doFinal(info + byteArrayOf(1)).copyOf(length)
    }
}
