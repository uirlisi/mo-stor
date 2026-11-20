package io.github.uirlisi.mostor.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.BottleContent
import io.github.uirlisi.mostor.core.Diaper
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.core.EventDraft
import io.github.uirlisi.mostor.core.EventEdit
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.TimeField
import io.github.uirlisi.mostor.core.Volume
import io.github.uirlisi.mostor.data.Editing
import io.github.uirlisi.mostor.data.HomeState
import io.github.uirlisi.mostor.data.SaveResult
import io.github.uirlisi.mostor.ui.AppUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The edit screen's state: the record as loaded and the draft, whether it changed, the overlaps
 * it would have, the clock, the time being nudged or picked, and how Save reports back. Made by
 * [rememberEditState]; [eventId] is the record edited, or null for a new [newKind] record.
 */
@Stable
internal class EditState(
    private val ui: AppUi,
    private val scope: CoroutineScope,
    private val eventId: String?,
    private val newKind: Kind?,
    initialState: MutableState<EventDraft?>,
    draftState: MutableState<EventDraft?>,
    loadedHlcState: MutableState<String?>,
    discardAskState: MutableState<Boolean>,
) {
    private val repo = ui.repository

    var home by mutableStateOf<HomeState?>(null)
        private set
    private var original by mutableStateOf<Editing?>(null)
    /** The draft as loaded (or as a new record starts), to tell whether anything changed. */
    var initial by initialState
        private set
    var draft by draftState
    private var loadedHlc by loadedHlcState
    /** The record is gone (deleted meanwhile). */
    var gone by mutableStateOf(false)
        private set
    /** Bumped to load the record again (after a Save found it changed meanwhile). */
    var reloads by mutableIntStateOf(0)
        private set
    /** The time the nudge panel is open for. */
    var field by mutableStateOf<TimeField?>(null)
    var picking by mutableStateOf(false)
    var pickingDate by mutableStateOf(false)
    var discardAsk by discardAskState
    /** Other records the draft overlaps (allowed, only highlighted). */
    var overlaps by mutableStateOf<List<Event>>(emptyList())
        private set
    var saving by mutableStateOf(false)
        private set
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    /** Something to save: a new record always, an edit once it differs from what was loaded. */
    val dirty: Boolean
        get() {
            val d = draft
            return d != null && (eventId == null || d != initial)
        }

    /** Back: asks first when there is something to lose. */
    fun leave() {
        if (dirty) discardAsk = true else ui.back()
    }

    /** Moves the clock on (every 15 s). */
    suspend fun tick() {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    /** Loads the record (or starts a new one), keeping a draft restored with the screen. */
    suspend fun load() {
        val st = repo.homeState()
        home = st
        ui.unit = st.settings.unit
        val d = if (eventId != null) {
            val e = repo.loadForEdit(eventId)
            if (e == null) {
                gone = true
                return
            }
            original = e
            EventEdit.draftOf(e.event, e.segments)
        } else {
            val t = System.currentTimeMillis()
            now = t
            newDraft(newKind ?: Kind.NOTE, st, t)
        }
        // A draft restored with the screen goes on, if the record is still the one it was made from.
        val restored = reloads == 0 && draft != null && initial != null && (eventId == null || loadedHlc == original?.event?.hlc)
        if (!restored) {
            initial = d
            draft = d
            field = null
        }
        loadedHlc = original?.event?.hlc
    }

    /** Looks up what the draft overlaps, once it has stayed the same for 300 ms. */
    suspend fun findOverlaps() {
        val d = draft ?: return
        delay(300)
        overlaps = repo.overlapsFor(d)
    }

    /** Saves [d] (in the app scope, so it finishes even if the screen goes) and reports back. */
    fun save(d: EventDraft) {
        if (saving) return
        saving = true
        scope.launch {
            val r = ui.appScope.async { repo.saveEdit(original, d) }.await()
            saving = false
            when (r) {
                is SaveResult.Saved -> {
                    ui.back()
                    ui.confirm(r.done)
                }
                SaveResult.Unchanged -> ui.back()
                SaveResult.Stale -> {
                    ui.message(ui.fmt.string(R.string.edit_stale))
                    reloads++
                }
                SaveResult.Refused -> ui.message(ui.fmt.string(R.string.edit_refused))
                is SaveResult.Invalid -> Unit // shown inline already
            }
        }
    }
}

@Composable
internal fun rememberEditState(ui: AppUi, eventId: String?, newKind: Kind?): EditState {
    val scope = rememberCoroutineScope()
    // Saved with the screen, so a rotation, a dark-mode switch or Android ending the app keeps what was
    // typed (review 2026-11-19). The record is loaded again; the draft stays unless it changed meanwhile.
    val initial = rememberSaveable(stateSaver = DraftSaver.saver) { mutableStateOf<EventDraft?>(null) }
    val draft = rememberSaveable(stateSaver = DraftSaver.saver) { mutableStateOf<EventDraft?>(null) }
    val loadedHlc = rememberSaveable { mutableStateOf<String?>(null) }
    val discardAsk = rememberSaveable { mutableStateOf(false) }
    val state = remember { EditState(ui, scope, eventId, newKind, initial, draft, loadedHlc, discardAsk) }

    LaunchedEffect(Unit) { state.tick() }
    LaunchedEffect(state.reloads) { state.load() }
    LaunchedEffect(state.draft) { state.findOverlaps() }
    return state
}

/** A new record's starting point: what the quick sheet would record now (SPEC §6.3 defaults). */
private fun newDraft(kind: Kind, st: HomeState, now: Long): EventDraft {
    val base = EventEdit.blank(kind, st.childId, now, st.lastSide?.other ?: Side.LEFT)
    val unit = st.settings.unit
    val v = st.settings.visibility
    return when (kind) {
        Kind.BOTTLE -> base.copy(
            subtype = (v.bottleContents.firstOrNull { it == st.settings.lastBottleContent } ?: v.bottleContents.firstOrNull()
                ?: BottleContent.BREAST_MILK).code,
            amountMl = st.settings.bottleStartMl ?: Volume.toMl(unit.firstAmount, unit),
        )
        Kind.PUMP -> base.copy(amountMl = st.settings.pumpStartMl ?: Volume.toMl(unit.firstAmount, unit))
        Kind.DIAPER -> base.copy(subtype = (v.diapers.firstOrNull() ?: Diaper.WET).code)
        Kind.SUPPLEMENT -> st.presets.firstOrNull { it.childId == base.childId && v.showsPreset(it.id) }?.let { withPreset(base, it) } ?: base
        else -> base
    }
}
