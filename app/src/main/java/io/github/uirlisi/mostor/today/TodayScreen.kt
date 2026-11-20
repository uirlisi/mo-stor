package io.github.uirlisi.mostor.today

import android.Manifest
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.DayClock
import io.github.uirlisi.mostor.data.DayItem
import io.github.uirlisi.mostor.data.HomeState
import io.github.uirlisi.mostor.edit.AddRecordDialog
import io.github.uirlisi.mostor.log.DuplicateDialog
import io.github.uirlisi.mostor.log.LogRow
import io.github.uirlisi.mostor.sheet.QuickSheetActivity
import io.github.uirlisi.mostor.ui.AppUi
import io.github.uirlisi.mostor.ui.ChildFilter
import io.github.uirlisi.mostor.ui.MoStor
import io.github.uirlisi.mostor.ui.Route
import io.github.uirlisi.mostor.ui.caps
import io.github.uirlisi.mostor.ui.childColor
import io.github.uirlisi.mostor.ui.childOptions
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The space between Today's blocks (header, whose day, summary cards, timeline). */
private val BLOCK = 16.dp

/** How far back the day pager goes (about ten years). */
private const val PAGES = 3_650

/**
 * Today (SPEC §6.5; layout from docs/PHASE3B_HANDOFF.md, "Today screen"): the header, whose day,
 * the summary cards and the timeline. Swipe or use the arrows for other days; tap a record to edit
 * it. On today, the Record button at the bottom right carries the live timers and opens the quick
 * sheet; Today itself shows no timer cards. Also asks for the notification permission once, then
 * shows a banner while it's missing (§7).
 */
