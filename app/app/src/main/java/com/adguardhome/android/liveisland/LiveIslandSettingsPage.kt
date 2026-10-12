package com.adguardhome.android.liveisland

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adguardhome.android.ui.LocalThemeSettings
import com.adguardhome.android.ui.UiStyle

@Composable
fun LiveIslandSettingsPage() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(LiveIslandPrefs.load(context)) }

    LaunchedEffect(settings.enabled) {
        if (settings.enabled) {
            LiveIslandService.start(context)
        }
    }

    fun update(next: LiveIslandSettings) {
        settings = next
        LiveIslandPrefs.save(context, next)
        if (next.enabled) {
            LiveIslandService.start(context)
        } else {
            LiveIslandService.stop(context)
        }
    }

    SettingsColumn {
        item {
            Text(
                "ColorOS 16 / HyperOS 2.0（澎湃 OS）原生胶囊，由系统流体云渲染",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            ManagerCardLocal("效果预览", "点击胶囊展开收起，实际样式由系统渲染。") {
                LiveIslandPreview(settings)
            }
        }
        item {
            SwitchCardLocal("启用灵动岛", "在屏幕顶部胶囊显示过滤状态", settings.enabled) {
                update(settings.copy(enabled = it))
            }
        }
        item {
            ManagerCardLocal("显示时机") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LiveIslandVisibility.entries.forEachIndexed { index, visibility ->
                        SegmentedButton(
                            selected = settings.visibility == visibility,
                            onClick = { update(settings.copy(visibility = visibility)) },
                            shape = SegmentedButtonDefaults.itemShape(index, LiveIslandVisibility.entries.size),
                            label = {
                                Text(
                                    when (visibility) {
                                        LiveIslandVisibility.ALWAYS -> "始终显示"
                                        LiveIslandVisibility.PAUSE_ONLY -> "暂停时显示"
                                        LiveIslandVisibility.STATUS_BAR_ONLY -> "仅状态栏信息"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
                if (settings.visibility == LiveIslandVisibility.STATUS_BAR_ONLY) {
                    Text(
                        "不显示顶部胶囊，仅在状态栏保留运行信息。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            SwitchCardLocal("开机后自动启动", "重启设备后自动恢复顶部胶囊", settings.autoStart) {
                update(settings.copy(autoStart = it))
            }
        }
        item {
            SwitchCardLocal("显示快捷操作", "展开时显示暂停、恢复和服务控制", settings.showActions) {
                update(settings.copy(showActions = it))
            }
        }
        item {
            ManagerCardLocal("胶囊信息") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("查询数", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(settings.showQueries, { update(settings.copy(showQueries = it)) })
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("拦截数", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(settings.showBlocked, { update(settings.copy(showBlocked = it)) })
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("拦截率", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(settings.showRate, { update(settings.copy(showRate = it)) })
                }
            }
        }
        item {
            ManagerCardLocal("说明") {
                Text(
                    "需要 ColorOS 16 / HyperOS 2.0（澎湃 OS）及以上系统。Android 16 以下会退回普通常驻通知；暂停保护时胶囊会显示倒计时。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsColumn(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    // Miuix 子页面标题栏是 48dp 浮层,这里留出标题栏 + 间距,避免首行文字被遮住。
    val headerInset = if (LocalThemeSettings.current.value.style == UiStyle.MIUIX) 64.dp else 16.dp
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = headerInset + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            end = 16.dp,
            bottom = 112.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun ManagerCardLocal(title: String, text: String = "", content: (@Composable () -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (text.isNotEmpty()) {
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content?.invoke()
        }
    }
}

@Composable
private fun SwitchCardLocal(
    title: String,
    text: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    ManagerCardLocal(title) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text,
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked, onChecked)
        }
    }
}