package com.quietsentry.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配对与设备管理（PRD FR-02 / §4.2）。
 *
 * 相机端是唯一能颁发令牌的一方，所以这台设备就是配对的“现场”：
 * 二维码承载一次性配对码，6 位码是扫不出码时的兜底，
 * 下方的设备列表则是当前所有持有效令牌的观看端。
 *
 * 页面打开时会重新生成配对码——旧码随之失效。
 * 这是有意的：一张长期有效的码如果被截图转发出去，等于永久后门。
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var pairing: PairingStore
    private lateinit var hintText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var codeText: TextView
    private lateinit var fingerprintText: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var emptyDevices: TextView

    /** 非空表示当前二维码是这条一次性邀请，而不是常驻配对码 */
    private var inviteLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        pairing = PairingStore(this)
        hintText = findViewById(R.id.pairing_hint)
        qrImage = findViewById(R.id.pairing_qr)
        codeText = findViewById(R.id.pairing_code)
        fingerprintText = findViewById(R.id.pairing_fingerprint)
        deviceList = findViewById(R.id.pairing_devices)
        emptyDevices = findViewById(R.id.pairing_devices_empty)

        findViewById<Button>(R.id.pairing_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.pairing_refresh).setOnClickListener {
            // 刷新回到常驻配对码：邀请是一次性的，不能靠“刷新”续命
            inviteLink = null
            refresh()
        }
        findViewById<Button>(R.id.pairing_invite).setOnClickListener { showInvite() }
        findViewById<Button>(R.id.pairing_revoke_all).setOnClickListener { confirmRevokeAll() }
        refresh()
    }

    private fun refresh() {
        // 二维码里的端口必须就是正在监听的端口，否则观看端连过去是一个没人接受的端口
        val port = LanServerHost.ensure(applicationContext)
        val host = PairingQr.localIpv4(this)
        val fingerprint = pairing.fingerprint()
        fingerprintText.text = getString(R.string.pairing_fingerprint, fingerprint)

        val invite = inviteLink
        if (invite != null) {
            hintText.text = getString(R.string.pairing_invite_ready)
            codeText.text = getString(R.string.pairing_invite_label)
            showQr(invite)
        } else {
            val code = pairing.newPairCode()
            if (port == null || host == null) {
                // 没有局域网地址时二维码一定是废的，此时引导用户先连 WiFi，而不是画一张扫了也没用的码
                hintText.text = getString(R.string.pairing_no_address)
                qrImage.visibility = View.GONE
            } else {
                hintText.text = getString(R.string.pairing_hint)
                showQr(PairingQr.pairLink(host, port, code, fingerprint))
            }
            codeText.text = getString(R.string.pairing_code_label, code)
        }
        renderDevices()
    }

    private fun showQr(content: String) {
        val bitmap = PairingQr.encode(content, QR_SIZE_PX)
        qrImage.setImageBitmap(bitmap)
        qrImage.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        if (bitmap == null) {
            Toast.makeText(this, R.string.pairing_qr_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInvite() {
        val port = LanServerHost.ensure(applicationContext)
        val host = PairingQr.localIpv4(this)
        if (port == null || host == null) {
            Toast.makeText(this, R.string.pairing_no_address, Toast.LENGTH_SHORT).show()
            return
        }
        // 30 分钟够对方走到设备前扫码；再长会让一条链接在聊天记录里变成长期通行证
        val invite = pairing.createInvite(INVITE_TTL_MS)
        inviteLink = PairingQr.joinLink(host, port, invite.token, pairing.fingerprint())
        refresh()
    }

    private fun renderDevices() {
        val grants = pairing.activeGrants()
        deviceList.removeAllViews()
        emptyDevices.visibility = if (grants.isEmpty()) View.VISIBLE else View.GONE
        grants.forEach { deviceList.addView(deviceRow(it)) }
    }

    private fun deviceRow(grant: PairingStore.Grant): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val info = TextView(this).apply {
            // 指纹是设备唯一稳定标识；令牌会随重配变化，给用户看的必须是前者
            text = buildString {
                append(getString(roleName(grant.role)))
                append(" · ")
                append(grant.fingerprint.take(12))
                append('\n')
                append(
                    if (grant.expiresAt == 0L) {
                        getString(R.string.pairing_device_longterm)
                    } else {
                        getString(
                            R.string.pairing_device_expires,
                            TIME_FORMAT.format(Date(grant.expiresAt))
                        )
                    }
                )
            }
            setTextColor(0xFFF2F2F2.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val revoke = Button(this).apply {
            text = getString(R.string.pairing_revoke)
            setOnClickListener { confirmRevoke(grant) }
        }
        row.addView(info)
        row.addView(revoke)
        return row
    }

    private fun confirmRevoke(grant: PairingStore.Grant) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pairing_revoke_title)
            .setMessage(R.string.pairing_revoke_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pairing_revoke) { _, _ ->
                pairing.revoke(grant.token)
                Toast.makeText(this, R.string.pairing_revoked, Toast.LENGTH_SHORT).show()
                // 直播连接会在下一次令牌心跳里自行断开，这里只刷新列表
                renderDevices()
            }
            .show()
    }

    private fun confirmRevokeAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pairing_revoke_all_title)
            .setMessage(R.string.pairing_revoke_all_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pairing_revoke_all) { _, _ ->
                pairing.revokeAll()
                Toast.makeText(this, R.string.pairing_revoked, Toast.LENGTH_SHORT).show()
                renderDevices()
            }
            .show()
    }

    private fun roleName(role: PairingStore.Role): Int = when (role) {
        PairingStore.Role.OWNER -> R.string.pairing_role_owner
        PairingStore.Role.MEMBER -> R.string.pairing_role_member
        PairingStore.Role.GUEST -> R.string.pairing_role_guest
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 输出 480px 而不是屏上的 240dp：低清二维码在稍远距离或弱光下识别率明显下降 */
        const val QR_SIZE_PX = 480
        const val INVITE_TTL_MS = 30 * 60 * 1000L
        val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
}