@Composable
fun TodayScreen(ui: AppUi) {
    val repository = ui.repository
    val notifier = ui.notifier
    val context = LocalContext.current
    val home by remember { repository.homeStates() }.collectAsStateWithLifecycle(initialValue = null)
    var canPost by remember { mutableStateOf(notifier.canPost()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var adding by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = PAGES - 1) { PAGES }
    val scope = rememberCoroutineScope()

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canPost = granted
        ui.quietly { notifier.refresh() }
    }
    LaunchedEffect(Unit) {
        if (!canPost && repository.markNotificationPermissionAsked()) request.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LifecycleResumeEffect(Unit) {
        canPost = notifier.canPost()
        onPauseOrDispose { }
    }
    LaunchedEffect(home?.settings?.unit) { home?.settings?.unit?.let { ui.unit = it } }
    // [Open day] on Patterns: turn to that day.
    val pendingDay = ui.pendingDay
    val dayStart = home?.settings?.dayStartHour
    LaunchedEffect(pendingDay, dayStart) {
        if (pendingDay == null || dayStart == null) return@LaunchedEffect
        val today = DayClock(dayStart, ZoneId.systemDefault()).dayOf(System.currentTimeMillis())
        val offset = ChronoUnit.DAYS.between(today, pendingDay).toInt()
        pager.scrollToPage((PAGES - 1 + offset).coerceIn(0, PAGES - 1))
        ui.pendingDay = null
    }
    // Every second while the Record button shows a clock, else twice a minute for the log.
    val ticking = home?.running?.isNotEmpty() == true
    LaunchedEffect(ticking) {
        while (true) {
            now = System.currentTimeMillis()
            delay(if (ticking) 1_000 else 30_000)
        }
    }

    val st = home
    if (adding && st != null) {
        AddRecordDialog(ui, st.settings.visibility, onClose = { adding = false })
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The whole page swipes between days, its header included; the arrows do the same.
        HorizontalPager(pager, modifier = Modifier.fillMaxSize(), key = { it }) { page ->
            val pageOffset = page - (PAGES - 1)
            DayPage(
                ui, pageOffset, st, now, showHidden, { showHidden = it }, canPost,
                onAllowNotifications = { request.launch(Manifest.permission.POST_NOTIFICATIONS) },
                onAdd = { adding = true },
                onPrev = { scope.launch { pager.animateScrollToPage(page - 1) } },
                onNext = { scope.launch { pager.animateScrollToPage(page + 1) } },
            )
        }
        // Record: today only, in the thumb zone; it grows leftwards with the timers it carries.
        if (st != null && pager.currentPage == PAGES - 1) {
            RecordButton(
                st, now, ui.fmt, maxWidth = maxWidth - 32.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { focus ->
                val intent = Intent(context, QuickSheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                focus?.let { intent.putExtra(QuickSheetActivity.EXTRA_FOCUS_EVENT, it) }
                context.startActivity(intent)
            }
        }
    }
}

/** The day's name over its date, with the earlier and later day buttons; on a page, so it swipes with it. */
@Composable
private fun DayHeader(title: String, date: String, canGoNext: Boolean, onPrev: () -> Unit, onNext: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // At least 64 dp; taller at the largest fonts, so the date line under the title is never clipped.
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold))
            Text(date, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = scheme.onSurfaceVariant)
        }
        OutlinedIconButton(
            onClick = onPrev,
            modifier = Modifier.size(48.dp),
            border = BorderStroke(1.dp, scheme.outlineVariant),
            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = scheme.surface),
        ) {
            Icon(painterResource(R.drawable.ic_prev), contentDescription = stringResource(R.string.today_prev), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onNext, enabled = canGoNext, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_next), contentDescription = stringResource(R.string.today_next), modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPage(
    ui: AppUi,
    offset: Int,
    st: HomeState?,
    now: Long,
    showHidden: Boolean,
    setShowHidden: (Boolean) -> Unit,
    canPost: Boolean,
    onAllowNotifications: () -> Unit,
    onAdd: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val repository = ui.repository
    val fmt = ui.fmt
    val context = LocalContext.current
    // Read again every hour as well as on every change: the day boundary (midnight, or the day start
    // hour) passing while Today is on screen moves "today" without any record changing.
    val hour = now / 3_600_000L
    var shown by remember(offset, showHidden) { mutableStateOf<io.github.uirlisi.mostor.data.DayView?>(null) }
    val fresh by remember(offset, showHidden, hour) { repository.dayViews(offset, showHidden) }.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(fresh) { fresh?.let { shown = it } }
    // The previous hour's view stays up until the new one is read, so the page doesn't blank.
    val view = fresh ?: shown
    var duplicateOf by remember { mutableStateOf<DayItem?>(null) }
    val unit = st?.settings?.unit ?: ui.unit

    duplicateOf?.let { item ->
        DuplicateDialog(ui, item, view?.items.orEmpty(), now, unit, onClose = { duplicateOf = null })
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        // The bottom padding keeps the last row clear of the Record button.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
    ) {
        item(key = "header") {
            val noon = view?.let { it.endMs - 12 * 3_600_000L }
            val title = when (offset) {
                0 -> stringResource(R.string.today_title)
                -1 -> stringResource(R.string.today_yesterday)
                else -> noon?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_WEEKDAY) }.orEmpty()
            }
            val date = noon?.let {
                DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)
            }.orEmpty()
            DayHeader(title, date, canGoNext = offset < 0, onPrev = onPrev, onNext = onNext)
        }
        // Whose day: one, several or all children (Phase 3c). Choosing exactly one also picks who
        // this phone records for (shared with the quick sheet's picker).
        if (st != null && st.severalChildren) {
            item(key = "children") {
                ChildFilter(
                    childOptions(st.children, fmt, st::colorIndex),
                    view?.childIds ?: listOf(st.childId),
                    Modifier.padding(top = 8.dp),
                ) { ids -> ui.quietly { repository.setTodayChildren(ids) } }
            }
        }
        if (offset == 0 && !canPost) {
            item {
                Card(Modifier.padding(top = BLOCK), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.notification_reason, stringResource(R.string.app_name)))
                        Button(onClick = onAllowNotifications) { Text(stringResource(R.string.allow_notifications)) }
                    }
                }
            }
        }
        val v = view ?: return@LazyColumn
        val visibility = st?.settings?.visibility
        // Child chips (card headers, timeline rows) only with several children: one child's day needs no labels.
        val all = v.several
        if (visibility != null) {
            items(v.totals, key = { "totals:" + (it.child?.id ?: "") }) { ct ->
                Spacer(Modifier.height(BLOCK))
                SummaryCard(if (all) ct else ct.copy(child = null), fmt, unit, visibility, ct.child?.let { c -> childColor(c, st.colorIndex(c.id)) })
            }
        }
        if (v.hiddenCount > 0 || showHidden) {
            item {
                TextButton(onClick = { setShowHidden(!showHidden) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (showHidden) stringResource(R.string.today_hide_again) else stringResource(R.string.today_hidden, v.hiddenCount))
                }
            }
        }
        item(key = "timeline") {
            Column(Modifier.padding(top = BLOCK, bottom = 4.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // The label and the button share a line; at the largest fonts the button moves below
                // the label rather than squeezing it into a column.
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        caps(stringResource(R.string.today_timeline)),
                        style = MoStor.type.sectionLabel,
                        color = MoStor.colors.label,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    OutlinedButton(
                        onClick = onAdd,
                        modifier = Modifier.heightIn(min = 36.dp).height(36.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.add_record), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (v.items.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.today_empty, stringResource(R.string.app_name)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val several = st?.severalChildren == true && v.several
        items(v.items, key = { it.event.id }) { item ->
            val child = if (several) st?.childrenById?.get(item.event.childId) else null
            LogRow(
                item, fmt, now, unit, v.endMs, child, st?.colorIndex(item.event.childId) ?: 0,
                onClick = { ui.open(Route.Edit(item.event.id)) },
                onDuplicate = { duplicateOf = item },
            )
        }
    }
}
