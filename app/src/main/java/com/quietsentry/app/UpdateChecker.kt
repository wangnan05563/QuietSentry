package com.quietsentry.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 版本与在线更新（PRD FR-17）。
 *
 * 不建自有更新服务：版本清单放在项目自带的 GitHub Releases，请求的是公开 API。
 * 这样既满足“自动检测内置仓库版本”，又不引入需要长期维护的服务器与账号体系。
 * 检查失败一律不阻塞设置页，离线用户仍然能用全部本地功能。
 */
object UpdateChecker {

    data class Info(val versionName: String, val versionCode: Long)

    data class Release(
        val version: String,
        val pageUrl: String,
        val notes: String,
        val apkUrl: String?,
    )

    /** 当前构建未内置仓库地址：调用方据此区分“没配置”和“网络失败” */
    class NoRepoException : IllegalStateException("no repository configured")

    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "update-check").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun current(context: Context): Info {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return Info(info.versionName ?: "", code)
    }

    /**
     * 查询内置仓库的最新 Release；结果在主线程回调。
     * 仓库未发布任何 Release 时返回 success(null)，按“已是最新”处理。
     */
    fun check(callback: (Result<Release?>) -> Unit) {
        val slug = BuildConfig.GIT_REPO
        if (slug.isBlank()) {
            callback(Result.failure(NoRepoException()))
            return
        }
        io.execute {
            val result = runCatching { fetchLatest(slug) }
            main.post { callback(result) }
        }
    }

    /** 按 0.1.0-YYYYMMDD.N 的版本格式逐段比较，patch 日期与序号都参与判断 */
    fun isNewer(current: String, remote: String): Boolean {
        fun segments(value: String): List<Int> =
            value.removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }
        val left = segments(current)
        val right = segments(remote)
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return b > a
        }
        return false
    }

    fun openPage(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    private fun fetchLatest(slug: String): Release? {
        val connection = URL("https://api.github.com/repos/$slug/releases/latest")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "QuietSentry")
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            if (tag.isBlank()) return null
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            return Release(
                version = tag,
                pageUrl = json.optString("html_url"),
                notes = json.optString("body"),
                apkUrl = apkUrl,
            )
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 8000
}
