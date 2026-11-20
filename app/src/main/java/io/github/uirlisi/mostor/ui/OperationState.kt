package io.github.uirlisi.mostor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.uirlisi.mostor.container

/**
 * The state of an operation that runs in the app scope and reports back (a backup, an import, a
 * restore, a household's export): kept in the app's container, not in the screen, so a result that
 * arrives after the screen was recreated (a rotation, a dark-mode switch) still shows. One per
 * [key] and household; lost only with the process, like the operation itself.
 */
@Composable
fun <T> rememberOperationState(key: String, initial: T): MutableState<T> {
    val c = LocalContext.current.container
    val household = c.households.list().activeId
    @Suppress("UNCHECKED_CAST")
    return remember(key, household) { c.operationStates.getOrPut("$household/$key") { mutableStateOf(initial) } as MutableState<T> }
}
