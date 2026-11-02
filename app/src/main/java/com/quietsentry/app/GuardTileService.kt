package com.quietsentry.app

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * 快捷设置磁贴（PRD 竞品表 P2，lenscast 依据）：下拉一键开始/停止值守。
 *
 * 为什么不在这里复刻 MainActivity 的全部前置流程：磁贴环境里既不能申请运行时权限，
 * 也不能弹系统对话框（电池豁免引导）；这两件事交回主界面完成。
 * 磁贴只在“同意已确认 + 相机权限已授”的前提下直接拉起服务，否则打开主界面。
 */
class GuardTileService : TileService() {

    override fun onStartListening() {
        // 下拉面板每次展开都会走到这里：以服务真实状态为准，校正上次点击时的乐观渲染
        render()
    }

    override fun onClick() {
        if (!ConsentStore.isAccepted(this) || !hasCameraPermission()) {
            openMain()
            return
        }
        val intent = Intent(this, CaptureService::class.java)
        if (CaptureService.isRunning) {
            // 停止不需要前台启动约束：服务已在运行，普通 startService 只是在投递指令
            startService(intent.setAction(CaptureService.ACTION_STOP))
        } else {
            ContextCompat.startForegroundService(this, intent.setAction(CaptureService.ACTION_START))
        }
        // 按目标状态立即渲染：服务启停是异步的，等真实状态翻转再刷新会让磁贴看起来没反应；
        // 下次展开面板时 onStartListening 会用真实状态校正
        qsTile?.state = if (CaptureService.isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        qsTile?.updateTile()
    }

    private fun render() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_guard)
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher)
        tile.state = if (CaptureService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Android 14 起要求 PendingIntent 形式；旧版的重载只在语义上被标记过时 */
    private fun openMain() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
