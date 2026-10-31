package dev.idadroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.idadroid.R

/**
 * V5: 保活服务升级 — 静态通知 → 动态进度通知 + WakeLock。
 *
 * 旧版问题(净屏项目血泪教训 "一会就黑了"):
 * - 息屏后 CPU/网络休眠, agent 长任务(流式输出/工具执行/大文件传输)被冻结
 * - 用户看不到 agent 在干什么, 只有一个死通知
 *
 * V5:
 * - [beginTurn]/[updateProgress]/[endTurn] 由 PiAgentManager 事件驱动,
 *   通知实时显示"流式输出中/执行工具 xxx/上下文压缩"等状态
 * - turn 进行期间持有 PARTIAL_WAKE_LOCK(CPU 不睡), turn 结束释放
 * - 通知常驻(手动开关语义保留), turn 状态叠加其上
 */
class KeepAliveService : Service() {
    companion object {
        const val CHANNEL_ID = "idadroid_keep_alive"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_BEGIN_TURN = "dev.idadroid.action.BEGIN_TURN"
        const val ACTION_UPDATE_PROGRESS = "dev.idadroid.action.UPDATE_PROGRESS"
        const val ACTION_END_TURN = "dev.idadroid.action.END_TURN"
        const val EXTRA_TEXT = "text"

        @Volatile private var turnActive = false

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.keep_alive_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.keep_alive_channel_description)
                    setShowBadge(false)
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
        }

        /** turn 开始 — 拉起前台服务 + WakeLock */
        fun beginTurn(context: Context, initialText: String = "正在连接模型…") {
            ensureChannel(context)
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_BEGIN_TURN
                putExtra(EXTRA_TEXT, initialText)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /** turn 进行中 — 更新通知进度文本(轻量, 不触碰服务) */
        fun updateProgress(context: Context, text: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (turnActive) {
                nm.notify(NOTIFICATION_ID, buildNotification(context, text))
            }
        }

        /** turn 结束 — 释放 WakeLock, 通知回落静态文案 */
        fun endTurn(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_END_TURN
            }
            context.startService(intent)
        }

        private fun buildNotification(context: Context, text: String): android.app.Notification {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pending = launchIntent?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_idadroid)
                .setContentTitle(context.getString(R.string.keep_alive_notification_title))
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(pending)
                .build()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val action = intent?.action
        val text = intent?.getStringExtra(EXTRA_TEXT)

        when (action) {
            ACTION_BEGIN_TURN -> {
                turnActive = true
                acquireWakeLock()
                startForegroundCompat(buildNotification(this, text ?: "正在连接模型…"))
            }
            ACTION_UPDATE_PROGRESS -> {
                if (turnActive) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(this, text ?: ""))
                }
            }
            ACTION_END_TURN -> {
                turnActive = false
                releaseWakeLock()
                startForegroundCompat(buildNotification(this, getString(R.string.keep_alive_notification_text)))
            }
            else -> {
                // 默认启动(手动开关) — 静态通知
                startForegroundCompat(buildNotification(this, getString(R.string.keep_alive_notification_text)))
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "idadroid:agent_turn").apply {
            setReferenceCounted(false)
            acquire(35 * 60 * 1000L) // 35 分钟上限(超过 TURN_HARD_LIMIT 30min + 余量), 防泄漏
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        turnActive = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
