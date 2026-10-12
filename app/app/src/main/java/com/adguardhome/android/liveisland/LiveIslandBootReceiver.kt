package com.adguardhome.android.liveisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the live island overlay after reboot when auto start is enabled. */
class LiveIslandBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = LiveIslandPrefs.load(context)
        if (settings.enabled && settings.autoStart) {
            runCatching { LiveIslandService.start(context) }
        }
    }
}
