package com.adguardhome.android.liveisland

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import com.adguardhome.android.ApiClient
import com.adguardhome.android.ModuleControlClient
import java.io.IOException
import java.time.Instant
import org.json.JSONObject

/** Controls when the top capsule is shown next to the camera punch hole. */
enum class LiveIslandVisibility {
    ALWAYS,
    PAUSE_ONLY,
    STATUS_BAR_ONLY,
}

@Stable
data class LiveIslandSettings(
    val enabled: Boolean = false,
    val autoStart: Boolean = true,
    val visibility: LiveIslandVisibility = LiveIslandVisibility.ALWAYS,
    val followTheme: Boolean = true,
    val compact: Boolean = false,
    val showQueries: Boolean = true,
    val showBlocked: Boolean = true,
    val showRate: Boolean = true,
    val showActions: Boolean = true,
)

object LiveIslandPrefs {
    const val NAME = "live_island"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_VISIBILITY = "visibility"
    private const val KEY_FOLLOW_THEME = "follow_theme"
    private const val KEY_COMPACT = "compact"
    private const val KEY_QUERIES = "queries"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_RATE = "rate"
    private const val KEY_ACTIONS = "actions"

    fun load(context: Context): LiveIslandSettings {
        val prefs = prefs(context)
        return LiveIslandSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            autoStart = prefs.getBoolean(KEY_AUTO_START, true),
            visibility = runCatching {
                LiveIslandVisibility.valueOf(
                    prefs.getString(KEY_VISIBILITY, LiveIslandVisibility.ALWAYS.name).orEmpty(),
                )
            }.getOrDefault(LiveIslandVisibility.ALWAYS),
            followTheme = prefs.getBoolean(KEY_FOLLOW_THEME, true),
            compact = prefs.getBoolean(KEY_COMPACT, false),
            showQueries = prefs.getBoolean(KEY_QUERIES, true),
            showBlocked = prefs.getBoolean(KEY_BLOCKED, true),
            showRate = prefs.getBoolean(KEY_RATE, true),
            showActions = prefs.getBoolean(KEY_ACTIONS, true),
        )
    }

    fun save(context: Context, value: LiveIslandSettings) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putBoolean(KEY_AUTO_START, value.autoStart)
            .putString(KEY_VISIBILITY, value.visibility.name)
            .putBoolean(KEY_FOLLOW_THEME, value.followTheme)
            .putBoolean(KEY_COMPACT, value.compact)
            .putBoolean(KEY_QUERIES, value.showQueries)
            .putBoolean(KEY_BLOCKED, value.showBlocked)
            .putBoolean(KEY_RATE, value.showRate)
            .putBoolean(KEY_ACTIONS, value.showActions)
            .apply()
    }

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

@Stable
data class LiveIslandSnapshot(
    val loading: Boolean = true,
    val running: Boolean = false,
    val protection: Boolean = true,
    val total: Long = 0L,
    val blocked: Long = 0L,
    val pauseUntil: Long = 0L,
) {
    val rate: Double get() = if (total == 0L) 0.0 else blocked * 100.0 / total

    companion object {
        // 读取失败时抛出异常,由调用方保留上一次可用状态;
        // 只有控制桥明确返回 stopped 才判定服务停止,避免瞬时抖动显示"已停止"。
        fun load(): LiveIslandSnapshot {
            val control = ModuleControlClient()
            val statusResult = control.execute("status")
            if (statusResult.code != 0) throw IOException("模块控制桥状态码 ${statusResult.code}")
            val output = statusResult.output.lowercase()
            if (output.contains("stopped")) return LiveIslandSnapshot(loading = false, running = false)
            if (!output.contains("running")) throw IOException("模块状态未知")
            val api = ApiClient.getInstance()
            val status = api.get("/control/status")
            val stats = api.get("/control/stats")
            return LiveIslandSnapshot(
                loading = false,
                running = true,
                protection = status.optBoolean("protection_enabled"),
                total = stats.optLong("num_dns_queries"),
                blocked = stats.optLong("num_blocked_filtering"),
                pauseUntil = resolvePauseDeadline(status),
            )
        }

        private fun resolvePauseDeadline(status: JSONObject): Long {
            if (status.optBoolean("protection_enabled")) return 0L
            val now = System.currentTimeMillis()
            for (key in listOf("protection_disabled_until", "protection_until", "disabled_until")) {
                val raw = status.opt(key)
                val parsed = when (raw) {
                    is Number -> raw.toLong().let { if (it in 1 until 10_000_000_000L) it * 1_000L else it }
                    is String -> runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
                    else -> null
                }
                if (parsed != null && parsed > now) return parsed
            }
            val rawDuration = status.optLong("protection_disabled_duration", 0L)
            val durationMillis = when {
                rawDuration <= 0L -> 0L
                rawDuration > 365L * 24L * 60L * 60L * 1_000L -> rawDuration / 1_000_000L
                else -> rawDuration
            }
            return if (durationMillis > 0L) {
                now + durationMillis.coerceAtMost(Long.MAX_VALUE - now)
            } else {
                Long.MAX_VALUE
            }
        }
    }
}

/** Actions carried from the capsule UI into the overlay service. */
sealed class LiveIslandAction {
    object OpenManager : LiveIslandAction()
    object Dismiss : LiveIslandAction()
    object ToggleProtection : LiveIslandAction()
    data class SetProtection(val enabled: Boolean, val duration: Long = 0L) : LiveIslandAction()
    object StartService : LiveIslandAction()
    object StopService : LiveIslandAction()
    object RestartService : LiveIslandAction()
}
