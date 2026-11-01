package com.quietsentry.app

import android.content.Context
import android.util.Base64
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
        /** 首次加入时间；成员列表要回答“这是谁、什么时候来的” */
        val joinedAt: Long,
    ) {
        fun isActive(now: Long = System.currentTimeMillis()): Boolean =
            expiresAt == 0L || expiresAt > now
    }

    /** role 决定兑换后拿到的是长期成员还是限时访客，所以邀请必须自带角色 */
    data class Invite(val token: String, val expiresAt: Long, val role: Role)

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
    // ---------- 本地恢复码（PRD §10.3 / 开放问题 O4） ----------

    private val recoveryHash: String?
        get() = prefs.getString(KEY_RECOVERY_HASH, null)

    /**
     * 生成一次性恢复码：只存 SHA-256 摘要，页面关闭后无法再次展示。
     * 恢复码不是日常配对入口，只在换机/丢失时用于强制接管，因此给 24 小时有效期。
     */
    @Synchronized
    fun newRecoveryCode(): String {
        val code = CryptoBox.randomCode()
        val hash = MessageDigest.getInstance("SHA-256").digest(code.toByteArray())
        prefs.edit()
            .putString(KEY_RECOVERY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putLong(KEY_RECOVERY_EXPIRES, System.currentTimeMillis() + RECOVERY_TTL_MS)
            .putBoolean(KEY_RECOVERY_USED, false)
            .apply()
        return code
    }

    /** 校验并消费恢复码；哈希比对使用常量时间，避免逐字节试探 */
    @Synchronized
    fun consumeRecoveryCode(input: String): Boolean {
        if (prefs.getBoolean(KEY_RECOVERY_USED, true)) return false
        if (prefs.getLong(KEY_RECOVERY_EXPIRES, 0L) <= System.currentTimeMillis()) return false
        val expected = recoveryHash ?: return false
        val actual = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(input.trim().toByteArray()),
            Base64.NO_WRAP
        )
        if (!MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())) return false
        prefs.edit().putBoolean(KEY_RECOVERY_USED, true).apply()
        return true
    }

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
        val peer = runCatching { CryptoBox.decodePublicKey(clientPublicKey) }.getOrNull() ?: return null
        val fingerprint = CryptoBox.fingerprint(peer)
        val existing = readGrants().firstOrNull { it.fingerprint == fingerprint }
        // 同一台设备重配不占新名额，但要继承首次加入时间，列表里不该因为重连把资历刷掉
        if (existing == null && activeGrants().size >= MAX_GRANTS) return null
        if (code != null && !consumePairCode(code)) return null
        val own = identity()
        val salt = MessageDigest.getInstance("SHA-256")
            .digest(CryptoBox.encodePublicKey(own.public).toByteArray() + clientPublicKey.toByteArray())
        val key = CryptoBox.sharedSecret(own.private, peer, salt)
        val now = System.currentTimeMillis()
        val grant = Grant(
            token = CryptoBox.randomToken(),
            role = role,
            fingerprint = fingerprint,
            key = key,
            expiresAt = if (ttlMs > 0L) now + ttlMs else 0L,
            joinedAt = existing?.joinedAt?.takeIf { it > 0L } ?: now,
        )
        saveGrant(grant)
        return grant
    }

    /** 名额包含 Owner：PRD §4.4 规定单台摄像头最多 5 人（O3） */
    @Synchronized
    fun atCapacity(): Boolean = activeGrants().size >= MAX_GRANTS

    /** 供界面显示“已授权 x/5”与上限提示 */
    val maxGrants: Int get() = MAX_GRANTS

    @Synchronized
    fun hasGrant(clientPublicKey: String): Boolean {
        val peer = runCatching { CryptoBox.decodePublicKey(clientPublicKey) }.getOrNull() ?: return false
        val fingerprint = CryptoBox.fingerprint(peer)
        return activeGrants().any { it.fingerprint == fingerprint }
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
     * 成员 / 访客邀请（PRD FR-12）：邀请本身不绑定具体设备，谁先兑换谁获得。
     * 同一条链接可以被转发，所以它必须一次性、且带有效期，不能变成长期通行证。
     */
    @Synchronized
    fun createInvite(role: Role, ttlMs: Long): Invite {
        val now = System.currentTimeMillis()
        val invite = Invite(
            token = CryptoBox.randomToken(),
            expiresAt = now + ttlMs.coerceIn(MIN_INVITE_MS, MAX_INVITE_MS),
            role = role,
        )
        val array = readJsonArray(KEY_INVITES)
        array.put(JSONObject().apply {
            put("token", invite.token)
            put("expiresAt", invite.expiresAt)
            put("role", invite.role.raw)
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
        val role = Role.fromRaw(hit.optString("role"))
        // Member 是长期身份，邀请的有效期只限制“多久内可兑换”；
        // Guest 的权限本身就是限时的，所以授权止于邀请到期时间。
        val ttl = if (role == Role.GUEST) hit.optLong("expiresAt") - now else 0L
        val grant = pair(clientPublicKey, role, ttl)
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
            put("joinedAt", grant.joinedAt)
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
                joinedAt = obj.optLong("joinedAt"),
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
                put("joinedAt", grant.joinedAt)
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
        const val KEY_RECOVERY_HASH = "recovery_hash"
        const val KEY_RECOVERY_EXPIRES = "recovery_expires"
        const val KEY_RECOVERY_USED = "recovery_used"
        const val RECOVERY_TTL_MS = 24 * 60 * 60 * 1000L
        const val MIN_INVITE_MS = 10 * 60 * 1000L
        /** PRD FR-12 给 Guest 的有效期上限是 24 小时，邀请窗口不应超过它 */
        const val MAX_INVITE_MS = 24 * 60 * 60 * 1000L

        /** PRD §4.4（决策 O3）：单台摄像头最多 5 人，含 Owner */
        const val MAX_GRANTS = 5
    }
}
