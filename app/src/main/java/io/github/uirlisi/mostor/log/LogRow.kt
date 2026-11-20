package io.github.uirlisi.mostor.log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.uirlisi.mostor.ui.AppUi
import io.github.uirlisi.mostor.ui.MoStor
import io.github.uirlisi.mostor.ui.FitText
import io.github.uirlisi.mostor.ui.ChildTag
import io.github.uirlisi.mostor.core.Kind
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.VolumeUnit
import io.github.uirlisi.mostor.data.DayItem
import io.github.uirlisi.mostor.sheet.BigButton
import io.github.uirlisi.mostor.ui.ChildDot
import io.github.uirlisi.mostor.ui.KindIcons
import io.github.uirlisi.mostor.ui.iconOf
import io.github.uirlisi.mostor.ui.RecordIcon
import io.github.uirlisi.mostor.ui.chartColors
import io.github.uirlisi.mostor.ui.of
import io.github.uirlisi.mostor.ui.Formats
import io.github.uirlisi.mostor.ui.childColor

/**
 * One record in Today's timeline and the Log (Phase 3b look): the time in a 56 dp right-aligned
 * column, a 32 dp disc with the record's icon, the title over an optional detail line ("until
 * 2:45 PM", "→ next day"), and the duration on the right. With several children the detail line
 * starts with a chip in the child's color. A "possible duplicate" badge (SPEC §5.6). Tap to edit.
 */
@Composable
fun LogRow(
    item: DayItem,
    fmt: Formats,
    now: Long,
    unit: VolumeUnit,
    dayEndMs: Long?,
    child: Child?,
    childIndex: Int,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
) {
    val e = item.event
    val scheme = MaterialTheme.colorScheme
    // Shown once, under the day it started, marked when it runs past the day's end (SPEC §5.1).
    val end = e.endAt ?: if (e.status == EventStatus.RUNNING) now else null
    val crossesDay = dayEndMs != null && end != null && end > dayEndMs
    val parts = fmt.logParts(e, item.timed, now, unit)
    val detail = listOfNotNull(parts.detail, stringResource(R.string.log_next_day).takeIf { crossesDay }).joinToString(" · ").ifEmpty { null }
    val tint = chartColors().iconOf(e.kind, e.subtype)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FitText(
            fmt.time(e.startAt),
            MaterialTheme.typography.bodyMedium.merge(MoStor.type.tabular).copy(fontSize = 14.sp),
            Modifier.width(56.dp),
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        Box(
            Modifier.padding(horizontal = 10.dp).size(32.dp).background(tint.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { RecordIcon(KindIcons.of(e.kind, e.subtype), tint, 18.dp) }
        Column(Modifier.weight(1f)) {
            Text(
                parts.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = if (e.kind == Kind.NOTE) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (child != null || detail != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    child?.let { ChildTag(fmt.childName(it.name), childColor(it, childIndex), small = true) }
                    detail?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = MoStor.colors.label) }
                }
            }
            if (item.duplicates.isNotEmpty()) {
                AssistChip(
                    onClick = onDuplicate,
                    label = { Text(stringResource(R.string.log_possible_duplicate)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        parts.duration?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium.merge(MoStor.type.tabular).copy(fontSize = 14.sp),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * The dialog for [item] and its possible duplicates among [shown] (the records on screen), on Today
 * and in the log: [Keep this one] deletes the others as one action, with Undo. [onClose] closes it.
 */
@Composable
fun DuplicateDialog(ui: AppUi, item: DayItem, shown: List<DayItem>, now: Long, unit: VolumeUnit, onClose: () -> Unit) {
    val group = listOf(item) + shown.filter { it.event.id in item.duplicates }
    DuplicateDialog(group, ui.fmt, now, unit, onKeep = { keep ->
        onClose()
        ui.act({ ui.repository.deleteAll(group.map { it.event.id }.filter { it != keep }) })
    }, onDismiss = onClose)
}

/** The two (or more) records that may be one, each with [Keep this one]; the others are deleted with Undo. */
@Composable
private fun DuplicateDialog(
    items: List<DayItem>,
    fmt: Formats,
    now: Long,
    unit: VolumeUnit,
    onKeep: (keepId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.dup_text))
                for (it in items) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(fmt.time(it.event.startAt) + " · " + fmt.logLine(it.event, it.timed, now, unit))
                            BigButton(stringResource(R.string.dup_keep), Modifier.fillMaxWidth()) { onKeep(it.event.id) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dup_keep_both)) } },
    )
}
