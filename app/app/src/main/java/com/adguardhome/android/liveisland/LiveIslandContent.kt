package com.adguardhome.android.liveisland

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adguardhome.android.R
import com.adguardhome.android.ui.managerColorScheme
import com.adguardhome.android.ui.readThemeSettings
import java.util.Locale
import kotlin.math.abs

/** Compose root used by the floating overlay service. */
@Composable
fun LiveIslandOverlayContent(
    settings: LiveIslandSettings,
    snapshot: LiveIslandSnapshot,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAction: (LiveIslandAction) -> Unit,
) {
    val context = LocalContext.current
    val theme = readThemeSettings(context)
    val colors = if (settings.followTheme) {
        managerColorScheme(theme, theme.dark)
    } else {
        darkColorScheme(
            primary = Color(0xFF82B9FF),
            onPrimary = Color(0xFF002F55),
            surface = Color(0xFF17181C),
            onSurface = Color(0xFFF2F3F5),
            background = Color(0xFF17181C),
            onBackground = Color(0xFFF2F3F5),
            surfaceContainer = Color(0xFF1D1F24),
            surfaceContainerHigh = Color(0xFF24262C),
            surfaceContainerHighest = Color(0xFF2A2D34),
            surfaceVariant = Color(0xFF3B3E45),
            onSurfaceVariant = Color(0xFFD2D5DA),
            outline = Color(0xFF9AA0A6),
            surfaceTint = Color.Transparent,
            error = Color(0xFFFFB4AB),
            errorContainer = Color(0xFF93000A),
        )
    }
    MaterialTheme(colorScheme = colors) {
        Surface(color = Color.Transparent) {
            LiveIslandCard(settings, snapshot, expanded, onExpandedChange, onAction)
        }
    }
}

/** A Material capsule that expands from the camera cutout area like a live island. */
@Composable
internal fun LiveIslandCard(
    settings: LiveIslandSettings,
    snapshot: LiveIslandSnapshot,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAction: (LiveIslandAction) -> Unit,
) {
    val entrance = remember { Animatable(0f) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "island chevron",
    )
    val fillColor = MaterialTheme.colorScheme.inverseSurface
    val contentColor = MaterialTheme.colorScheme.inverseOnSurface

    LaunchedEffect(Unit) {
        entrance.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f))
    }

    Surface(
        modifier = Modifier
            .graphicsLayer {
                alpha = entrance.value
                scaleX = 0.9f + entrance.value * 0.1f
                scaleY = 0.9f + entrance.value * 0.1f
                translationY = (1f - entrance.value) * (-20f)
            }
            .animateContentSize(spring(dampingRatio = 0.76f, stiffness = 520f)),
        shape = RoundedCornerShape(26.dp),
        color = fillColor,
        contentColor = contentColor,
        shadowElevation = 14.dp,
    ) {
        Column(
            Modifier
                .clickable { onExpandedChange(!expanded) }
                .pointerInput(expanded) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount > 0f && abs(dragAmount) > 64f) {
                                onAction(LiveIslandAction.Dismiss)
                            }
                        },
                    )
                }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CollapsedIslandRow(settings, snapshot, chevronRotation)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(340, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(240)),
                exit = shrinkVertically(
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                ) + fadeOut(tween(140)),
            ) {
                ExpandedIslandContent(settings, snapshot, onAction)
            }
        }
    }
}

