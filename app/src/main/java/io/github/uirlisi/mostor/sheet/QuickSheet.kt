package io.github.uirlisi.mostor.sheet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.DayClock
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Durations
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.MedStatus
import io.github.uirlisi.mostor.core.PickedTime
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.SupplementPreset
import io.github.uirlisi.mostor.data.HomeState
import io.github.uirlisi.mostor.data.Repository
import io.github.uirlisi.mostor.data.RunningTimer
import io.github.uirlisi.mostor.data.Source
import io.github.uirlisi.mostor.data.Verb
import io.github.uirlisi.mostor.ui.ChartColors
import io.github.uirlisi.mostor.ui.ChildPicker
import io.github.uirlisi.mostor.ui.FitText
import io.github.uirlisi.mostor.ui.Formats
import io.github.uirlisi.mostor.ui.KindIcons
import io.github.uirlisi.mostor.ui.MoStor
import io.github.uirlisi.mostor.ui.RecordIcon
import io.github.uirlisi.mostor.ui.RowStatus
import io.github.uirlisi.mostor.ui.caps
import io.github.uirlisi.mostor.ui.chartColors
import io.github.uirlisi.mostor.ui.childColor
import io.github.uirlisi.mostor.ui.childOptions
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A med chip's status line (SPEC §5.10): a mark icon, the words, and their color (the mark's may differ). */
internal data class MedLine(val text: String, val mark: Int?, val color: Color, val markColor: Color = color)

/**
 * The quick sheet (SPEC §6.3; layout from docs/PHASE3B_HANDOFF.md). Top to bottom, leaving out
 * what's hidden on this phone: handle and child switcher, running cards, Feed, Meds, Sleep, Diaper,
 * Pump; then, pinned, the note editor, the confirmation strip and the footer.
 */
