package com.quietsentry.app

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
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
    private lateinit var pinSwitch: SwitchCompat
    private lateinit var pinChange: Button
    private lateinit var maskButton: Button

    private var quiet = AlertPolicy.Quiet(false, 0, 0)
    private var checks: List<Diagnostics.Check> = emptyList()

    /** 程序化回填开关同样会触发监听器，用它区分"用户操作"与"渲染状态" */
    private var renderingPin = false

    /** 降低保护的动作已在本页确认过本人，会话内不重复弹 PIN */
    private var pinVerified = false

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

    private val createAuditDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(AuditLog.exportText(this).toByteArray())
            } ?: error("no stream")
        }.isSuccess
        if (ok) AuditLog.record(this, AuditLog.EXPORT, "导出审计日志")
        Toast.makeText(
            this,
            if (ok) R.string.audit_export_saved else R.string.audit_export_failed,
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
        pinSwitch = findViewById(R.id.settings_pin_switch)
        pinChange = findViewById(R.id.settings_pin_change)
        maskButton = findViewById(R.id.settings_mask)

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
        bindPrivacy()
    }

    private fun bindPrivacy() {
        renderPin()
        renderMask()
        pinSwitch.setOnCheckedChangeListener { _, checked ->
            if (renderingPin) return@setOnCheckedChangeListener
            if (checked) requestSetPin() else requestClearPin()
        }
        pinChange.setOnClickListener { ensureUnlocked { requestSetPin() } }
        maskButton.setOnClickListener { showMaskChooser() }
        findViewById<Button>(R.id.settings_audit_open).setOnClickListener { showAudit() }
    }

    private fun renderPin() {
        val hasPin = PrivacyGuard.hasPin(this)
        renderingPin = true
        pinSwitch.isChecked = hasPin
        renderingPin = false
        // 没有 PIN 时“修改”没有对象，禁用比点了再报错更省一次操作
        pinChange.isEnabled = hasPin
    }

    /**
     * 关闭或修改应用锁都只会降低保护强度，必须确认是本人操作。
     * 校验放在动作之前而不是页面创建时：只是看看设置不该被反复挡在门外。
     */
    private fun ensureUnlocked(onOk: () -> Unit) {
        if (pinVerified || PrivacyGuard.allow(this)) {
            pinVerified = true
            onOk()
            return
        }
        PrivacyGuard.prompt(this) { ok ->
            if (ok) {
                pinVerified = true
                onOk()
            } else {
                // 取消或输错都要按真实存储状态回填，否则开关停在“开”而实际并未开启
                renderPin()
            }
        }
    }

    private fun requestSetPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_set_title)
            .setMessage(R.string.pin_set_hint)
            .setView(input)
            .setPositiveButton(R.string.pin_ok) { _, _ -> applyPin(input.text.toString()) }
            .setNegativeButton(R.string.pin_cancel) { _, _ -> renderPin() }
            // 返回键关闭也要回填，否则开关停在“开”，用户会以为 PIN 已经设成
            .setOnCancelListener { renderPin() }
            .show()
    }

    private fun applyPin(pin: String) {
        val ok = PrivacyGuard.setPin(this, pin)
        Toast.makeText(
            this,
            if (ok) R.string.pin_set_done else R.string.pin_format_invalid,
            Toast.LENGTH_SHORT
        ).show()
        if (ok) pinVerified = true
        renderPin()
    }

    private fun requestClearPin() = ensureUnlocked {
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_clear_title)
            .setMessage(R.string.pin_clear_message)
            .setPositiveButton(R.string.pin_ok) { _, _ ->
                PrivacyGuard.clearPin(this)
                Toast.makeText(this, R.string.pin_cleared, Toast.LENGTH_SHORT).show()
                renderPin()
            }
            .setNegativeButton(R.string.pin_cancel) { _, _ -> renderPin() }
            .setOnCancelListener { renderPin() }
            .show()
    }

    private fun showMaskChooser() {
        val modes = PrivacyGuard.MaskMode.entries
        val labels = modes.map { getString(it.labelRes) }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_mask)
            .setSingleChoiceItems(
                labels.toTypedArray(),
                modes.indexOf(PrivacyGuard.maskMode(this))
            ) { dialog, which ->
                PrivacyGuard.setMaskMode(this, modes[which])
                renderMask()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.pin_cancel, null)
            .show()
    }

    private fun renderMask() {
        val label = getString(PrivacyGuard.maskMode(this).labelRes)
        maskButton.text = getString(R.string.settings_mask_value, label)
    }

    private fun showAudit() {
        val entries = AuditLog.recent(this, AUDIT_LIMIT)
        val body = if (entries.isEmpty()) {
            getString(R.string.audit_empty)
        } else {
            val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            entries.joinToString("\n") {
                "${format.format(Date(it.at))} [${AuditLog.typeLabel(it.type)}] ${it.detail}"
            }
        }
        // 上百条记录会超出对话框可视高度，套一层滚动容器，否则只能看到开头几条
        val text = TextView(this).apply {
            this.text = body
            textSize = 13f
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.audit_title)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.pin_ok, null)
            .setNeutralButton(R.string.settings_audit_export) { _, _ ->
                createAuditDocument.launch("quietsentry-audit.txt")
            }
            .show()
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

        /** 审计页一次最多拉 100 条：再多也没人逐条读完，徒增滚动与拼串成本 */
        const val AUDIT_LIMIT = 100
    }
}