@Composable
private fun CollapsedIslandRow(
    settings: LiveIslandSettings,
    snapshot: LiveIslandSnapshot,
    chevronRotation: Float,
) {
    val contentColor = MaterialTheme.colorScheme.inverseOnSurface
    val dotColor = statusColor(snapshot)
    val pausedRemaining = if (snapshot.running && !snapshot.protection) {
        val remaining = snapshot.pauseUntil - System.currentTimeMillis()
        if (remaining > 0L && snapshot.pauseUntil != Long.MAX_VALUE) {
            "剩余 ${formatRemaining(remaining)}"
        } else {
            "已暂停"
        }
    } else {
        null
    }
    Row(
        Modifier
            .defaultMinSize(minWidth = 92.dp, minHeight = 40.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Icon(
            painterResource(R.drawable.ic_shield),
            "AdGuard Home",
            Modifier.size(17.dp),
            tint = contentColor,
        )
        if (pausedRemaining != null) {
            Text(
                pausedRemaining,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (settings.compact) {
            Text(
                statusLabel(snapshot),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            if (settings.showQueries) {
                Text(formatCount(snapshot.total), style = MaterialTheme.typography.labelLarge)
            }
            if (settings.showBlocked) {
                Text(
                    "${formatCount(snapshot.blocked)} 拦截",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (settings.showRate) {
                Text(
                    "${snapshot.rate.toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.78f),
                )
            }
        }
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            "展开",
            Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = chevronRotation },
            tint = contentColor,
        )
    }
}
@Composable
private fun ExpandedIslandContent(
    settings: LiveIslandSettings,
    snapshot: LiveIslandSnapshot,
    onAction: (LiveIslandAction) -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.inverseOnSurface
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    statusLabel(snapshot),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (snapshot.loading) "正在连接本地模块" else "AdGuard Home",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.70f),
                    maxLines = 1,
                )
            }
            SmallIslandIcon(Icons.Rounded.OpenInFull, "打开管理器") {
                onAction(LiveIslandAction.OpenManager)
            }
            SmallIslandIcon(Icons.Rounded.Close, "收起") {
                onAction(LiveIslandAction.Dismiss)
            }
        }

        if (snapshot.running) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IslandStat(formatCount(snapshot.total), "查询", Modifier.weight(1f))
                IslandStat(formatCount(snapshot.blocked), "拦截", Modifier.weight(1f))
                IslandStat("${snapshot.rate.toInt()}%", "拦截率", Modifier.weight(1f))
            }
        } else {
            Text(
                "服务未运行，展开后可直接启动",
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.78f),
            )
        }

        if (snapshot.running && !snapshot.protection) {
            val remaining = snapshot.pauseUntil - System.currentTimeMillis()
            Text(
                if (remaining > 0L && snapshot.pauseUntil != Long.MAX_VALUE) {
                    "剩余 ${formatRemaining(remaining)}"
                } else {
                    "已暂停，等待手动恢复"
                },
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.90f),
            )
        }

        if (settings.showActions) {
            if (snapshot.running && snapshot.protection) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IslandActionButton("5 分钟", Modifier.weight(1f)) {
                        onAction(LiveIslandAction.SetProtection(false, 300_000L))
                    }
                    IslandActionButton("30 分钟", Modifier.weight(1f)) {
                        onAction(LiveIslandAction.SetProtection(false, 1_800_000L))
                    }
                    IslandActionButton("1 小时", Modifier.weight(1f)) {
                        onAction(LiveIslandAction.SetProtection(false, 3_600_000L))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!snapshot.running) {
                    IslandActionButton("启动服务", Modifier.weight(1f), primary = true) {
                        onAction(LiveIslandAction.StartService)
                    }
                } else if (snapshot.protection) {
                    IslandActionButton("暂停保护", Modifier.weight(1f), primary = true) {
                        onAction(LiveIslandAction.ToggleProtection)
                    }
                } else {
                    IslandActionButton("恢复保护", Modifier.weight(1f), primary = true) {
                        onAction(LiveIslandAction.SetProtection(true, 0L))
                    }
                }
                IslandActionButton("打开管理器", Modifier.weight(1f)) {
                    onAction(LiveIslandAction.OpenManager)
                }
            }
            if (snapshot.running) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IslandActionButton("重启服务", Modifier.weight(1f)) {
                        onAction(LiveIslandAction.RestartService)
                    }
                    IslandActionButton("停止服务", Modifier.weight(1f)) {
                        onAction(LiveIslandAction.StopService)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallIslandIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            icon,
            description,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.86f),
        )
    }
}

@Composable
private fun IslandStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.68f),
        )
    }
}

@Composable
private fun IslandActionButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val background = if (primary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.16f)
    }
    Box(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

@Composable
fun LiveIslandPreview(settings: LiveIslandSettings) {
    var expanded by remember { mutableStateOf(false) }
    val snapshot = LiveIslandSnapshot(
        loading = false,
        running = true,
        protection = true,
        total = 128_456L,
        blocked = 30_210L,
    )
    Box(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        LiveIslandCard(settings, snapshot, expanded, { expanded = it }, {})
    }
}

private fun statusLabel(snapshot: LiveIslandSnapshot): String = when {
    snapshot.loading -> "正在连接"
    !snapshot.running -> "服务已停止"
    !snapshot.protection -> if (snapshot.pauseUntil == Long.MAX_VALUE) "已暂停" else "暂停中"
    else -> "过滤保护中"
}

private fun statusColor(snapshot: LiveIslandSnapshot): Color = when {
    snapshot.loading -> Color(0xFFB0BEC5)
    !snapshot.running -> Color(0xFFFF8A80)
    !snapshot.protection -> Color(0xFFFFC107)
    else -> Color(0xFF69F0AE)
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
