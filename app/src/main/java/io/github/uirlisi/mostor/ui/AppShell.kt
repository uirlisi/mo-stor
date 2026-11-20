package io.github.uirlisi.mostor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.uirlisi.mostor.AppContainer
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.VolumeUnit
import io.github.uirlisi.mostor.data.Done
import io.github.uirlisi.mostor.data.Repository
import io.github.uirlisi.mostor.edit.EditScreen
import io.github.uirlisi.mostor.log.LogScreen
import io.github.uirlisi.mostor.notify.StatusNotifier
import io.github.uirlisi.mostor.patterns.PatternsScreen
import io.github.uirlisi.mostor.settings.SettingsPage
import io.github.uirlisi.mostor.settings.SettingsScreen
import io.github.uirlisi.mostor.stats.StatsScreen
import io.github.uirlisi.mostor.today.TodayScreen
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** The bottom navigation's tabs (SPEC §6.5). */
enum class Tab(val label: Int, val icon: Int) {
    TODAY(R.string.tab_today, R.drawable.ic_today),
    LOG(R.string.tab_log, R.drawable.ic_log),
    PATTERNS(R.string.tab_patterns, R.drawable.ic_patterns),
    STATS(R.string.tab_stats, R.drawable.ic_stats),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings),
}

/** A screen on top of the tabs. Encoded as a string so the stack survives process death. */
sealed interface Route {
    data class Edit(val eventId: String) : Route
    data class Add(val kind: Kind) : Route
    data class Settings(val page: SettingsPage, val arg: String? = null) : Route

    fun encode(): String = when (this) {
        is Edit -> "edit:$eventId"
        is Add -> "add:${kind.code}"
        is Settings -> "settings:${page.name}" + (arg?.let { ":$it" } ?: "")
    }

    companion object {
        fun decode(s: String): Route? {
            val parts = s.split(':', limit = 3)
            return runCatching {
                when (parts[0]) {
                    "edit" -> Edit(parts[1])
                    "add" -> Add(Kind.of(parts[1]))
                    "settings" -> Settings(SettingsPage.valueOf(parts[1]), parts.getOrNull(2))
                    else -> null
                }
            }.getOrNull()
        }
    }
}

/** What every screen of the main app gets: the repository, navigation, and writes confirmed with Undo. */
class AppUi(
    val repository: Repository,
    val notifier: StatusNotifier,
    val appScope: CoroutineScope,
    val fmt: Formats,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val stack: SnapshotStateList<String>,
    private val undoLabel: String,
    private val refusedText: String,
    private val selectTab: (Tab) -> Unit,
) {
    /** The volume unit for snackbar text; kept current by the Today screen and settings. */
    var unit: VolumeUnit = VolumeUnit.ML

    /** A day for Today to show next ([showDay]); Today clears it once it has turned to it. */
    var pendingDay: LocalDate? by mutableStateOf(null)

    /** Switches to Today, on [day]. */
    fun showDay(day: LocalDate) {
        pendingDay = day
        stack.clear()
        selectTab(Tab.TODAY)
    }

    fun open(route: Route) {
        stack.add(route.encode())
    }

    fun back() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    /**
     * Runs a write on the app scope (it finishes even if the screen goes away) and confirms it
     * with a snackbar offering [Undo] (SPEC §6.5). [after] runs once the write is done.
     */
    fun act(block: suspend () -> Done?, after: (Done) -> Unit = {}) {
        scope.launch {
            val done = appScope.async { block() }.await() ?: return@launch
            after(done)
            confirm(done)
        }
    }

    /** The snackbar for a finished action, with [Undo]. */
    fun confirm(done: Done) {
        scope.launch {
            val info = done.info
            val text = if (info.kind != null) fmt.action(info, unit) + " · " + fmt.time(info.at) else fmt.action(info, unit)
            val result = snackbar.showSnackbar(text, actionLabel = undoLabel, withDismissAction = true, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed && !appScope.async { repository.undo(done.undoId) }.await()) {
                snackbar.showSnackbar(refusedText)
            }
        }
    }

    /** A plain message. */
    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short) }
    }

    /** Runs a write that needs no confirmation. */
    fun quietly(block: suspend () -> Unit) {
        appScope.launch { block() }
    }
}

