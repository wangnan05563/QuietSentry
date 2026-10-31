package com.quietsentry.app

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置与诊断（PRD FR-17 与 FR-09 的可配置入口）。
 *
 * 免打扰与接收级别直接写 AlertPolicy 的 prefs：EventNotifier 每次发通知前都会重读，
 * 所以这里改完立刻生效，不需要重启值守服务，也不需要通知服务做一次“刷新”。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var quietSwitch: SwitchCompat
    private lateinit var quietStart: Button
    private lateinit var quietEnd: Button
    private lateinit var quietHint: TextView
    private lateinit var levelGroup: RadioGroup
    private lateinit var resultContainer: LinearLayout
    private lateinit var summary: TextView

    private var quiet = AlertPolicy.Quiet(false, 0, 0)
    private var checks: List<Diagnostics.Check> = emptyList()

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(Diagnostics.exportReport(this, checks).toByteArray())
            } ?: error("no stream")
        }.isSuccess
        Toast.makeText(
            this,
            if (ok) R.string.settings_diag_exported else R.string.settings_diag_export_failed,
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        quietSwitch = findViewById(R.id.settings_quiet_switch)
        quietStart = findViewById(R.id.settings_quiet_start)
        quietEnd = findViewById(R.id.settings_quiet_end)
        quietHint = findViewById(R.id.settings_quiet_hint)
        levelGroup = findViewById(R.id.settings_level_group)
        resultContainer = findViewById(R.id.settings_diag_result)
        summary = findViewById(R.id.settings_diag_summary)

        findViewById<Button>(R.id.settings_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.settings_diag_run).setOnClickListener { runDiagnostics() }
        findViewById<Button>(R.id.settings_diag_export).setOnClickListener {
            if (checks.isEmpty()) checks = Diagnostics.run(this)
            createDocument.launch("quietsentry-diag.txt")
        }
        findViewById<Button>(R.id.settings_quick_pairing).setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }
        findViewById<Button>(R.id.settings_quick_devices).setOnClickListener {
            startActivity(Intent(this, DeviceListActivity::class.java))
        }

        quiet = AlertPolicy.quiet(this)
        quietSwitch.setOnCheckedChangeListener { _, checked ->
            quiet = quiet.copy(enabled = checked)
            persistQuiet()
        }
        quietStart.setOnClickListener {
            pickTime(quiet.startMin) { quiet = quiet.copy(startMin = it); persistQuiet() }
        }
        quietEnd.setOnClickListener {
            pickTime(quiet.endMin) { quiet = quiet.copy(endMin = it); persistQuiet() }
        }
        renderQuiet()

        renderLevel()
        levelGroup.setOnCheckedChangeListener { _, checkedId ->
            AlertPolicy.setMinLevel(this, levelOf(checkedId))
        }

        renderLastSummary()
    }

    private fun persistQuiet() {
        AlertPolicy.setQuiet(this, quiet)
        renderQuiet()
    }

    private fun renderQuiet() {
        quietSwitch.isChecked = quiet.enabled
        quietStart.text = getString(R.string.settings_quiet_start, quiet.startLabel())
        quietEnd.text = getString(R.string.settings_quiet_end, quiet.endLabel())
        // 关闭时禁用时间按钮：让人以为“关了还能改时间”会带来一次无效操作
        quietStart.isEnabled = quiet.enabled
        quietEnd.isEnabled = quiet.enabled
        quietHint.text = when {
            !quiet.enabled -> getString(R.string.settings_quiet_hint_off)
            quiet.crossesMidnight -> getString(
                R.string.settings_quiet_hint_cross, quiet.startLabel(), quiet.endLabel()
            )
            else -> getString(R.string.settings_quiet_hint_on, quiet.startLabel(), quiet.endLabel())
        }
    }

    /** 统一用 24 小时制选择器，避免和系统 12 小时制混在一起让用户误判 22:00 与 10:00 */
    private fun pickTime(initialMinuteOfDay: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            initialMinuteOfDay / 60,
            initialMinuteOfDay % 60,
            true,
        ).show()
    }

    private fun renderLevel() {
        val id = when (AlertPolicy.minLevel(this)) {
            AlertLevel.INFO -> R.id.settings_level_all
            AlertLevel.URGENT -> R.id.settings_level_urgent
            AlertLevel.NORMAL -> R.id.settings_level_normal
        }
        levelGroup.check(id)
    }

    private fun levelOf(checkedId: Int): AlertLevel = when (checkedId) {
        R.id.settings_level_all -> AlertLevel.INFO
        R.id.settings_level_urgent -> AlertLevel.URGENT
        else -> AlertLevel.NORMAL
    }

    private fun runDiagnostics() {
        checks = Diagnostics.run(this)
        resultContainer.removeAllViews()
        checks.forEach { resultContainer.addView(checkRow(it)) }
        saveSummary()
    }

    private fun checkRow(check: Diagnostics.Check): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = getString(R.string.settings_diag_line, check.label, check.detail)
            setTextColor(statusColor(check.status))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (check.target != Diagnostics.Target.NONE) {
            row.addView(Button(this).apply {
                text = getString(R.string.settings_diag_go)
                setOnClickListener { openTarget(check.target) }
            })
        }
        return row
    }

    private fun statusColor(status: Diagnostics.Status): Int = when (status) {
        Diagnostics.Status.PASS -> getColor(R.color.qs_ok)
        Diagnostics.Status.WARN -> getColor(R.color.qs_warn)
        Diagnostics.Status.BLOCK -> getColor(R.color.qs_danger)
    }

    private fun saveSummary() {
        val passed = checks.count { it.status == Diagnostics.Status.PASS }
        val warn = checks.count { it.status == Diagnostics.Status.WARN }
        val block = checks.count { it.status == Diagnostics.Status.BLOCK }
        getSharedPreferences(DIAG_PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_DIAG_AT, System.currentTimeMillis())
            .putInt(KEY_DIAG_PASS, passed)
            .putInt(KEY_DIAG_WARN, warn)
            .putInt(KEY_DIAG_BLOCK, block)
            .apply()
        renderLastSummary()
    }

    private fun renderLastSummary() {
        val prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE)
        val at = prefs.getLong(KEY_DIAG_AT, 0L)
        summary.text = if (at <= 0L) {
            getString(R.string.settings_diag_never)
        } else {
            getString(
                R.string.settings_diag_summary,
                SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at)),
                prefs.getInt(KEY_DIAG_PASS, 0),
                prefs.getInt(KEY_DIAG_WARN, 0),
                prefs.getInt(KEY_DIAG_BLOCK, 0),
            )
        }
    }

    private fun openTarget(target: Diagnostics.Target) {
        val intent = when (target) {
            Diagnostics.Target.APP_SETTINGS -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            Diagnostics.Target.NOTIFICATION -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            Diagnostics.Target.BATTERY -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            // 自启动白名单没有统一的系统页面，只能落到应用详情页让用户按 ROM 的入口自行找
            Diagnostics.Target.AUTO_START -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            Diagnostics.Target.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            Diagnostics.Target.NONE -> return
        }
        // 部分 ROM 会裁剪这些系统页面，跳转失败不能让设置页崩掉
        runCatching { startActivity(intent) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DIAG_PREFS = "diag_prefs"
        const val KEY_DIAG_AT = "last_at"
        const val KEY_DIAG_PASS = "pass"
        const val KEY_DIAG_WARN = "warn"
        const val KEY_DIAG_BLOCK = "block"
    }
}
