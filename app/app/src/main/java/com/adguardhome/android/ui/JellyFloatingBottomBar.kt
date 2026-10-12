/*
 * Interaction and rendering model adapted from KernelSU Manager's
 * FloatingBottomBar and DampedDragAnimation (Apache 2.0).
 */
package com.adguardhome.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

private val PositionSpring = spring<Float>(
    dampingRatio = 1f,
    stiffness = 1000f,
    visibilityThreshold = 0.001f,
)

private val PressInSpring = spring<Float>(
    dampingRatio = 0.6f,
    stiffness = 250f,
    visibilityThreshold = 0.001f,
)

private val PressOutSpring = spring<Float>(
    dampingRatio = 0.7f,
    stiffness = 250f,
    visibilityThreshold = 0.001f,
)

@Composable
fun JellyFloatingBottomBar(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    tabsCount: Int,
    backdrop: Backdrop,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(index: Int, selected: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val position = remember { Animatable(selectedIndex.toFloat()) }
    val press = remember { Animatable(0f) }
    val panelDrag = remember { Animatable(0f) }
    val dragVelocity = remember { Animatable(0f) }
    var pressJob by remember { mutableStateOf<Job?>(null) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val blur = blurEnabled && isRenderEffectSupported()
    val shape = CircleShape
    val baseColor = MaterialTheme.colorScheme.surface
    val dropletColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color.Black.copy(alpha = 0.10f)
    }

    LaunchedEffect(selectedIndex, dragging) {
        if (!dragging) position.animateTo(selectedIndex.toFloat(), PositionSpring)
    }

    val panelShift = if (totalWidthPx == 0f) 0f else {
        val fraction = (panelDrag.value / totalWidthPx).coerceIn(-1f, 1f)
        with(density) { 4.dp.toPx() } * fraction.sign * EaseOut.transform(abs(fraction))
    }

    Box(
        modifier = modifier
            .width(IntrinsicSize.Min)
            .height(64.dp)
            .pointerInput(tabsCount, tabWidthPx, totalWidthPx, isLtr) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    dragging = true
                    var draggedDistance = 0f
                    var previous = down.position
                    settleJob?.cancel()
                    pressJob?.cancel()
                    pressJob = scope.launch { press.animateTo(1f, PressInSpring) }
                    scope.launch { panelDrag.snapTo(0f) }
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.position.x - previous.x
                            previous = change.position
                            tracker.addPosition(change.uptimeMillis, change.position)
                            draggedDistance += delta
                            if (tabWidthPx > 0f && abs(draggedDistance) > viewConfiguration.touchSlop) {
                                val direction = if (isLtr) 1f else -1f
                                val attempted = position.value + delta / tabWidthPx * direction
                                val next = attempted.coerceIn(0f, (tabsCount - 1).toFloat())
                                val velocity = tracker.calculateVelocity().x / tabWidthPx /
                                    (tabsCount - 1).coerceAtLeast(1)
                                scope.launch {
                                    position.snapTo(next)
                                    if (attempted == next) {
                                        panelDrag.snapTo(0f)
                                    } else {
                                        panelDrag.snapTo(panelDrag.value + delta)
                                    }
                                    dragVelocity.snapTo(velocity)
                                }
                            }
                        }
                    } finally {
                        val wasDrag = abs(draggedDistance) > viewConfiguration.touchSlop
                        val clicked = if (tabWidthPx > 0f) {
                            val localX = (down.position.x - 4.dp.toPx()).coerceAtLeast(0f)
                            val physicalIndex = floor(localX / tabWidthPx).toInt().coerceIn(0, tabsCount - 1)
                            if (isLtr) physicalIndex else tabsCount - 1 - physicalIndex
                        } else selectedIndex
                        val target = if (wasDrag) {
                            position.value.roundToInt().coerceIn(0, tabsCount - 1)
                        } else clicked
                        pressJob?.cancel()
                        settleJob = scope.launch {
                            launch { panelDrag.animateTo(0f, spring(1f, 300f, 0.5f)) }
                            launch { dragVelocity.animateTo(0f, spring(0.5f, 300f, 0.01f)) }
                            val moveJob = launch { position.animateTo(target.toFloat(), PositionSpring) }
                            snapshotFlow { abs(position.value - target.toFloat()) }.first { it < 0.03f }
                            onSelected(target)
                            press.animateTo(0f, PressOutSpring)
                            moveJob.join()
                            dragging = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .graphicsLayer { translationX = panelShift }
                .then(
                    when {
                        blur -> Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                if (blur) blur(6.dp.toPx(), 6.dp.toPx())
                            },
                            onDrawSurface = { drawRect(baseColor.copy(alpha = 0.6f)) },
                        )
                        else -> Modifier.background(baseColor, shape)
                    }
                )
                .height(64.dp)
                .padding(4.dp)
                .onSizeChanged { size ->
                    totalWidthPx = size.width.toFloat()
                    tabWidthPx = if (tabsCount == 0) 0f else size.width.toFloat() / tabsCount
                }
        ) {
            Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(tabsCount) { Spacer(Modifier.defaultMinSize(minWidth = 76.dp).weight(1f)) }
            }
        }

        if (tabWidthPx > 0f) {
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .graphicsLayer {
                        val progressOffset = position.value * tabWidthPx
                        translationX = (if (isLtr) progressOffset else -progressOffset) + panelShift
                        val pressedScale = lerp(1f, 78f / 56f, press.value)
                        val velocity = (dragVelocity.value / 10f).coerceIn(-0.2f, 0.2f)
                        scaleX = pressedScale / (1f - velocity * 0.75f)
                        scaleY = pressedScale * (1f - velocity * 0.25f)
                        clip = false
                    }
                    .background(dropletColor, shape)
                    .width(with(density) { tabWidthPx.toDp() })
                    .height(56.dp)
            )
        }

        Row(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer { translationX = panelShift }
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(tabsCount) { index -> content(index, index == selectedIndex) }
        }
    }
}

@Composable
fun RowScope.JellyNavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .defaultMinSize(minWidth = 76.dp)
            .fillMaxHeight()
            .weight(1f)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        top.yukonga.miuix.kmp.basic.Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        )
        top.yukonga.miuix.kmp.basic.Text(
            text = label,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