@Composable
fun AppShell(c: AppContainer) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    val tabStates = rememberSaveableStateHolder()
    val stack = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) {
        mutableStateListOf<String>()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    val refused = stringResource(R.string.sheet_undo_refused)
    val ui = remember {
        AppUi(c.repository, c.notifier, c.appScope, Formats(context), scope, snackbar, stack, undoLabel, refused) { tab = it }
    }
    val top = stack.lastOrNull()?.let(Route::decode)
    val households by c.households.flow.collectAsStateWithLifecycle()

    // Back closes the screen on top (the edit screen adds its own check for unsaved changes),
    // then goes back to Today before leaving the app.
    BackHandler(enabled = stack.isNotEmpty()) { ui.back() }
    BackHandler(enabled = stack.isEmpty() && tab != Tab.TODAY) { tab = Tab.TODAY }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (top == null) {
                // Phase 3b: the sheet color with a hairline on top; the selected tab in a pill of the
                // strip color, its label in the text color at 600.
                val s = MaterialTheme.colorScheme
                BoxWithConstraints {
                    // The bar measures its labels without a width limit, so a label can't tell it is too
                    // wide to shrink: give it the item's width (the bar's 8 dp side padding and 8 dp gaps).
                    val labelMax = (maxWidth - 16.dp - 8.dp * (Tab.entries.size - 1)) / Tab.entries.size
                    Column {
                        HorizontalDivider(color = s.outlineVariant)
                        NavigationBar(containerColor = s.surface, tonalElevation = 0.dp, modifier = Modifier.height(80.dp + bottomInset())) {
                            for (t in Tab.entries) {
                                val selected = tab == t
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { tab = t },
                                    icon = { Icon(painterResource(t.icon), contentDescription = null) },
                                    label = {
                                        // One line that shrinks at the largest fonts, never "Setti / ngs".
                                        FitText(
                                            stringResource(t.label),
                                            MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                                            Modifier.widthIn(max = labelMax),
                                            // Down to the label's size at the default font (12 dp), not 10 sp
                                            // scaled up, which is still too wide at the largest fonts.
                                            minFontSize = with(LocalDensity.current) { 12.dp.toSp() },
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = s.primaryContainer,
                                        selectedIconColor = s.onPrimaryContainer,
                                        selectedTextColor = s.onSurface,
                                        unselectedIconColor = s.onSurfaceVariant,
                                        unselectedTextColor = s.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
            // With two or more households, which one this is (SPEC §9.4). A label, not a switch: it
            // opens Settings › Households and phones, so a stray tap can't send records to the wrong household.
            if (top == null && households.several) {
                HouseholdLabel(c.nameOf(households.active)) { ui.open(Route.Settings(SettingsPage.HOUSEHOLD)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (top) {
                    // Each tab keeps its saved state (the day Today shows, how far Log is scrolled)
                    // while an edit or a settings page is open over it, and when another tab is chosen.
                    null -> tabStates.SaveableStateProvider(tab.name) {
                        when (tab) {
                            Tab.TODAY -> TodayScreen(ui)
                            Tab.LOG -> LogScreen(ui)
                            Tab.PATTERNS -> PatternsScreen(ui)
                            Tab.STATS -> StatsScreen(ui)
                            Tab.SETTINGS -> SettingsScreen(ui)
                        }
                    }
                    // One composition per route: two edits in a row never share state.
                    is Route.Edit -> key(top) { EditScreen(ui, eventId = top.eventId, newKind = null) }
                    is Route.Add -> key(top) { EditScreen(ui, eventId = null, newKind = top.kind) }
                    is Route.Settings -> key(top) { SettingsScreen(ui, top.page, top.arg) }
                }
            }
        }
    }
}

/** The active household's name over the tabs; a tap opens Settings › Households and phones. */
@Composable
private fun HouseholdLabel(name: String, onClick: () -> Unit) {
    val description = stringResource(R.string.household_label_desc, name)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            painterResource(R.drawable.ic_next),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The system navigation bar's height, which the bottom bar draws behind. */
@Composable
private fun bottomInset(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
