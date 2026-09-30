package es.emtvalencia.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How much of the screen the open pane currently covers (0..1). */
val popoverVisibleFraction = mutableFloatStateOf(0f)

/**
 * Bottom sheet the user drags between three heights — a small peek, half and
 * (nearly) full — so the map above stays visible and usable.
 *
 * `peek` is always on screen; `content` reveals itself as the sheet is raised,
 * and receives `expanded = true` once it reaches the tallest anchor.
 */
@Composable
fun DraggableSheet(
    modifier: Modifier = Modifier,
    peekHeight: Dp = 112.dp,
    halfFraction: Float = 0.5f,
    fullFraction: Float = 0.94f,
    onDismiss: (() -> Unit)? = null,
    peek: @Composable () -> Unit,
    content: @Composable ColumnScope.(expanded: Boolean, dismiss: () -> Unit) -> Unit,
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val container = with(density) { maxHeight.toPx() }
        val anchors = remember(container, peekHeight, halfFraction, fullFraction) {
            listOf(
                (container * (1f - fullFraction)).coerceAtLeast(0f),
                (container * (1f - halfFraction)).coerceAtLeast(0f),
                (container - with(density) { peekHeight.toPx() }).coerceAtLeast(0f),
            )
        }
        val offset = remember { Animatable(container) }
        val scope = rememberCoroutineScope()
        val expanded = offset.value <= anchors.first() + 16f
        val overshoot = 96f

        // Slide up into view when the sheet appears.
        LaunchedEffect(anchors) {
            offset.animateTo(anchors[1], tween(300))
        }
        val dismiss: () -> Unit = {
            scope.launch {
                offset.animateTo(container, tween(220))
                onDismiss?.invoke()
            }
        }
        // Report the visible height so the floating buttons can ride above the pane.
        SideEffect {
            popoverVisibleFraction.floatValue = ((container - offset.value) / container).coerceIn(0f, 1f)
        }
        DisposableEffect(Unit) {
            onDispose { popoverVisibleFraction.floatValue = 0f }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxHeight)
                .offset { IntOffset(0, offset.value.roundToInt()) },
            tonalElevation = 4.dp,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.fillMaxWidth().height(maxHeight)) {
                // Only the header starts a drag, so lists below stay scrollable.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(anchors, onDismiss) {
                            detectVerticalDragGestures(
                            onDragEnd = {
                                // Dragged well below the peek: dismiss for good.
                                if (onDismiss != null && offset.value > anchors.last() + overshoot / 2) {
                                    dismiss()
                                } else {
                                        val nearest = anchors.minByOrNull { abs(it - offset.value) } ?: offset.value
                                        scope.launch { offset.animateTo(nearest, tween(260)) }
                                    }
                                },
                            ) { _, dragAmount ->
                                scope.launch {
                                    offset.snapTo(
                                        (offset.value + dragAmount).coerceIn(anchors.first(), anchors.last() + overshoot),
                                    )
                                }
                            }
                        },
                ) {
                    Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
                        )
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 4.dp)) { peek() }
                }
                content(expanded, dismiss)
            }
        }
    }
}
