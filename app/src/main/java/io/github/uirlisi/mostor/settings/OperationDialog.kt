package io.github.uirlisi.mostor.settings

import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.res.stringResource
import io.github.uirlisi.mostor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What an operation that runs in the app scope and reports back (a backup, a copy, an import, a
 * household's export) shows: nothing, a note while it works, its result with OK, or a step of its
 * own ([Step], e.g. what an import would change, to confirm). Kept with `rememberOperationState`,
 * so a result that arrives after the screen was recreated still shows.
 */
internal sealed interface Operation<out S> {
    data object Idle : Operation<Nothing>

    /** At work: [title] over [text], which can't be dismissed. */
    data class Busy(val title: Int, val text: Int) : Operation<Nothing>

    /** Done: [title] over [text], with OK. */
    data class Message(val title: Int, val text: Int) : Operation<Nothing>

    /** A step of the operation's own, which [OperationDialog]'s caller shows. */
    data class Step<S>(val step: S) : Operation<S>
}

/**
 * The dialog for [state]'s operation: the busy note, the result with OK (both texts may name the
 * app, see [namedString]), or [step] for a step of its own.
 */
@Composable
internal fun <S> OperationDialog(state: MutableState<Operation<S>>, step: @Composable (S) -> Unit = {}) {
    when (val s = state.value) {
        Operation.Idle -> Unit
        is Operation.Busy -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(s.title)) },
            text = { Text(namedString(s.text)) },
        )
        is Operation.Message -> AlertDialog(
            onDismissRequest = { state.value = Operation.Idle },
            confirmButton = { TextButton(onClick = { state.value = Operation.Idle }) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(s.title)) },
            text = { Text(namedString(s.text)) },
        )
        is Operation.Step -> step(s.step)
    }
}

/**
 * Shows [busy] under [title] (or nothing, without [busy]) while [work] runs in [scope], then what
 * [work] returns.
 */
internal fun <S> MutableState<Operation<S>>.runThenShow(scope: CoroutineScope, title: Int, busy: Int?, work: suspend () -> Operation<S>) {
    if (busy != null) value = Operation.Busy(title, busy)
    scope.launch { value = work() }
}

/**
 * As [runThenShow], showing [failed] under [title] when [work] throws: logged as "[what] failed" with the
 * exception's class only (no data in the log).
 */
internal fun <S> MutableState<Operation<S>>.runThenShowOrFail(
    scope: CoroutineScope,
    title: Int,
    busy: Int?,
    failed: Int,
    what: String,
    work: suspend () -> Operation<S>,
) = runThenShow(scope, title, busy) {
    try {
        work()
    } catch (e: Exception) {
        Log.w("MoStor", "$what failed: ${e.javaClass.simpleName}")
        Operation.Message(title, failed)
    }
}
