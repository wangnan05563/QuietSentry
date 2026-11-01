package com.quietsentry.app

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 局域网 TLS 身份（PRD FR-14）。
 *
 * 为什么每台设备单独签发：共用证书的私钥会随 APK 分发，任何拿到安装包的人都能解密流量，
 * 这样的 TLS 只是形式。这里首次启动生成 RSA 密钥与自签证书，私钥只落在本机私有目录。
 *
 * 证书有效期 10 年：设备作为常驻摄像头，频繁轮换证书会让已配对的观看端全部失效，
 * 收益远小于重新配对的成本。
 */
object TlsIdentity {

    private const val PREFS = "tls_prefs"
    private const val KEY_PASSWORD = "keystore_password"
    private const val FILE = "tls-identity.p12"
    private const val ALIAS = "quietsentry"
    private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000

    data class Material(val context: SSLContext, val fingerprint: String)

    @Volatile
    private var cached: Material? = null

    @Synchronized
    fun material(context: Context): Material {
        cached?.let { return it }
        val app = context.applicationContext
        val p12 = File(app.filesDir, FILE)
        val password = passwordOf(app)
        val keyStore = KeyStore.getInstance("PKCS12")
        if (p12.exists()) {
            p12.inputStream().use { keyStore.load(it, password) }
        } else {
            keyStore.load(null, null)
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = Date()
            val name = X500Name("CN=QuietSentry,O=QuietSentry,C=CN")
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(System.currentTimeMillis()),
                now,
                Date(now.time + TEN_YEARS_MS),
                name,
                pair.public,
            )
            val cert = JcaX509CertificateConverter().getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            )
            keyStore.setKeyEntry(ALIAS, pair.private, password, arrayOf(cert))
            p12.outputStream().use { keyStore.store(it, password) }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
        val cert = keyStore.getCertificate(ALIAS)
        val material = Material(ssl, CryptoBox.fingerprint(cert.publicKey))
        cached = material
        return material
    }

    /** 密钥库口令随机生成并只存本机；与配对密钥同一安全边界，不引入第二套账号体系 */
    private fun passwordOf(context: Context): CharArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PASSWORD, null)?.let { return it.toCharArray() }
        val random = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val value = Base64.encodeToString(random, Base64.NO_WRAP)
        prefs.edit().putString(KEY_PASSWORD, value).apply()
        return value.toCharArray()
    }
}