@Composable
fun QuickSheet(
    repository: Repository,
    appScope: CoroutineScope,
    focusEventId: String?,
    /** A launcher shortcut's action, recorded once the state has loaded. */
    shortcut: String? = null,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val fmt = remember { Formats(context) }
    val colors = chartColors()
    val state by remember { repository.homeStates() }.collectAsStateWithLifecycle(initialValue = null)
    val sheet = rememberQuickSheetState(repository, appScope)
    val pull = rememberPullToClose(onClose, onLetGo = sheet::restartCountdown)

    // The live texts' clock, ticking each second. Only those texts read it, so a tick recomposes
    // them and not the whole sheet.
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val now = clock::longValue
    LaunchedEffect(Unit) {
        while (true) {
            clock.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }

    AutoClose(sheet, state?.settings?.let { it.autoCloseMs ?: NEVER } ?: 4_000L, pull.fingerDown, onClose)

    // Opened from a launcher shortcut: record it, so the sheet shows its strip with Undo.
    val loaded = state != null
    LaunchedEffect(loaded) {
        if (!loaded || shortcut == null) return@LaunchedEffect
        when (shortcut) {
            QuickSheetActivity.ACTION_WET -> sheet.act { repository.recordDiaper(Diaper.WET, Source.SHEET) }
            QuickSheetActivity.ACTION_POOP -> sheet.act { repository.recordDiaper(Diaper.POOP, Source.SHEET) }
            QuickSheetActivity.ACTION_LEFT -> sheet.act { repository.tapSide(Side.LEFT, Source.SHEET) }
            QuickSheetActivity.ACTION_RIGHT -> sheet.act { repository.tapSide(Side.RIGHT, Source.SHEET) }
        }
    }
    val noIndication = remember { MutableInteractionSource() }
    val st = state

    Box(
        Modifier
            .fillMaxSize()
            // The scrim lightens as the sheet goes down.
            .drawBehind {
                if (st != null) drawRect(Color.Black.copy(alpha = 0.4f * pull.shown()))
            }
            .clickable(interactionSource = noIndication, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (st == null) return@Box
        val scheme = MaterialTheme.colorScheme
        val v = st.settings.visibility
        val selectedChild = st.children.firstOrNull { it.id == st.childId }
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = scheme.surface,
            modifier = Modifier
                .offset { IntOffset(0, pull.offset().roundToInt()) }
                .fillMaxWidth()
                .statusBarsPadding()
                .pullToClose(pull)
                .clickable(interactionSource = noIndication, indication = null) {},
        ) {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                // Pinned, the grab area: the handle and the child picker (about 80 dp).
                // Full width, so the handle is centered also when there is no picker to widen the column.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    // The handle takes the selected child's color, with one child too (SPEC §6.3). A
                    // drag isn't reachable by a screen reader, so it is also a "Close" button for one.
                    val handle = if (selectedChild != null) childColor(selectedChild, st.colorIndex(selectedChild.id)) else scheme.outline
                    val closeLabel = stringResource(R.string.sheet_close_cd)
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(48.dp, HANDLE_ROW)
                            .semantics {
                                role = Role.Button
                                contentDescription = closeLabel
                                onClick { onClose(); true }
                            },
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).background(handle, RoundedCornerShape(2.dp)))
                    }

                    // The child picker, only with several active children (SPEC §6.3). The selection
                    // is this phone's active child, so the sheet opens on the last one used.
                    if (st.severalChildren) {
                        Spacer(Modifier.height(2.dp))
                        ChildPicker(
                            childOptions(st.children, fmt, st::colorIndex),
                            st.childId,
                            contentDescription = { res.getString(R.string.picker_record_for_cd, it) },
                            status = { id -> timerStatus(st, id, now(), fmt, colors, res) },
                            onExpandedChange = { open ->
                                sheet.menuOpen = open
                                if (!open) sheet.restartCountdown()
                            },
                        ) { id ->
                            sheet.touch()
                            if (id != st.childId) appScope.launch { repository.setActiveChild(id) }
                        }
                        // Pinned with the picker, so the content scrolls away below a gap, not right under it.
                        Spacer(Modifier.height(PICKER_GAP))
                    }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    // The first block's gap keeps the 3b spacing: 16 dp below the picker (with the pinned
                    // gap), or below the handle bar (which ends 10 dp above the header's bottom) without one.
                    val lead = if (st.severalChildren) 16.dp - PICKER_GAP else 16.dp - (HANDLE_ROW - 14.dp)
                    val cards = st.running.isNotEmpty() || st.noEnd.isNotEmpty() || st.autoEnded.isNotEmpty()

                    // Running and "has no end" cards. They scroll with the sections: pinned, two of
                    // them at a large font would leave no room for anything else (DECISIONS).
                    if (cards) {
                        Spacer(Modifier.height(lead))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TimerCards(st, now, fmt, repository, host = sheet.host, focusEventId = focusEventId)
                        }
                    }

                    sectionsToShow(st).forEachIndexed { i, section ->
                        val top = if (i > 0) 22.dp else if (cards) 16.dp else lead
                        // Keyed by name: an enum's hash differs between processes, so saved state wouldn't come back.
                        key(section.name) {
                            when (section) {
                                SheetSection.FEED -> FeedSection(top, st, now, fmt, colors, sheet, repository)
                                SheetSection.MEDS -> MedsSection(top, st, fmt, colors, sheet, repository)
                                SheetSection.SLEEP -> SleepSection(top, st, now, fmt, colors, sheet, repository)
                                SheetSection.DIAPER -> DiaperSection(top, st, now, fmt, colors, sheet, repository)
                                SheetSection.PUMP -> PumpSection(top, st, now, fmt, colors, sheet, repository)
                            }
                        }
                    }
                }

                // Pinned: the note editor (opened from the footer), the strip and the footer.
                if (sheet.editor == Editor.NOTE) {
                    HorizontalDivider(color = scheme.outlineVariant)
                    Box(Modifier.padding(16.dp)) {
                        NoteEditor { text ->
                            sheet.editor = Editor.NONE
                            sheet.act { repository.recordNote(text, Source.SHEET) }
                        }
                    }
                }
                sheet.strip?.let { s ->
                    ConfirmationStrip(
                        s, st.settings.unit, fmt,
                        onUndo = { sheet.undo(s) },
                        onSkip = { sheet.act { repository.setSkipped(s.done.info.eventId, true, Source.SHEET) } },
                        onEarlier = { m -> sheet.act { repository.adjust(s.done.undoId, PickedTime.minutesBefore(s.base, m)) } },
                        onPick = {
                            val title = when (s.done.info.verb) {
                                Verb.PAUSE -> R.string.sheet_pick_pause
                                Verb.RESUME -> R.string.sheet_pick_resume
                                Verb.SWITCH -> R.string.sheet_pick_switch
                                else -> R.string.sheet_pick_start
                            }
                            sheet.picker = PickerRequest(title, s.done.info.at) { lt ->
                                val at = PickedTime.latestAtOrBefore(lt, System.currentTimeMillis(), ZoneId.systemDefault())
                                sheet.act { repository.adjust(s.done.undoId, at) }
                            }
                        },
                    )
                }
                Footer(
                    todaySummary(st, res),
                    synced = { st.lastSync?.let { res.getString(R.string.sync_status_footer, it.peerName, fmt.ago(now() - it.at)) } },
                    showNote = v.shows(Kind.NOTE),
                    noteOpen = sheet.editor == Editor.NOTE,
                    onNote = { sheet.toggle(Editor.NOTE) },
                    onOpenApp = onOpenApp,
                )
            }
        }
    }

    sheet.picker?.let { req ->
        TimePickDialog(
            title = stringResource(req.titleRes),
            initial = req.initial,
            onDismiss = { sheet.picker = null; sheet.touch() },
            onPicked = { lt ->
                sheet.picker = null
                req.onPicked(lt)
            },
        )
    }
}

