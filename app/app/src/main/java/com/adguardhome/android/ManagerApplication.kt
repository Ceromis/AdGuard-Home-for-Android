package com.adguardhome.android

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class ManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleControlClient.init(this)
        // 旧版暂停倒计时服务已移除,清理遗留的通知渠道。
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .deleteNotificationChannel("protection_pause_countdown")
        }
        val enabled = getSharedPreferences("theme_config", 0).getBoolean("gesture", false)
        setPredictiveBack(applicationInfo, enabled)
    }

    companion object {
        fun setPredictiveBack(info: ApplicationInfo, enabled: Boolean) {
            if (Build.VERSION.SDK_INT < 34) return
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback"
                )
                ApplicationInfo::class.java
                    .getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(info, enabled)
            }
        }
    }
}
