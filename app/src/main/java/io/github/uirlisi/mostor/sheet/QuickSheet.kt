package io.github.uirlisi.mostor.sheet

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Durations
import io.github.uirlisi.mostor.core.DayClock
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.MedStatus
import io.github.uirlisi.mostor.core.PickedTime
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.SupplementPreset
import io.github.uirlisi.mostor.core.Volume
import io.github.uirlisi.mostor.core.VolumeUnit
import io.github.uirlisi.mostor.data.Done
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
import io.github.uirlisi.mostor.ui.childOptions
import io.github.uirlisi.mostor.ui.chartColors
import io.github.uirlisi.mostor.ui.childColor
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Editor { NONE, BOTTLE, PUMP, NOTE }

/** The confirmation strip: the last action, its Undo, and for starts the "started earlier" fixes. */
private data class Strip(val done: Done, val base: Long, val outcome: Outcome = Outcome.DONE, val skippable: Boolean = false) {
    enum class Outcome { DONE, UNDONE, UNDO_REFUSED }
}

private data class PickerRequest(val titleRes: Int, val initial: Long, val onPicked: (java.time.LocalTime) -> Unit)

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
    val view = LocalView.current
    val fmt = remember { Formats(context) }
    val colors = chartColors()
    val state by remember { repository.homeStates() }.collectAsStateWithLifecycle(initialValue = null)

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var editor by remember { mutableStateOf(Editor.NONE) }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }
    var strip by remember { mutableStateOf<Strip?>(null) }
    // The child menu is open, or a finger is down on the sheet: no auto-close meanwhile.
    var menuOpen by remember { mutableStateOf(false) }
    var fingerDown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // Closes 4 s (setting; or never) after the last tap, but not while something is being typed or
    // picked, the child menu is open, or a finger is on the sheet (pulling it down, Phase 3c).
    val autoCloseMs = state?.settings?.let { it.autoCloseMs ?: NEVER } ?: 4_000L
    LaunchedEffect(lastTap, editor, picker, autoCloseMs, menuOpen, fingerDown) {
        if (lastTap > 0 && editor == Editor.NONE && picker == null && autoCloseMs != NEVER && !menuOpen && !fingerDown) {
            delay(autoCloseMs)
            onClose()
        }
    }

    fun touch() {
        lastTap = SystemClock.uptimeMillis()
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

    val host = remember {
        TimerHost(
            act = { block -> act(block = block) },
            pickTime = { title, initial, onPicked -> picker = PickerRequest(title, initial, onPicked) },
            quietly = { block -> touch(); appScope.launch { block() } },
        )
    }
    // Opened from a launcher shortcut: record it, so the sheet shows its strip with Undo.
    val loaded = state != null
    LaunchedEffect(loaded) {
        if (!loaded || shortcut == null) return@LaunchedEffect
        when (shortcut) {
            QuickSheetActivity.ACTION_WET -> act { repository.recordDiaper(Diaper.WET, Source.SHEET) }
            QuickSheetActivity.ACTION_POOP -> act { repository.recordDiaper(Diaper.POOP, Source.SHEET) }
            QuickSheetActivity.ACTION_LEFT -> act { repository.tapSide(Side.LEFT, Source.SHEET) }
            QuickSheetActivity.ACTION_RIGHT -> act { repository.tapSide(Side.RIGHT, Source.SHEET) }
        }
    }
    val zone = ZoneId.systemDefault()
    val noIndication = remember { MutableInteractionSource() }
    val st = state

    // Pull down to close (Phase 3c): the sheet sits at Shown (offset 0) or Hidden (its height).
    val drag = remember { AnchoredDraggableState(SheetValue.SHOWN) }
    var sheetHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val flingPx = with(density) { CLOSE_FLING.toPx() }
    fun offset() = drag.offset.takeUnless { it.isNaN() } ?: 0f

    /**
     * Where a pulled sheet goes when let go: Hidden past a quarter of its height or after a downward
     * flick, else back to Shown. Hidden closes the sheet, as a tap outside does; springing back
     * restarts the auto-close countdown. False when the sheet hadn't moved (nothing to settle).
     */
    suspend fun settle(velocity: Float): Boolean {
        val pulled = offset()
        if (pulled <= 0f) return false
        val close = when {
            velocity > flingPx -> true
            velocity < -flingPx -> false
            else -> pulled > CLOSE_FRACTION * sheetHeight
        }
        drag.animateTo(if (close) SheetValue.HIDDEN else SheetValue.SHOWN)
        if (close) onClose() else if (lastTap > 0) touch()
        return true
    }

    // The scrolling content hands the sheet what it can't use: a downward scroll at its top pulls
    // the sheet, and an upward one while the sheet is pulled moves the sheet back first (as M3 sheets).
    val nested = remember {
        object : NestedScrollConnection {
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

    Box(
        Modifier
            .fillMaxSize()
            // The scrim lightens as the sheet goes down.
            .drawBehind {
                if (st != null) {
                    val shown = if (sheetHeight > 0) (1f - offset() / sheetHeight).coerceIn(0f, 1f) else 1f
                    drawRect(Color.Black.copy(alpha = 0.4f * shown))
                }
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
                .offset { IntOffset(0, offset().roundToInt()) }
                .fillMaxWidth()
                .statusBarsPadding()
                .onSizeChanged { size ->
                    sheetHeight = size.height
                    drag.updateAnchors(
                        DraggableAnchors {
                            SheetValue.SHOWN at 0f
                            SheetValue.HIDDEN at size.height.toFloat()
                        },
                    )
                }
                // No auto-close while a finger is on the sheet; letting go restarts the countdown.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        fingerDown = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            fingerDown = false
                            if (lastTap > 0) touch()
                        }
                    }
                }
                .nestedScroll(nested)
                // A vertical drag anywhere on the sheet, past the touch slop; taps still reach the buttons.
                .draggable(
                    state = rememberDraggableState { delta -> drag.dispatchRawDelta(delta) },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> settle(velocity) },
                )
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
                            contentDescription = { context.getString(R.string.picker_record_for_cd, it) },
                            status = { id -> timerStatus(st, id, now, fmt, colors, context) },
                            onExpandedChange = { open ->
                                menuOpen = open
                                if (!open && lastTap > 0) touch()
                            },
                        ) { id ->
                            touch()
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
                    val cards = st.running.isNotEmpty() || st.noEnd.isNotEmpty()

                    // Running and "has no end" cards. They scroll with the sections: pinned, two of
                    // them at a large font would leave no room for anything else (DECISIONS).
                    if (cards) {
                        Spacer(Modifier.height(lead))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TimerCards(st, now, fmt, repository, host, focusEventId)
                        }
                    }

                    var sections = 0
                    fun gap(): Dp = if (sections++ > 0) 22.dp else if (cards) 16.dp else lead

                    // Feed
                    val feeding = st.running.firstOrNull { it.timed.event.kind == Kind.BREAST && it.timed.event.childId == st.childId }
                    val showBreast = v.shows(Kind.BREAST)
                    val showBottle = v.bottleContents.isNotEmpty()
                    if (showBreast || showBottle) {
                        val lastWasBottle = st.lastFeed?.kind == Kind.BOTTLE
                        Section(
                            gap(), R.string.sheet_feed,
                            if (feeding != null) feedingStatus(feeding, now, fmt) else fmt.sinceLastFeed(st, now),
                            colors.text(if (feeding == null && lastWasBottle) colors.bottle(BottleContent.ofOrNull(st.lastFeed?.subtype) ?: BottleContent.BREAST_MILK) else colors.left),
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
                                        ) {
                                            if (runningSide != side) act { repository.tapSide(side, Source.SHEET) }
                                        }
                                    }
                                }
                                if (showBottle) {
                                    val bottle = colors.icon(colors.bottle)
                                    BigButton(
                                        stringResource(R.string.sheet_bottle), Modifier.weight(1f), selected = editor == Editor.BOTTLE,
                                        icon = KindIcons.of(Kind.BOTTLE), iconTint = bottle, accent = bottle,
                                    ) { toggle(Editor.BOTTLE) }
                                }
                                if (!showBreast) repeat(2) { Spacer(Modifier.weight(1f)) }
                            }
                            if (editor == Editor.BOTTLE && showBottle) {
                                Spacer(Modifier.height(12.dp))
                                BottleEditor(st, fmt, v.bottleContents) { content, ml ->
                                    editor = Editor.NONE
                                    act { repository.recordBottle(content, ml, Source.SHEET) }
                                }
                            }
                        }
                    }

                    // Meds: one chip per visible med, two columns; they keep their order whatever their state.
                    val meds = st.presets.filter { it.childId == st.childId && v.showsPreset(it.id) }
                    if (meds.isNotEmpty()) {
                        Section(gap(), R.string.sheet_meds, null, Color.Unspecified) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                for (pair in meds.chunked(2)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        for (p in pair) {
                                            val status = st.meds[p.id]
                                            MedChip(fmt.supplement(p.name, p.amount, p.unit), status?.let { medLine(it, st, fmt) }, colors, Modifier.weight(1f)) {
                                                val skippable = status == MedStatus.Due || status is MedStatus.Late
                                                act(skippable) { repository.recordSupplement(p.id, Source.SHEET) }
                                            }
                                        }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    // Sleep. While a sleep runs the section leaves: its card holds [Woke up].
                    val sleeping = st.running.any { it.timed.event.kind == Kind.SLEEP && it.timed.event.childId == st.childId }
                    if (v.shows(Kind.SLEEP) && !sleeping) {
                        Section(
                            gap(), R.string.sheet_sleep,
                            st.awakeSince?.let { stringResource(R.string.sheet_awake_since, fmt.time(it), fmt.duration(now - it)) },
                            colors.text(colors.sleep),
                        ) {
                            BigButton(
                                stringResource(R.string.sheet_asleep), Modifier.fillMaxWidth(),
                                icon = KindIcons.of(Kind.SLEEP), iconTint = colors.icon(colors.sleep), iconAbove = false,
                            ) { act { repository.startSleep(Source.SHEET) } }
                        }
                    }

                    // Diaper: one equal column per visible type.
                    val diapers = v.diapers
                    if (diapers.isNotEmpty()) {
                        val last = st.lastDiaper
                        val lastType = Diaper.ofOrNull(last?.subtype)
                        Section(
                            gap(), R.string.sheet_diaper,
                            last?.let { fmt.since(fmt.diaper(lastType ?: Diaper.WET), it.startAt, now) },
                            colors.text(lastType?.let(colors::diaper) ?: colors.diaperGeneral),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                for (d in diapers) {
                                    BigButton(fmt.diaper(d), Modifier.weight(1f), icon = KindIcons.of(d), iconTint = colors.icon(colors.diaper(d))) {
                                        act { repository.recordDiaper(d, Source.SHEET) }
                                    }
                                }
                            }
                        }
                    }

                    // Pump
                    if (v.shows(Kind.PUMP)) {
                        Section(gap(), R.string.sheet_other, st.lastPump?.let { fmt.since(null, it.startAt, now) }, colors.text(colors.pump)) {
                            val pump = colors.icon(colors.pump)
                            BigButton(
                                stringResource(R.string.sheet_pump), Modifier.fillMaxWidth(), selected = editor == Editor.PUMP,
                                icon = KindIcons.of(Kind.PUMP), iconTint = pump, iconAbove = false, accent = pump,
                            ) { toggle(Editor.PUMP) }
                            if (editor == Editor.PUMP) {
                                Spacer(Modifier.height(12.dp))
                                AmountEditor(st.settings.pumpStartMl, st.settings.unit, fmt) { ml ->
                                    editor = Editor.NONE
                                    act { repository.recordPump(ml, Source.SHEET) }
                                }
                            }
                        }
                    }
                }

                // Pinned: the note editor (opened from the footer), the strip and the footer.
                if (editor == Editor.NOTE) {
                    HorizontalDivider(color = scheme.outlineVariant)
                    Box(Modifier.padding(16.dp)) {
                        NoteEditor { text ->
                            editor = Editor.NONE
                            act { repository.recordNote(text, Source.SHEET) }
                        }
                    }
                }
                strip?.let { s ->
                    ConfirmationStrip(
                        s, st.settings.unit, fmt,
                        onUndo = { undo(s) },
                        onSkip = { act { repository.setSkipped(s.done.info.eventId, true, Source.SHEET) } },
                        onEarlier = { m -> act { repository.adjust(s.done.undoId, PickedTime.minutesBefore(s.base, m)) } },
                        onPick = {
                            val title = when (s.done.info.verb) {
                                Verb.PAUSE -> R.string.sheet_pick_pause
                                Verb.RESUME -> R.string.sheet_pick_resume
                                Verb.SWITCH -> R.string.sheet_pick_switch
                                else -> R.string.sheet_pick_start
                            }
                            picker = PickerRequest(title, s.done.info.at) { lt ->
                                val at = PickedTime.latestAtOrBefore(lt, System.currentTimeMillis(), zone)
                                act { repository.adjust(s.done.undoId, at) }
                            }
                        },
                    )
                }
                Footer(
                    todaySummary(st, context),
                    st.lastSync?.let { context.getString(R.string.sync_status_footer, it.peerName, fmt.ago(now - it.at)) },
                    showNote = v.shows(Kind.NOTE),
                    noteOpen = editor == Editor.NOTE,
                    onNote = { toggle(Editor.NOTE) },
                    onOpenApp = onOpenApp,
                )
            }
        }
    }

    picker?.let { req ->
        TimePickDialog(
            title = stringResource(req.titleRes),
            initial = req.initial,
            onDismiss = { picker = null; touch() },
            onPicked = { lt ->
                picker = null
                req.onPicked(lt)
            },
        )
    }
}

