package io.github.uirlisi.mostor.sheet

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import io.github.uirlisi.mostor.data.Done
import io.github.uirlisi.mostor.data.Repository
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class Editor { NONE, BOTTLE, PUMP, NOTE }

/** The confirmation strip: the last action, its Undo, and for starts the "started earlier" fixes. */
internal data class Strip(val done: Done, val base: Long, val outcome: Outcome = Outcome.DONE, val skippable: Boolean = false) {
    enum class Outcome { DONE, UNDONE, UNDO_REFUSED }
}

internal data class PickerRequest(val titleRes: Int, val initial: Long, val onPicked: (LocalTime) -> Unit)

/**
 * The quick sheet's interaction state: the last tap (the auto-close counts from it), the open
 * editor, the time picker, the confirmation strip and the child menu; and how a tap runs its write.
 * Made by [rememberQuickSheetState].
 */
@Stable
internal class QuickSheetState(
    private val repository: Repository,
    private val appScope: CoroutineScope,
    private val view: View,
    editorState: MutableState<Editor>,
) {
    /** When the sheet was last tapped (uptime ms); 0 before the first tap, when there is no auto-close. */
    var lastTap by mutableLongStateOf(0L)
        private set
    var editor by editorState
    var picker by mutableStateOf<PickerRequest?>(null)
    var strip by mutableStateOf<Strip?>(null)
        private set

    /** The child menu is open: no auto-close meanwhile. */
    var menuOpen by mutableStateOf(false)

    fun touch() {
        lastTap = SystemClock.uptimeMillis()
    }

    /** Restarts the auto-close countdown, if a tap has started it. */
    fun restartCountdown() {
        if (lastTap > 0) touch()
    }

    /**
     * Runs a write on the app scope (it finishes even if the sheet closes) and shows its strip.
     * [skippable]: a due or late med's dose, so the strip offers [Skip] (SPEC §5.10).
     */
    fun act(skippable: Boolean = false, block: suspend () -> Done?) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        touch()
        appScope.launch {
            val done = block() ?: return@launch
            withContext(Dispatchers.Main) {
                val keepBase = strip?.takeIf { it.done.undoId == done.undoId }?.base
                strip = Strip(done, keepBase ?: done.info.at, skippable = skippable)
            }
        }
    }

    fun undo(s: Strip) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        touch()
        appScope.launch {
            val ok = repository.undo(s.done.undoId)
            withContext(Dispatchers.Main) {
                strip = s.copy(outcome = if (ok) Strip.Outcome.UNDONE else Strip.Outcome.UNDO_REFUSED)
            }
        }
    }

    fun toggle(e: Editor) {
        touch()
        editor = if (editor == e) Editor.NONE else e
    }

    val host = TimerHost(
        act = { block -> act(block = block) },
        pickTime = { title, initial, onPicked -> picker = PickerRequest(title, initial, onPicked) },
        quietly = { block -> touch(); appScope.launch { block() } },
    )
}

@Composable
internal fun rememberQuickSheetState(repository: Repository, appScope: CoroutineScope): QuickSheetState {
    val view = LocalView.current
    // Saved with the sheet: a rotation or a dark-mode switch keeps the open editor and what is in it.
    val editor = rememberSaveable { mutableStateOf(Editor.NONE) }
    return remember { QuickSheetState(repository, appScope, view, editor) }
}

/**
 * Closes the sheet [autoCloseMs] (4 s by default; or [NEVER]) after the last tap, but not while
 * something is being typed or picked, the child menu is open, or a finger is on the sheet ([fingerDown]:
 * pulling it down, Phase 3c).
 */
@Composable
internal fun AutoClose(sheet: QuickSheetState, autoCloseMs: Long, fingerDown: Boolean, onClose: () -> Unit) {
    LaunchedEffect(sheet.lastTap, sheet.editor, sheet.picker, autoCloseMs, sheet.menuOpen, fingerDown) {
        if (sheet.lastTap > 0 && sheet.editor == Editor.NONE && sheet.picker == null && autoCloseMs != NEVER && !sheet.menuOpen && !fingerDown) {
            delay(autoCloseMs)
            onClose()
        }
    }
}

/** Auto-close "Never". */
internal const val NEVER = -1L
