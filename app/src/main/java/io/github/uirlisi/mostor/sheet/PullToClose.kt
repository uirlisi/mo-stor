package io.github.uirlisi.mostor.sheet

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * Pull down to close (Phase 3c): the sheet sits at Shown (offset 0) or Hidden (its height). Made by
 * [rememberPullToClose]; [pullToClose] puts it on the sheet, which moves itself down by [offset].
 */
@Stable
internal class PullToCloseState(
    private val flingPx: () -> Float,
    private val onClose: () -> Unit,
    private val onLetGo: () -> Unit,
) {
    private val drag = AnchoredDraggableState(SheetValue.SHOWN)
    private var sheetHeight by mutableIntStateOf(0)

    /** A finger is down on the sheet: no auto-close meanwhile. */
    var fingerDown by mutableStateOf(false)
        private set

    internal val dragState = DraggableState { delta -> drag.dispatchRawDelta(delta) }

    /** How far the sheet is pulled down, in px. */
    fun offset(): Float = drag.offset.takeUnless { it.isNaN() } ?: 0f

    /** How much of the sheet shows, 0 to 1: the scrim lightens as the sheet goes down. */
    fun shown(): Float = if (sheetHeight > 0) (1f - offset() / sheetHeight).coerceIn(0f, 1f) else 1f

    internal fun onSheetSize(height: Int) {
        sheetHeight = height
        drag.updateAnchors(
            DraggableAnchors {
                SheetValue.SHOWN at 0f
                SheetValue.HIDDEN at height.toFloat()
            },
        )
    }

    internal fun onFinger(down: Boolean) {
        fingerDown = down
        if (!down) onLetGo()
    }

    /**
     * Where a pulled sheet goes when let go: Hidden past a quarter of its height or after a downward
     * flick, else back to Shown. Hidden closes the sheet, as a tap outside does; springing back
     * restarts the auto-close countdown. False when the sheet hadn't moved (nothing to settle).
     */
    internal suspend fun settle(velocity: Float): Boolean {
        val pulled = offset()
        if (pulled <= 0f) return false
        val fling = flingPx()
        val close = when {
            velocity > fling -> true
            velocity < -fling -> false
            else -> pulled > CLOSE_FRACTION * sheetHeight
        }
        drag.animateTo(if (close) SheetValue.HIDDEN else SheetValue.SHOWN)
        if (close) onClose() else onLetGo()
        return true
    }

    // The scrolling content hands the sheet what it can't use: a downward scroll at its top pulls
    // the sheet, and an upward one while the sheet is pulled moves the sheet back first (as M3 sheets).
    internal val nested = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0 && source == NestedScrollSource.UserInput && offset() > 0f) Offset(0f, drag.dispatchRawDelta(available.y))
            else Offset.Zero

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.y > 0 && source == NestedScrollSource.UserInput) Offset(0f, drag.dispatchRawDelta(available.y))
            else Offset.Zero

        override suspend fun onPreFling(available: Velocity): Velocity =
            if (available.y < 0 && offset() > 0f && settle(available.y)) available else Velocity.Zero

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            if (settle(available.y)) available else Velocity.Zero
    }
}

/**
 * A sheet that closes when pulled down. [onClose] runs when it is let go far enough down;
 * [onLetGo] when a finger leaves it or it springs back (the quick sheet restarts its auto-close).
 */
@Composable
internal fun rememberPullToClose(onClose: () -> Unit, onLetGo: () -> Unit): PullToCloseState {
    val flingPx = with(LocalDensity.current) { CLOSE_FLING.toPx() }
    val fling by rememberUpdatedState(flingPx)
    val close by rememberUpdatedState(onClose)
    val letGo by rememberUpdatedState(onLetGo)
    return remember { PullToCloseState({ fling }, { close() }, { letGo() }) }
}

/**
 * The sheet's side of [state]: it measures the sheet, tracks a finger on it, takes what the
 * scrolling content leaves over, and drags. Goes after the sheet's own padding, as its height is
 * how far it travels.
 */
internal fun Modifier.pullToClose(state: PullToCloseState): Modifier = this
    .onSizeChanged { size -> state.onSheetSize(size.height) }
    // No auto-close while a finger is on the sheet; letting go restarts the countdown.
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.onFinger(true)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                state.onFinger(false)
            }
        }
    }
    .nestedScroll(state.nested)
    // A vertical drag anywhere on the sheet, past the touch slop; taps still reach the buttons.
    .draggable(
        state = state.dragState,
        orientation = Orientation.Vertical,
        onDragStopped = { velocity -> state.settle(velocity) },
    )

/** The pull-to-close sheet's two places. */
private enum class SheetValue { SHOWN, HIDDEN }

/** Let go past this share of its height, or after a downward flick this fast, and the sheet closes (to tune on the phones). */
private const val CLOSE_FRACTION = 0.25f
private val CLOSE_FLING = 400.dp