/** A section: the gap above it ([top]: 16 dp for the first, 22 dp between sections), its header, 8 dp, then its buttons. */
@Composable
private fun Section(top: Dp, label: Int, status: String?, statusColor: Color, content: @Composable ColumnScope.() -> Unit) {
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
private fun timerStatus(st: HomeState, childId: String, now: Long, fmt: Formats, colors: ChartColors, context: android.content.Context): RowStatus? {
    val t = st.running.firstOrNull { it.timed.event.childId == childId }?.timed ?: return null
    return if (t.event.kind == Kind.BREAST) {
        val duration = fmt.duration(Durations.breast(t.segments, now).totalMs)
        RowStatus(
            KindIcons.of(t.lastSide ?: Side.LEFT),
            colors.icon(colors.left),
            context.getString(if (t.isPaused) R.string.sheet_feed_paused else R.string.notif_feeding, duration),
            duration,
        )
    } else {
        val duration = fmt.duration(now - t.event.startAt)
        RowStatus(KindIcons.of(Kind.SLEEP), colors.icon(colors.sleep), context.getString(R.string.notif_asleep) + " · " + duration, duration)
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

private fun todaySummary(st: HomeState, context: android.content.Context): String {
    val r = context.resources
    val c = st.today
    val v = st.settings.visibility
    val parts = buildList {
        if (v.shows(Kind.BREAST) || v.shows(Kind.BOTTLE)) add(r.getQuantityString(R.plurals.count_feeds, c.feeds, c.feeds))
        if (v.shows(Kind.DIAPER)) {
            add(r.getQuantityString(R.plurals.count_wet, c.wet, c.wet))
            add(r.getQuantityString(R.plurals.count_poop, c.poop, c.poop))
        }
    }
    return context.getString(R.string.sheet_today_list, parts.joinToString(" · "))
}

/** A section's CAPS name on the left; on the right, live, when the last one happened, in its color. */
@Composable
private fun SectionHeader(res: Int, status: String?, statusColor: Color) {
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(caps(stringResource(res)), style = MoStor.type.sectionLabel, color = MoStor.colors.label)
        if (status != null) {
            Text(
                status,
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

@Composable
private fun Footer(summary: String, synced: String?, showNote: Boolean, noteOpen: Boolean, onNote: () -> Unit, onOpenApp: () -> Unit) {
    val s = MaterialTheme.colorScheme
    HorizontalDivider(color = s.outlineVariant)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Today's counts, and under them the last sync with another phone (Phase 5).
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(summary, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = s.onSurfaceVariant)
            synced?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = s.onSurfaceVariant) }
        }
        if (showNote) {
            IconButton(onClick = onNote, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(R.drawable.ic_note),
                    contentDescription = stringResource(R.string.sheet_note_cd),
                    tint = if (noteOpen) s.primary else s.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        TextButton(onClick = onOpenApp) { Text(stringResource(R.string.sheet_open_app), style = MoStor.type.button) }
    }
}

@Composable
private fun ConfirmationStrip(
    s: Strip,
    unit: VolumeUnit,
    fmt: Formats,
    onUndo: () -> Unit,
    onSkip: () -> Unit,
    onEarlier: (Int) -> Unit,
    onPick: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = fmt.action(s.done.info, unit) + " · " + fmt.time(s.done.info.at)
                val text = when (s.outcome) {
                    Strip.Outcome.DONE -> label
                    Strip.Outcome.UNDONE -> stringResource(R.string.sheet_undone, label)
                    Strip.Outcome.UNDO_REFUSED -> stringResource(R.string.sheet_undo_refused)
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                // A due or late med's dose can be turned into a skip (SPEC §5.10).
                if (s.outcome == Strip.Outcome.DONE && s.skippable) {
                    ChipButton(stringResource(R.string.meds_skip), Modifier.padding(end = 8.dp).widthIn(min = 64.dp), border = MoStor.colors.stripOutline) { onSkip() }
                }
                if (s.outcome == Strip.Outcome.DONE) {
                    Button(onClick = onUndo, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.action_undo), style = MoStor.type.button)
                    }
                }
            }
            val isStart = s.done.info.verb in ADJUSTABLE
            if (isStart && s.outcome == Strip.Outcome.DONE) {
                Text(
                    stringResource(R.string.sheet_started_earlier),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MoStor.colors.onStripVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val edge = MoStor.colors.stripOutline
                    for (m in START_OFFSETS) ChipButton(stringResource(R.string.minus_minutes, m), Modifier.weight(1f), border = edge) { onEarlier(m) }
                    ChipButton(stringResource(R.string.sheet_pick_time), Modifier.weight(1f), border = edge) { onPick() }
                }
            }
        }
    }
}

