package com.quietsentry.app

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * 观看端对相机端自签证书的信任锚（PRD FR-14）。
 *
 * 为什么不信任系统 CA：自签证书过不了系统校验；这里按“证书公钥指纹”pin 住，
 * 首次配对时从 /api/info 取得指纹，后续连接只接受同一证书，换证书即报警。
 */
object TlsTrust {

    private data class Pin(val fingerprint: String, val factory: SSLSocketFactory)

    private val pins = ConcurrentHashMap<String, Pin>()

    private fun key(host: String, port: Int): String = "$host:$port"

    fun register(host: String, port: Int, fingerprint: String) {
        if (port <= 0 || fingerprint.isEmpty()) return
        pins.getOrPut(key(host, port)) { Pin(fingerprint, factory(fingerprint)) }
    }

    fun fingerprintFor(host: String, port: Int): String? = pins[key(host, port)]?.fingerprint

    fun factoryFor(host: String, port: Int): SSLSocketFactory? = pins[key(host, port)]?.factory

    private fun factory(fingerprint: String): SSLSocketFactory {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("client certificate not accepted")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("empty chain")
                val actual = CryptoBox.fingerprint(cert.publicKey)
                if (!MessageDigest.isEqual(actual.toByteArray(), fingerprint.toByteArray())) {
                    throw CertificateException("certificate fingerprint mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trust), SecureRandom())
        return context.socketFactory
    }
}