/** The record sections under the cards, in their order. */
private enum class SheetSection { FEED, MEDS, SLEEP, DIAPER, PUMP }

/** The sections to show: those not hidden on this phone, and Sleep only while no sleep runs. */
private fun sectionsToShow(st: HomeState): List<SheetSection> {
    val v = st.settings.visibility
    return buildList {
        if (v.shows(Kind.BREAST) || v.bottleContents.isNotEmpty()) add(SheetSection.FEED)
        if (visibleMeds(st).isNotEmpty()) add(SheetSection.MEDS)
        // While a sleep runs the section leaves: its card holds [Woke up].
        val sleeping = st.running.any { it.timed.event.kind == Kind.SLEEP && it.timed.event.childId == st.childId }
        if (v.shows(Kind.SLEEP) && !sleeping) add(SheetSection.SLEEP)
        if (v.diapers.isNotEmpty()) add(SheetSection.DIAPER)
        if (v.shows(Kind.PUMP)) add(SheetSection.PUMP)
    }
}

/** The selected child's meds shown on this phone. */
private fun visibleMeds(st: HomeState): List<SupplementPreset> =
    st.presets.filter { it.childId == st.childId && st.settings.visibility.showsPreset(it.id) }

@Composable
private fun FeedSection(top: Dp, st: HomeState, now: () -> Long, fmt: Formats, colors: ChartColors, sheet: QuickSheetState, repository: Repository) {
    val v = st.settings.visibility
    val feeding = st.running.firstOrNull { it.timed.event.kind == Kind.BREAST && it.timed.event.childId == st.childId }
    val showBreast = v.shows(Kind.BREAST)
    val showBottle = v.bottleContents.isNotEmpty()
    val lastWasBottle = st.lastFeed?.kind == Kind.BOTTLE
    Section(
        top, R.string.sheet_feed,
        status = { if (feeding != null) feedingStatus(feeding, now(), fmt) else fmt.sinceLastFeed(st, now()) },
        statusColor = colors.text(if (feeding == null && lastWasBottle) colors.bottle(BottleContent.ofOrNull(st.lastFeed?.subtype) ?: BottleContent.BREAST_MILK) else colors.left),
    ) {
        // Paused: no side is lit, and tapping one resumes on it.
        val runningSide = feeding?.timed?.openSegment?.side
        // The side to offer next: the other one than the last breast feed ended on.
        val nextSide = if (feeding == null && showBreast) st.lastSide?.other else null
        val left = colors.icon(colors.left)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showBreast) {
                for (side in Side.entries) {
                    BigButton(
                        fmt.sideLong(side), Modifier.weight(1f),
                        selected = runningSide == side, outlined = nextSide == side,
                        icon = KindIcons.of(side), iconTint = left, accent = left,
                        badge = if (nextSide == side) stringResource(R.string.sheet_next_side) else null,
                        badgeStart = side == Side.LEFT,
                    ) {
                        if (runningSide != side) sheet.act { repository.tapSide(side, Source.SHEET) }
                    }
                }
            }
            if (showBottle) {
                val bottle = colors.icon(colors.bottle)
                BigButton(
                    stringResource(R.string.sheet_bottle), Modifier.weight(1f), selected = sheet.editor == Editor.BOTTLE,
                    icon = KindIcons.of(Kind.BOTTLE), iconTint = bottle, accent = bottle,
                ) { sheet.toggle(Editor.BOTTLE) }
            }
            if (!showBreast) repeat(2) { Spacer(Modifier.weight(1f)) }
        }
        if (sheet.editor == Editor.BOTTLE && showBottle) {
            Spacer(Modifier.height(12.dp))
            BottleEditor(st, fmt, v.bottleContents) { content, ml ->
                sheet.editor = Editor.NONE
                sheet.act { repository.recordBottle(content, ml, Source.SHEET) }
            }
        }
    }
}

