package com.quietsentry.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec

/**
 * 角色与授权（PRD §4.2）：Owner 持主密钥，Member 看实时与回看，Guest 限时看实时。
 * 不建账号，所以“你是谁”完全由这次配对交换的公钥指纹 + 会话令牌证明。
 *
 * 为什么把会话密钥也持久化：相机端可能在配对后被杀进程，
 * 重启后旧观看端手里的令牌必须继续有效，否则用户每次都要重新扫码。
 * 因此令牌与派生密钥一起落 SharedPreferences，撤销时整条移除。
 */
class PairingStore(context: Context) {

    enum class Role(val raw: String) {
        OWNER("owner"),
        MEMBER("member"),
        GUEST("guest");

        companion object {
            fun fromRaw(raw: String): Role = entries.firstOrNull { it.raw == raw } ?: GUEST
        }
    }

    data class Grant(
        val token: String,
        val role: Role,
        val fingerprint: String,
        val key: ByteArray,
        /** 0 表示长期有效；Guest 为邀请截止时间 */
        val expiresAt: Long,
    ) {
        fun isActive(now: Long = System.currentTimeMillis()): Boolean =
            expiresAt == 0L || expiresAt > now
    }

    data class Invite(val token: String, val expiresAt: Long)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun identity(): KeyPair {
        val privateEncoded = prefs.getString(KEY_PRIVATE, null)
        val publicEncoded = prefs.getString(KEY_PUBLIC, null)
        if (privateEncoded != null && publicEncoded != null) {
            // 已持久化的身份必须原样恢复：重新生成会换掉指纹，已配对设备会全部失效
            runCatching {
                KeyPair(
                    CryptoBox.decodePublicKey(publicEncoded),
                    CryptoBox.decodePrivateKey(privateEncoded)
                )
            }.getOrNull()?.let { return it }
        }
        val pair = CryptoBox.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, CryptoBox.encodePrivateKey(pair.private))
            .putString(KEY_PUBLIC, CryptoBox.encodePublicKey(pair.public))
            .apply()
        return pair
    }

    fun publicKeyBase64(): String = CryptoBox.encodePublicKey(identity().public)

    fun fingerprint(): String = CryptoBox.fingerprint(identity().public)

    /**
     * 一次性 6 位码：作为二维码扫不出来时的兜底。5 分钟有效且用一次即失效，
     * 避免用户把一张截图长期留在聊天记录里变成永久后门。
     */
    @Synchronized
    fun newPairCode(): String {
        val code = CryptoBox.randomCode()
        prefs.edit()
            .putString(KEY_CODE, code)
            .putLong(KEY_CODE_EXPIRES, System.currentTimeMillis() + PAIR_CODE_TTL_MS)
            .putBoolean(KEY_CODE_USED, false)
            .apply()
        return code
    }

    @Synchronized
    fun currentPairCode(): String? {
        if (prefs.getBoolean(KEY_CODE_USED, true)) return null
        if (prefs.getLong(KEY_CODE_EXPIRES, 0L) <= System.currentTimeMillis()) return null
        return prefs.getString(KEY_CODE, null)
    }

    /** 校验并消费一次性码；失败不清空，用户还有机会重输（但仍受过期约束） */
    @Synchronized
    fun consumePairCode(input: String): Boolean {
        val expected = currentPairCode() ?: return false
        if (input.trim() != expected) return false
        prefs.edit().putBoolean(KEY_CODE_USED, true).apply()
        return true
    }

    @Synchronized
    fun pair(clientPublicKey: String, role: Role, ttlMs: Long, code: String? = null): Grant? {
        if (code != null && !consumePairCode(code)) return null
        val peer = runCatching { CryptoBox.decodePublicKey(clientPublicKey) }.getOrNull() ?: return null
        val own = identity()
        val salt = MessageDigest.getInstance("SHA-256")
            .digest(CryptoBox.encodePublicKey(own.public).toByteArray() + clientPublicKey.toByteArray())
        val key = CryptoBox.sharedSecret(own.private, peer, salt)
        val grant = Grant(
            token = CryptoBox.randomToken(),
            role = role,
            fingerprint = CryptoBox.fingerprint(peer),
            key = key,
            expiresAt = if (ttlMs > 0L) System.currentTimeMillis() + ttlMs else 0L,
        )
        saveGrant(grant)
        return grant
    }

    /** 令牌鉴权；过期条目在这里顺手清掉，避免长期堆积成无效授权 */
    @Synchronized
    fun authenticate(token: String): Grant? {
        val now = System.currentTimeMillis()
        val all = readGrants()
        val hit = all.firstOrNull { it.token == token }
        if (hit == null) return null
        if (!hit.isActive(now)) {
            writeGrants(all.filterNot { it.token == token })
            return null
        }
        return hit
    }

    @Synchronized
    fun revoke(token: String) {
        writeGrants(readGrants().filterNot { it.token == token })
    }

    @Synchronized
    fun revokeAll() {
        prefs.edit().remove(KEY_GRANTS).apply()
    }

    @Synchronized
    fun activeGrants(): List<Grant> {
        val now = System.currentTimeMillis()
        return readGrants().filter { it.isActive(now) }
    }

    /**
     * 成员邀请：Owner 生成一条限时令牌，Member 凭它配对后直接获得 Guest 级权限。
     * 邀请本身不绑定具体设备，谁先redeem谁获得——这也是“临时访客”的语义。
     */
    @Synchronized
    fun createInvite(ttlMs: Long): Invite {
        val now = System.currentTimeMillis()
        val invite = Invite(CryptoBox.randomToken(), now + ttlMs.coerceAtLeast(MIN_INVITE_MS))
        val array = readJsonArray(KEY_INVITES)
        array.put(JSONObject().apply {
            put("token", invite.token)
            put("expiresAt", invite.expiresAt)
        })
        prefs.edit().putString(KEY_INVITES, array.toString()).apply()
        return invite
    }

    @Synchronized
    fun redeemInvite(inviteToken: String, clientPublicKey: String): Grant? {
        val now = System.currentTimeMillis()
        val invites = readJsonArray(KEY_INVITES)
        val hit = (0 until invites.length())
            .map { invites.getJSONObject(it) }
            .firstOrNull { it.optString("token") == inviteToken && it.optLong("expiresAt") > now }
            ?: return null
        val grant = pair(clientPublicKey, Role.GUEST, hit.optLong("expiresAt") - now)
            ?: return null
        // 邀请是一次性的：成功兑换后从列表移除，防止一条链接被无限转发
        val remaining = JSONArray()
        for (i in 0 until invites.length()) {
            val obj = invites.getJSONObject(i)
            if (obj.optString("token") != inviteToken) remaining.put(obj)
        }
        prefs.edit().putString(KEY_INVITES, remaining.toString()).apply()
        return grant
    }

    fun sessionCipher(grant: Grant): SecretKeySpec = SecretKeySpec(grant.key, "AES")

    private fun saveGrant(grant: Grant) {
        val array = readJsonArray(KEY_GRANTS)
        // 同指纹重复配对时替换旧令牌：一台设备只应有一条有效会话，避免撤销后旧令牌仍然可用
        val cleaned = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.optString("fingerprint") != grant.fingerprint) cleaned.put(obj)
        }
        cleaned.put(JSONObject().apply {
            put("token", grant.token)
            put("role", grant.role.raw)
            put("fingerprint", grant.fingerprint)
            put("key", android.util.Base64.encodeToString(grant.key, android.util.Base64.NO_WRAP))
            put("expiresAt", grant.expiresAt)
        })
        prefs.edit().putString(KEY_GRANTS, cleaned.toString()).apply()
    }

    private fun readGrants(): List<Grant> {
        val array = readJsonArray(KEY_GRANTS)
        val result = ArrayList<Grant>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val key = runCatching {
                android.util.Base64.decode(obj.optString("key"), android.util.Base64.NO_WRAP)
            }.getOrNull() ?: continue
            result += Grant(
                token = obj.optString("token"),
                role = Role.fromRaw(obj.optString("role")),
                fingerprint = obj.optString("fingerprint"),
                key = key,
                expiresAt = obj.optLong("expiresAt"),
            )
        }
        return result
    }

    private fun writeGrants(grants: List<Grant>) {
        val array = JSONArray()
        for (grant in grants) {
            array.put(JSONObject().apply {
                put("token", grant.token)
                put("role", grant.role.raw)
                put("fingerprint", grant.fingerprint)
                put("key", android.util.Base64.encodeToString(grant.key, android.util.Base64.NO_WRAP))
                put("expiresAt", grant.expiresAt)
            })
        }
        prefs.edit().putString(KEY_GRANTS, array.toString()).apply()
    }

    private fun readJsonArray(key: String): JSONArray =
        runCatching { JSONArray(prefs.getString(key, "[]") ?: "[]") }.getOrDefault(JSONArray())

    private companion object {
        const val PREFS = "pairing"
        const val KEY_PRIVATE = "identity_private"
        const val KEY_PUBLIC = "identity_public"
        const val KEY_CODE = "pair_code"
        const val KEY_CODE_EXPIRES = "pair_code_expires"
        const val KEY_CODE_USED = "pair_code_used"
        const val KEY_GRANTS = "grants"
        const val KEY_INVITES = "invites"

        /** 二维码/配对码有效期：够用户从相机端走到观看端，又不至于长期留在相册截图里 */
        const val PAIR_CODE_TTL_MS = 5 * 60 * 1000L
        const val MIN_INVITE_MS = 10 * 60 * 1000L
    }
}
