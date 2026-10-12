package com.adguardhome.android.liveisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.adguardhome.android.ApiClient
import com.adguardhome.android.ManagerActivity
import com.adguardhome.android.ModuleControlClient
import com.adguardhome.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * 纯系统灵动岛：常驻前台服务持续投递 promoted ongoing 通知，
 * 由 ColorOS 16 / HyperOS 2.0（澎湃 OS）/ Android 16 的 Live Updates（流体云）渲染顶部胶囊，不绘制任何悬浮窗。
 */
class LiveIslandService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val snapshotFlow = MutableStateFlow(LiveIslandSnapshot())
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadSettings() }
    private var settings = LiveIslandSettings()
    private var lastStatusNotify = 0L
    private var autoResumeInFlight = false
    private var autoResumeDeadline = 0L
    private var autoResumeFailures = 0
    private var lastAutoResumeAttempt = 0L

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            registerPrefsListener()
            startRefreshLoop()
        } catch (error: Throwable) {
            Log.e(TAG, "Live island startup failed", error)
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                performAction(LiveIslandAction.SetProtection(true, 0L))
                return START_STICKY
            }
            ACTION_PAUSE -> {
                performAction(LiveIslandAction.SetProtection(false, intent.getLongExtra(EXTRA_DURATION, 0L)))
                return START_STICKY
            }
            ACTION_RESTART -> {
                performAction(LiveIslandAction.RestartService)
                return START_STICKY
            }
            ACTION_START_SERVICE -> {
                performAction(LiveIslandAction.StartService)
                return START_STICKY
            }
        }
        reloadSettings()
        return START_STICKY
    }

    override fun onDestroy() {
        LiveIslandPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerPrefsListener() {
        LiveIslandPrefs.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun reloadSettings() {
        settings = LiveIslandPrefs.load(this)
        if (!settings.enabled) {
            stopSelf()
            return
        }
        // 开关切换后立刻重建通知,不等下一次数据变化。
        lastStatusNotify = 0L
        updateStatusNotification()
    }

    private fun startRefreshLoop() {
        serviceScope.launch {
            var refreshCount = 0L
            while (isActive) {
                try {
                    val next = withContext(Dispatchers.IO) { runCatching { LiveIslandSnapshot.load() } }.getOrNull()
                    if (next != null) {
                        val changed = next != snapshotFlow.value
                        snapshotFlow.value = next
                        maybeResumeExpiredPause(next)
                        if (changed || refreshCount % 5L == 0L) updateStatusNotification()
                    }
                    refreshCount += 1
                } catch (error: Throwable) {
                    Log.e(TAG, "Live island refresh failed", error)
                }
                delay(1_000L)
            }
        }
    }

    private fun updateStatusNotification() {
        val now = System.currentTimeMillis()
        if (now - lastStatusNotify < 1_000L) return
        lastStatusNotify = now
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    // AGH 服务端会按时恢复定时暂停；这里再做一层兜底,只处理已到期的定时暂停。
    private fun maybeResumeExpiredPause(snapshot: LiveIslandSnapshot) {
        if (!snapshot.running || snapshot.protection) {
            autoResumeDeadline = 0L
            autoResumeFailures = 0
            return
        }
        val deadline = snapshot.pauseUntil
        if (deadline !in 1 until Long.MAX_VALUE) return
        if (System.currentTimeMillis() < deadline) return
        if (autoResumeDeadline != deadline) {
            autoResumeDeadline = deadline
            autoResumeFailures = 0
        }
        if (autoResumeInFlight || autoResumeFailures >= 4) return
        val now = System.currentTimeMillis()
        if (now - lastAutoResumeAttempt < 5_000L) return
        lastAutoResumeAttempt = now
        autoResumeInFlight = true
        serviceScope.launch {
            val resumed = runCatching {
                withContext(Dispatchers.IO) {
                    ApiClient.getInstance().post(
                        "/control/protection",
                        JSONObject().put("enabled", true).put("duration", 0L),
                    )
                }
            }
            autoResumeInFlight = false
            if (resumed.isSuccess) {
                autoResumeFailures = 0
                runCatching { LiveIslandSnapshot.load() }.onSuccess { snapshotFlow.value = it }
                updateStatusNotification()
            } else {
                autoResumeFailures += 1
                Log.w(TAG, "auto resume after pause failed (${autoResumeFailures}/4)", resumed.exceptionOrNull())
            }
        }
    }

    private fun performAction(action: LiveIslandAction) {
        if (action is LiveIslandAction.OpenManager) {
            openManager()
            return
        }
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) { runAction(action) }
                // 先乐观更新,避免恢复/暂停瞬间因读取抖动闪出错误状态。
                if (action is LiveIslandAction.SetProtection) {
                    snapshotFlow.value = if (action.enabled) {
                        snapshotFlow.value.copy(protection = true, pauseUntil = 0L)
                    } else {
                        snapshotFlow.value.copy(
                            protection = false,
                            pauseUntil = if (action.duration > 0L) {
                                System.currentTimeMillis() + action.duration
                            } else {
                                Long.MAX_VALUE
                            },
                        )
                    }
                }
                val refreshed = withContext(Dispatchers.IO) { runCatching { LiveIslandSnapshot.load() } }.getOrNull()
                if (refreshed != null) snapshotFlow.value = refreshed
            } catch (error: Throwable) {
                Log.e(TAG, "Live island action failed", error)
            }
            updateStatusNotification()
        }
    }

    private fun runAction(action: LiveIslandAction) {
        when (action) {
            is LiveIslandAction.SetProtection -> ApiClient.getInstance().post(
                "/control/protection",
                JSONObject().put("enabled", action.enabled).put("duration", action.duration),
            )
            LiveIslandAction.ToggleProtection -> ApiClient.getInstance().post(
                "/control/protection",
                JSONObject().put("enabled", !snapshotFlow.value.protection).put("duration", 0L),
            )
            LiveIslandAction.StartService -> ModuleControlClient().execute("start")
            LiveIslandAction.StopService -> ModuleControlClient().execute("stop")
            LiveIslandAction.RestartService -> ModuleControlClient().execute("restart")
            else -> Unit
        }
    }

    private fun openManager() {
        val intent = Intent(this, ManagerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "灵动岛",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "顶部胶囊显示 AdGuard Home 过滤状态"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ManagerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snapshot = snapshotFlow.value
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("AdGuard Home")
            .setContentText(statusText(snapshot))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis())
        if (!snapshot.protection && snapshot.pauseUntil != Long.MAX_VALUE) {
            builder.setWhen(snapshot.pauseUntil)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        if (settings.showActions) {
            for (action in buildActions(open, snapshot)) builder.addAction(action)
        }
        requestPromotedCapsule(builder, snapshot)
        return builder.build()
    }

    private fun buildActions(open: PendingIntent, snapshot: LiveIslandSnapshot): Array<Notification.Action> {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun serviceAction(requestCode: Int, action: String, extra: Long? = null): PendingIntent {
            val intent = Intent(this, LiveIslandService::class.java).setAction(action)
            if (extra != null) intent.putExtra(EXTRA_DURATION, extra)
            return PendingIntent.getService(this, requestCode, intent, flags)
        }
        val actions = ArrayList<Notification.Action>()
        if (!snapshot.protection && snapshot.pauseUntil != Long.MAX_VALUE) {
            actions.add(Notification.Action.Builder(null, "立即恢复", serviceAction(1, ACTION_RESUME)).build())
        }
        if (snapshot.running && snapshot.protection) {
            var requestCode = 2
            for ((label, duration) in listOf("5 分钟" to 300_000L, "30 分钟" to 1_800_000L, "1 小时" to 3_600_000L)) {
                actions.add(
                    Notification.Action.Builder(null, label, serviceAction(requestCode++, ACTION_PAUSE, duration)).build(),
                )
            }
        }
        if (snapshot.running) {
            actions.add(Notification.Action.Builder(null, "重启服务", serviceAction(10, ACTION_RESTART)).build())
        } else {
            actions.add(Notification.Action.Builder(null, "启动服务", serviceAction(10, ACTION_START_SERVICE)).build())
        }
        actions.add(Notification.Action.Builder(null, "打开管理器", open).build())
        return actions.toTypedArray()
    }

    private fun requestPromotedCapsule(builder: Notification.Builder, snapshot: LiveIslandSnapshot) {
        // Live Updates 是 Android 16 (API 36) 的系统能力，旧系统退回普通常驻通知。
        if (Build.VERSION.SDK_INT < 36) return
        val promoted = shouldPromote(snapshot)
        runCatching {
            Notification.Builder::class.java
                .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, promoted)
            if (promoted) {
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", CharSequence::class.java)
                    .invoke(builder, shortText(snapshot))
            }
        }
    }

    private fun shouldPromote(snapshot: LiveIslandSnapshot): Boolean = when (settings.visibility) {
        LiveIslandVisibility.ALWAYS -> true
        LiveIslandVisibility.PAUSE_ONLY -> !snapshot.running || !snapshot.protection
        // 仅状态栏信息:保留常驻通知与状态栏图标,但不请求顶部胶囊。
        LiveIslandVisibility.STATUS_BAR_ONLY -> false
    }

    private fun shortText(snapshot: LiveIslandSnapshot): String {
        if (!snapshot.protection && snapshot.pauseUntil != Long.MAX_VALUE) {
            return formatRemaining(snapshot.pauseUntil - System.currentTimeMillis())
        }
        val parts = ArrayList<String>()
        if (settings.showQueries) parts.add(formatCount(snapshot.total))
        if (settings.showBlocked) parts.add("${formatCount(snapshot.blocked)} 拦截")
        if (settings.showRate) parts.add("${snapshot.rate.toInt()}%")
        return parts.joinToString(" · ").ifBlank { "AdGuard Home" }
    }

    private fun statusText(snapshot: LiveIslandSnapshot): String = when {
        snapshot.loading -> "正在连接本地模块"
        !snapshot.running -> "AdGuard Home 已停止"
        !snapshot.protection && snapshot.pauseUntil != Long.MAX_VALUE ->
            "已暂停 · 剩余 ${formatRemaining(snapshot.pauseUntil - System.currentTimeMillis())}"
        !snapshot.protection -> "已暂停 · 等待手动恢复"
        else -> {
            val metrics = metricsText(snapshot)
            if (metrics.isBlank()) "保护中" else "保护中 · $metrics"
        }
    }

    // 通知正文与状态栏胶囊共用同一组开关,避免"关掉查询数后正文还显示"。
    private fun metricsText(snapshot: LiveIslandSnapshot): String {
        val parts = ArrayList<String>()
        if (settings.showQueries) parts.add("${formatCount(snapshot.total)} 查询")
        if (settings.showBlocked) parts.add("${formatCount(snapshot.blocked)} 拦截")
        if (settings.showRate) parts.add("${snapshot.rate.toInt()}%")
        return parts.joinToString(" · ")
    }

    companion object {
        private const val TAG = "LiveIsland"
        private const val CHANNEL_ID = "live_island"
        private const val NOTIFICATION_ID = 4401
        private const val ACTION_STOP = "com.adguardhome.android.liveisland.action.STOP"
        private const val ACTION_RESUME = "com.adguardhome.android.liveisland.action.RESUME_PROTECTION"
        private const val ACTION_PAUSE = "com.adguardhome.android.liveisland.action.PAUSE_PROTECTION"
        private const val ACTION_RESTART = "com.adguardhome.android.liveisland.action.RESTART"
        private const val ACTION_START_SERVICE = "com.adguardhome.android.liveisland.action.START_SERVICE"
        private const val EXTRA_DURATION = "duration"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, LiveIslandService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveIslandService::class.java).setAction(ACTION_STOP))
        }

        fun evaluateStartup(context: Context) {
            val settings = LiveIslandPrefs.load(context)
            if (settings.enabled && settings.autoStart) {
                start(context)
            }
        }
    }
}

private fun formatCount(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

private fun formatRemaining(millis: Long): String {
    val totalSeconds = ((millis + 999L) / 1_000L).coerceAtLeast(1L)
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}