/** Meds: one chip per visible med, two columns; they keep their order whatever their state. */
@Composable
private fun MedsSection(top: Dp, st: HomeState, fmt: Formats, colors: ChartColors, sheet: QuickSheetState, repository: Repository) {
    Section(top, R.string.sheet_meds, status = { null }, statusColor = Color.Unspecified) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (pair in visibleMeds(st).chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (p in pair) {
                        val status = st.meds[p.id]
                        MedChip(fmt.supplement(p.name, p.amount, p.unit), status?.let { medLine(it, st, fmt) }, colors, Modifier.weight(1f)) {
                            val skippable = status == MedStatus.Due || status is MedStatus.Late
                            sheet.act(skippable) { repository.recordSupplement(p.id, Source.SHEET) }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SleepSection(top: Dp, st: HomeState, now: () -> Long, fmt: Formats, colors: ChartColors, sheet: QuickSheetState, repository: Repository) {
    Section(
        top, R.string.sheet_sleep,
        status = { st.awakeSince?.let { stringResource(R.string.sheet_awake_since, fmt.time(it), fmt.duration(now() - it)) } },
        statusColor = colors.text(colors.sleep),
    ) {
        BigButton(
            stringResource(R.string.sheet_asleep), Modifier.fillMaxWidth(),
            icon = KindIcons.of(Kind.SLEEP), iconTint = colors.icon(colors.sleep), iconAbove = false,
        ) { sheet.act { repository.startSleep(Source.SHEET) } }
    }
}

/** Diaper: one equal column per visible type. */
@Composable
private fun DiaperSection(top: Dp, st: HomeState, now: () -> Long, fmt: Formats, colors: ChartColors, sheet: QuickSheetState, repository: Repository) {
    val last = st.lastDiaper
    val lastType = Diaper.ofOrNull(last?.subtype)
    Section(
        top, R.string.sheet_diaper,
        status = { last?.let { fmt.since(fmt.diaper(lastType ?: Diaper.WET), it.startAt, now()) } },
        statusColor = colors.text(lastType?.let(colors::diaper) ?: colors.diaperGeneral),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (d in st.settings.visibility.diapers) {
                BigButton(fmt.diaper(d), Modifier.weight(1f), icon = KindIcons.of(d), iconTint = colors.icon(colors.diaper(d))) {
                    sheet.act { repository.recordDiaper(d, Source.SHEET) }
                }
            }
        }
    }
}

@Composable
private fun PumpSection(top: Dp, st: HomeState, now: () -> Long, fmt: Formats, colors: ChartColors, sheet: QuickSheetState, repository: Repository) {
    Section(
        top, R.string.sheet_other,
        status = { st.lastPump?.let { fmt.since(null, it.startAt, now()) } },
        statusColor = colors.text(colors.pump),
    ) {
        val pump = colors.icon(colors.pump)
        BigButton(
            stringResource(R.string.sheet_pump), Modifier.fillMaxWidth(), selected = sheet.editor == Editor.PUMP,
            icon = KindIcons.of(Kind.PUMP), iconTint = pump, iconAbove = false, accent = pump,
        ) { sheet.toggle(Editor.PUMP) }
        if (sheet.editor == Editor.PUMP) {
            Spacer(Modifier.height(12.dp))
            AmountEditor(st.settings.pumpStartMl, st.settings.unit, fmt) { ml ->
                sheet.editor = Editor.NONE
                sheet.act { repository.recordPump(ml, Source.SHEET) }
            }
        }
    }
}

/**
 * A section: the gap above it ([top]: 16 dp for the first, 22 dp between sections), its header, 8 dp,
 * then its buttons. [status] is read in the header alone, so the live clock redraws only the header.
 */
@Composable
private fun Section(top: Dp, label: Int, status: @Composable () -> String?, statusColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(top))
    SectionHeader(label, status, statusColor)
    Spacer(Modifier.height(8.dp))
    Column(content = content)
}

/** "Feeding · Left · 12m", or "Feed paused · Left · 12m". */
@Composable
private fun feedingStatus(r: RunningTimer, now: Long, fmt: Formats): String {
    val t = r.timed
    val side = fmt.sideLong(t.lastSide ?: Side.LEFT)
    val head = if (t.isPaused) stringResource(R.string.sheet_feed_paused, side) else stringResource(R.string.notif_feeding, side)
    return head + " · " + fmt.duration(Durations.breast(t.segments, now).totalMs)
}

/**
 * A child's running timer on the child menu (Phase 3c), live: "Feeding · 12m", "Feed paused · 12m" or
 * "Asleep · 1h 25m" with the record's icon. The most recently started one when two run.
 */
private fun timerStatus(st: HomeState, childId: String, now: Long, fmt: Formats, colors: ChartColors, res: android.content.res.Resources): RowStatus? {
    val t = st.running.firstOrNull { it.timed.event.childId == childId }?.timed ?: return null
    return if (t.event.kind == Kind.BREAST) {
        val duration = fmt.duration(Durations.breast(t.segments, now).totalMs)
        RowStatus(
            KindIcons.of(t.lastSide ?: Side.LEFT),
            colors.icon(colors.left),
            res.getString(if (t.isPaused) R.string.sheet_feed_paused else R.string.notif_feeding, duration),
            duration,
        )
    } else {
        val duration = fmt.duration(now - t.event.startAt)
        RowStatus(KindIcons.of(Kind.SLEEP), colors.icon(colors.sleep), res.getString(R.string.notif_asleep) + " · " + duration, duration)
    }
}

/**
 * A med chip's status line (SPEC §5.10): ✓ and a time or weekday in green, Due or "N days late" in
 * amber, Skipped and "Next: Sat" and "Last …" in gray; nothing for an As needed med never given.
 */
@Composable
private fun medLine(status: MedStatus, st: HomeState, fmt: Formats): MedLine? {
    val c = MoStor.colors
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val today = DayClock(st.settings.dayStartHour, ZoneId.systemDefault()).dayOf(st.now)
    return when (status) {
        is MedStatus.GivenToday -> MedLine(fmt.time(status.at), R.drawable.ic_given, c.given)
        is MedStatus.GivenThisCycle -> MedLine(fmt.nearDay(status.day, today), R.drawable.ic_given, c.given)
        is MedStatus.Skipped -> MedLine(stringResource(R.string.meds_skipped), R.drawable.ic_skipped, c.skipped)
        is MedStatus.NotItsDay -> MedLine(stringResource(R.string.meds_next, fmt.nearDay(status.next, today)), null, grey)
        MedStatus.Due -> MedLine(stringResource(R.string.meds_due), R.drawable.ic_due, c.dueText, markColor = c.due)
        is MedStatus.Late -> MedLine(
            pluralStringResource(R.plurals.meds_late, status.days, status.days), R.drawable.ic_overdue, c.dueText, markColor = c.due,
        )
        is MedStatus.AsNeededGiven -> {
            val last = if (status.lastDay == today) fmt.time(status.lastAt) else fmt.nearDay(status.lastDay, today)
            val times = if (status.countToday > 1) " · " + stringResource(R.string.meds_times, status.countToday) else ""
            MedLine(stringResource(R.string.meds_last, last) + times, null, grey)
        }
        MedStatus.NeverGiven -> null
    }
}

private fun todaySummary(st: HomeState, r: android.content.res.Resources): String {
    val c = st.today
    val v = st.settings.visibility
    val parts = buildList {
        if (v.showsFeeds) add(r.getQuantityString(R.plurals.count_feeds, c.feeds, c.feeds))
        if (v.shows(Kind.DIAPER)) {
            add(r.getQuantityString(R.plurals.count_wet, c.wet, c.wet))
            add(r.getQuantityString(R.plurals.count_poop, c.poop, c.poop))
        }
    }
    return r.getString(R.string.sheet_today_list, parts.joinToString(" · "))
}

/** A section's CAPS name on the left; on the right, live, when the last one happened, in its color. */
@Composable
private fun SectionHeader(res: Int, status: @Composable () -> String?, statusColor: Color) {
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(caps(stringResource(res)), style = MoStor.type.sectionLabel, color = MoStor.colors.label)
        val text = status()
        if (text != null) {
            Text(
                text,
                style = MoStor.type.status,
                color = statusColor,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
    }
}

/** A med: the capsule, the name, and under it the status line (SPEC §5.10). The chip stays neutral. */
@Composable
internal fun MedChip(name: String, line: MedLine?, colors: ChartColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val s = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = s.surfaceContainerHigh, contentColor = s.onSurface),
        border = BorderStroke(1.dp, s.outlineVariant),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        RecordIcon(KindIcons.of(Kind.SUPPLEMENT), colors.icon(colors.supplement), 22.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            FitText(name, MoStor.type.button)
            if (line != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    line.mark?.let { Icon(painterResource(it), contentDescription = null, tint = line.markColor, modifier = Modifier.size(13.dp)) }
                    FitText(line.text, MoStor.type.status.copy(fontSize = 12.sp, lineHeight = 16.sp), color = line.color)
                }
            }
        }
    }
}

/** The handle's row: 24 dp tall (the bar at 10 dp), 48 dp wide to reach with a screen reader. */
private val HANDLE_ROW = 24.dp

/** The pinned space under the child picker, which the scrolling content passes under. */
private val PICKER_GAP = 10.dp