@Composable
private fun BottleEditor(st: HomeState, fmt: Formats, contents: List<BottleContent>, onSave: (BottleContent, Double) -> Unit) {
    var content by remember { mutableStateOf(st.settings.lastBottleContent.takeIf { it in contents } ?: contents.first()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The content toggle only when both breast milk and formula are shown on this phone (SPEC §6.3).
        if (contents.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (c in contents) {
                    BigButton(fmt.bottleContent(c), Modifier.weight(1f), selected = content == c) { content = c }
                }
            }
        }
        AmountEditor(st.settings.bottleStartMl, st.settings.unit, fmt) { ml -> onSave(content, ml) }
    }
}

/** An amount stepper that starts at the last amount (or a round first value), then [Save]. */
@Composable
private fun AmountEditor(lastMl: Double?, unit: VolumeUnit, fmt: Formats, onSave: (Double) -> Unit) {
    var amount by remember { mutableStateOf(lastMl?.let { Volume.snapped(it, unit) } ?: unit.firstAmount) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BigButton(stringResource(R.string.sheet_less), Modifier.widthIn(min = 64.dp)) {
            amount = (amount - unit.step).coerceAtLeast(unit.step)
        }
        Text(
            fmt.volume(Volume.toMl(amount, unit), unit),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        BigButton(stringResource(R.string.sheet_more), Modifier.widthIn(min = 64.dp)) { amount += unit.step }
        BigButton(stringResource(R.string.sheet_save), Modifier.widthIn(min = 88.dp), primary = true) { onSave(Volume.toMl(amount, unit)) }
    }
}

@Composable
private fun NoteEditor(onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.sheet_note_hint)) },
            modifier = Modifier.weight(1f),
        )
        BigButton(stringResource(R.string.sheet_save), Modifier.widthIn(min = 88.dp), primary = true) { if (text.isNotBlank()) onSave(text) }
    }
}

private val START_OFFSETS = listOf(5, 10, 15)

/** The pull-to-close sheet's two places. */
private enum class SheetValue { SHOWN, HIDDEN }

/** Let go past this share of its height, or after a downward flick this fast, and the sheet closes (to tune on the phones). */
private const val CLOSE_FRACTION = 0.25f
private val CLOSE_FLING = 400.dp

/** The handle's row: 24 dp tall (the bar at 10 dp), 48 dp wide to reach with a screen reader. */
private val HANDLE_ROW = 24.dp

/** The pinned space under the child picker, which the scrolling content passes under. */
private val PICKER_GAP = 10.dp

/** Auto-close "Never". */
private const val NEVER = -1L

/** Actions whose time the strip's "Earlier" chips can move. */
private val ADJUSTABLE = setOf(Verb.START, Verb.SWITCH, Verb.PAUSE, Verb.RESUME)
