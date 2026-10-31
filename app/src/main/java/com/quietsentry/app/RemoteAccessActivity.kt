package com.quietsentry.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * 自组网访问引导与连通性诊断（PRD FR-08 连接顺序、FR-07 异常「自组网不可达」）。
 *
 * 页面只回答两件事：这台设备现在能不能连上、连不上时用户应该去哪里改。
 * 不做隧道管理——组网是用户自己的 Tailscale / WireGuard，产品既不建中继也不碰密钥。
 */
class RemoteAccessActivity : AppCompatActivity() {

    private lateinit var target: EditText
    private lateinit var probeButton: Button
    private lateinit var networkText: TextView
    private lateinit var lanText: TextView
    private lateinit var overlayText: TextView
    private lateinit var resultText: TextView

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_access)

        target = findViewById(R.id.remote_target)
        probeButton = findViewById(R.id.remote_probe)
        networkText = findViewById(R.id.remote_network)
        lanText = findViewById(R.id.remote_lan_result)
        overlayText = findViewById(R.id.remote_overlay_result)
        resultText = findViewById(R.id.remote_result)

        findViewById<Button>(R.id.remote_back).setOnClickListener { finish() }
        // Wi-Fi 断掉是“局域网直连不可用”的最常见原因，把用户直接送到系统开关处
        findViewById<Button>(R.id.remote_wifi_settings).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        probeButton.setOnClickListener { probe() }

        renderLocal()
        // 从设备卡片进来时带着目标地址，多一步点击只是多一次误操作机会
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        if (host.isNotEmpty()) {
            target.setText("$host:${intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)}")
            probe()
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun renderLocal() {
        val lan = NetworkPath.lanAddress(this)
        val overlay = NetworkPath.overlayAddress()
        networkText.text = getString(
            R.string.remote_network_local,
            lan ?: getString(R.string.remote_none),
            overlay ?: getString(R.string.remote_none),
        )
        lanText.text = getString(R.string.remote_lan_idle)
        overlayText.text = getString(R.string.remote_overlay_idle)
        resultText.text = getString(R.string.remote_result_idle)
    }

    private fun probe() {
        val (host, port) = parseTarget() ?: run {
            Toast.makeText(this, R.string.remote_target_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        probeButton.isEnabled = false
        resultText.text = getString(R.string.remote_probing)
        io.execute {
            val probe = NetworkPath.probe(host, port)
            runOnUiThread { if (!isFinishing) renderProbe(probe) }
        }
    }

    /** 允许 `ip` 与 `ip:port` 两种写法；不填端口时按默认端口，省掉用户去查端口 */
    private fun parseTarget(): Pair<String, Int>? {
        val raw = target.text.toString().trim()
        if (raw.isEmpty()) return null
        val index = raw.lastIndexOf(':')
        if (index <= 0) return raw to DEFAULT_PORT
        val port = raw.substring(index + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return raw.substring(0, index) to port
    }

    private fun renderProbe(probe: NetworkPath.Probe) {
        probeButton.isEnabled = true
        val device = probe.deviceName.ifEmpty { probe.host }
        when (probe.kind) {
            NetworkPath.Kind.LAN -> {
                setLine(lanText, getString(R.string.remote_lan_ok, device), getColor(R.color.qs_ok))
                setLine(overlayText, getString(R.string.remote_overlay_unused), getColor(R.color.qs_idle))
                resultText.text = getString(R.string.remote_result_lan, device)
            }
            NetworkPath.Kind.OVERLAY -> {
                setLine(lanText, getString(R.string.remote_lan_unavailable), getColor(R.color.qs_idle))
                setLine(overlayText, getString(R.string.remote_overlay_ok, device), getColor(R.color.qs_ok))
                resultText.text = getString(R.string.remote_result_overlay, device)
            }
            NetworkPath.Kind.UNREACHABLE -> {
                setLine(lanText, getString(R.string.remote_lan_unreachable), getColor(R.color.qs_danger))
                setLine(overlayText, getString(R.string.remote_overlay_unreachable), getColor(R.color.qs_danger))
                resultText.text = getString(R.string.remote_result_unreachable)
            }
        }
    }

    private fun setLine(view: TextView, text: String, color: Int) {
        view.text = text
        view.setTextColor(color)
    }

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val DEFAULT_PORT = 8080
    }
}
