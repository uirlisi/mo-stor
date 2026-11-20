package io.github.uirlisi.mostor.log

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.DayClock
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.data.DayItem
import io.github.uirlisi.mostor.data.Keys
import io.github.uirlisi.mostor.data.LogFilter
import io.github.uirlisi.mostor.edit.AddRecordDialog
import io.github.uirlisi.mostor.ui.AppUi
import io.github.uirlisi.mostor.ui.ChildFilter
import io.github.uirlisi.mostor.ui.KindIcons
import io.github.uirlisi.mostor.ui.iconOf
import io.github.uirlisi.mostor.ui.RecordIcon
import io.github.uirlisi.mostor.ui.chartColors
import io.github.uirlisi.mostor.ui.of
import io.github.uirlisi.mostor.ui.childOptions
import io.github.uirlisi.mostor.ui.Route
import java.time.ZoneId
import kotlinx.coroutines.delay

/** Days loaded at a time; scrolling to the end loads more. */
private const val PAGE_DAYS = 30

private val KINDS = listOf(Kind.BREAST, Kind.BOTTLE, Kind.SUPPLEMENT, Kind.SLEEP, Kind.DIAPER, Kind.PUMP, Kind.NOTE)

/**
 * The log (SPEC §6.5): every record, newest first, grouped by day. Filters by child (only with
 * several), kind and subtype; records hidden on this phone are behind the "Hidden types" filter.
 * Tap a record to edit or delete it; possible duplicates are flagged (§5.6).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogScreen(ui: AppUi) {
    val repository = ui.repository
    val fmt = ui.fmt
    val home by remember { repository.homeStates() }.collectAsStateWithLifecycle(initialValue = null)
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableIntStateOf(PAGE_DAYS) }
    var adding by remember { mutableStateOf(false) }
    var duplicateOf by remember { mutableStateOf<DayItem?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // The kind and subtype shown, remembered on this phone like the children ("diaper:poop").
    val kindFilter = home?.settings?.logKind?.split(':')
    val kind = kindFilter?.getOrNull(0)?.let { code -> Kind.entries.firstOrNull { it.code == code } }
    val subtype = kindFilter?.getOrNull(1)?.takeIf { kind != null }
    fun setKind(k: Kind?, sub: String? = null) {
        ui.quietly { repository.setSetting(Keys.LOG_KIND, k?.let { if (sub != null) it.code + ":" + sub else it.code }) }
    }
    // The children shown, remembered on this phone; none left (or none chosen) = all of them.
    val chosen = home?.let { h -> h.settings.logChildren.orEmpty().split(',').filter { it in h.childrenById } }.orEmpty()
    val filter = LogFilter(chosen.toSet(), kind, subtype, showHidden)
    val log by remember(days, filter) { repository.logViews(days, filter) }.collectAsStateWithLifecycle(initialValue = null)
    val st = home
    val unit = st?.settings?.unit ?: ui.unit
    val list = rememberLazyListState()
    val colors = chartColors()

    // Load older days when the end of the list comes into view.
    val atEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == list.layoutInfo.totalItemsCount - 1 } }
    LaunchedEffect(atEnd, log?.hasOlder) {
        if (atEnd && log?.hasOlder == true) days += PAGE_DAYS
    }

    if (adding && st != null) {
        AddRecordDialog(ui, st.settings.visibility, onClose = { adding = false })
    }
    duplicateOf?.let { item ->
        DuplicateDialog(ui, item, log?.days.orEmpty().flatMap { it.items }, now, unit, onClose = { duplicateOf = null })
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Filters
            Column(Modifier.padding(top = 8.dp)) {
                ChipRow {
                    // The children first (Phase 3c), archived ones included: the log keeps their records.
                    if (st != null && st.childrenById.size > 1) {
                        val options = childOptions(st.childrenById.values.toList(), fmt, st::colorIndex)
                        ChildFilter(options, chosen, compact = true) { ids ->
                            ui.quietly { repository.setSetting(Keys.LOG_CHILDREN, if (ids.size == options.size) null else ids.joinToString(",")) }
                        }
                    }
                    FilterChip(kind == null, { setKind(null) }, { Text(stringResource(R.string.log_all)) })
                    for (k in KINDS) {
                        FilterChip(kind == k, { setKind(if (kind == k) null else k) }, { Text(fmt.what(k, null, null)) }, leadingIcon = { RecordIcon(KindIcons.of(k), colors.iconOf(k), 18.dp) })
                    }
                    if (st?.settings?.visibility?.hidesAnything == true || showHidden) {
                        FilterChip(showHidden, { showHidden = !showHidden }, {
                            Text(stringResource(R.string.log_show_hidden) + (log?.hiddenCount?.takeIf { it > 0 && !showHidden }?.let { " ($it)" } ?: ""))
                        })
                    }
                }
                val subtypes = when (kind) {
                    Kind.BOTTLE -> BottleContent.entries.map { it.code to fmt.bottleContent(it) }
                    Kind.DIAPER -> Diaper.entries.map { it.code to fmt.diaper(it) }
                    else -> emptyList()
                }
                if (subtypes.isNotEmpty()) {
                    ChipRow {
                        for ((code, label) in subtypes) {
                            FilterChip(
                                subtype == code, { setKind(kind, if (subtype == code) null else code) }, { Text(label) },
                                leadingIcon = { RecordIcon(KindIcons.of(kind!!, code), colors.iconOf(kind, code), 18.dp) },
                            )
                        }
                    }
                }
            }

            val v = log
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            ) {
                if (v == null) return@LazyColumn
                if (v.days.isEmpty() && !v.hasOlder) {
                    item { Text(stringResource(R.string.log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
                }
                val clock = DayClock(st?.settings?.dayStartHour ?: 0, ZoneId.systemDefault())
                val today = clock.dayOf(now)
                for (d in v.days) {
                    stickyHeader(key = "day:${d.day}") {
                        Surface(Modifier.fillMaxWidth()) {
                            Text(
                                when (d.day) {
                                    today -> stringResource(R.string.today_title)
                                    today.minusDays(1) -> stringResource(R.string.today_yesterday)
                                    else -> fmt.date(clock.startOf(d.day) + 12 * 3_600_000L)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    val endMs = clock.endOf(d.day)
                    items(d.items, key = { it.event.id }) { item ->
                        val child = if (st?.severalChildren == true || (st?.childrenById?.size ?: 0) > 1) st?.childrenById?.get(item.event.childId) else null
                        LogRow(
                            item, fmt, now, unit, endMs, child,
                            st?.childrenById?.keys?.indexOf(item.event.childId) ?: 0,
                            onClick = { ui.open(Route.Edit(item.event.id)) },
                            onDuplicate = { duplicateOf = item },
                        )
                    }
                }
                if (v.hasOlder) {
                    item(key = "older") {
                        TextButton(onClick = { days += PAGE_DAYS }) { Text(stringResource(R.string.log_load_older)) }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.add_record))
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
