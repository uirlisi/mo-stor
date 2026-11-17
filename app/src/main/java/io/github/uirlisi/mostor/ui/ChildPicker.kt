package io.github.uirlisi.mostor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.ChildChoice

/** A child in a picker: its id, the name to show and its color. */
data class ChildOption(val id: String, val name: String, val color: Color)

/** [children] as picker options, each in their color ([colorIndex]: their place among all live children). */
fun childOptions(children: List<Child>, fmt: Formats, colorIndex: (String) -> Int): List<ChildOption> =
    children.map { ChildOption(it.id, fmt.childName(it.name), childColor(it, colorIndex(it.id))) }

/** A running timer on a quick sheet menu row: the record's icon in its color, and "Feeding · 12m". */
class RowStatus(val icon: Int, val tint: Color, val text: String, val duration: String)

/** A menu row's height, and the gap between rows. */
private val ROW = 52.dp
private val ROW_GAP = 2.dp

/** The menu's top and bottom padding (Material's dropdown, 8 dp). */
private val MENU_PADDING = 8.dp

/**
 * The single-choice child picker (Phase 3c; docs/PHASE3C_HANDOFF.md, "Child picker"): a 48 dp
 * button in the selected child's color with their dot and name, opening a menu of every child.
 * Tapping a row picks it and closes the menu; a tap outside or Back closes it unchanged. The menu
 * opens under the button, or above it where there is no room below (Patterns, Stats). [status]
 * gives a row's running timer (the quick sheet). [contentDescription] gets the selected name.
 */
@Composable
fun ChildPicker(
    options: List<ChildOption>,
    selected: String,
    contentDescription: (String) -> String,
    modifier: Modifier = Modifier,
    status: (String) -> RowStatus? = { null },
    onExpandedChange: (Boolean) -> Unit = {},
    onSelect: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val current = options.firstOrNull { it.id == selected } ?: options.firstOrNull() ?: return
    var expanded by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    fun open(value: Boolean) {
        expanded = value
        onExpandedChange(value)
    }

    Box(modifier) {
        val shape = RoundedCornerShape(14.dp)
        val fill = current.color.copy(alpha = if (MoStor.colors.dark) 0.15f else 0.10f).compositeOver(scheme.surface)
        val description = contentDescription(current.name)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .onSizeChanged { width = with(density) { it.width.toDp() } }
                .clip(shape)
                .background(fill)
                .border(1.dp, current.color, shape)
                .clickable(role = Role.Button) { open(true) }
                .clearAndSetSemantics {
                    this.contentDescription = description
                    role = Role.Button
                }
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChildDot(current.color, 10.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                current.name,
                style = MoStor.type.button,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Chevron(22.dp)
        }
        PickerMenu(expanded, width, rows = options.size, header = false, onDismiss = { open(false) }) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                for (o in options) {
                    val on = o.id == current.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = ROW)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) o.color.copy(alpha = 0.12f) else Color.Transparent)
                            .selectable(selected = on, role = Role.RadioButton) {
                                open(false)
                                onSelect(o.id)
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChildDot(o.color, 12.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            o.name,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // The timer shares the row with the name (up to half each) and shrinks first.
                        status(o.id)?.let { s ->
                            Row(Modifier.weight(1f, fill = false).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RecordIcon(s.icon, s.tint, 15.dp)
                                Spacer(Modifier.width(4.dp))
                                // Too long for its half (the largest fonts): the icon and the duration only.
                                var short by remember(o.id) { mutableStateOf(false) }
                                Text(
                                    if (short) s.duration else s.text,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { if (it.hasVisualOverflow) short = true },
                                )
                            }
                        }
                        if (on) {
                            Spacer(Modifier.width(8.dp))
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = o.color, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The multi-select child picker (Phase 3c): Today (full width, 44 dp) and the Log ([compact]: 36 dp,
 * sized to its content). The button shows the chosen children's dots as a stack and "All children",
 * one or two names, or "3 children". In the menu, ticking a row adds or removes that child and the
 * menu stays open; "Only" chooses just that child and closes it; "All children" chooses everyone.
 * At least one child stays chosen: the last ticked box can't be unticked.
 */
@Composable
fun ChildFilter(
    options: List<ChildOption>,
    chosen: List<String>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onChange: (List<String>) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ids = options.map { it.id }
    val picked = options.filter { it.id in chosen }.ifEmpty { options }
    val pickedIds = picked.map { it.id }
    val all = ChildChoice.isAll(pickedIds, ids)
    var expanded by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    val label = when {
        all -> stringResource(R.string.picker_all_children)
        picked.size == 1 -> picked[0].name
        picked.size == 2 -> stringResource(R.string.picker_two_children, picked[0].name, picked[1].name)
        else -> pluralStringResource(R.plurals.picker_n_children, picked.size, picked.size)
    }
    val description = stringResource(R.string.picker_show_cd, label)

    Box(modifier) {
        val shape = RoundedCornerShape(if (compact) 10.dp else 14.dp)
        Row(
            Modifier
                .then(if (compact) Modifier else Modifier.fillMaxWidth())
                .heightIn(min = if (compact) 36.dp else 44.dp)
                .onSizeChanged { width = with(density) { it.width.toDp() } }
                .clip(shape)
                .background(scheme.surface)
                .border(1.dp, scheme.outline, shape)
                .clickable(role = Role.Button) { expanded = true }
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                }
                .padding(start = if (compact) 10.dp else 14.dp, end = if (compact) 6.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotStack(picked.map { it.color }, if (compact) 10.dp else 12.dp, scheme.surface)
            Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
            Text(
                label,
                style = MoStor.type.button.copy(fontSize = if (compact) 14.sp else 15.sp),
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (compact) Modifier.widthIn(max = 200.dp) else Modifier.weight(1f),
            )
            if (compact) Spacer(Modifier.width(2.dp))
            Chevron(20.dp)
        }
        // The compact button is narrower than the rows need: the menu is at least as wide as a phone's half.
        PickerMenu(expanded, if (compact) maxOf(width, 260.dp) else width, rows = options.size, header = true, onDismiss = { expanded = false }) {
            // "All children": ticked when everyone is chosen, a dash when only some are.
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ROW)
                    .clip(RoundedCornerShape(10.dp))
                    .triStateToggleable(
                        state = if (all) ToggleableState.On else ToggleableState.Indeterminate,
                        role = Role.Checkbox,
                    ) { if (!all) onChange(ids) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckBox(scheme.primary, scheme.onPrimary, if (all) R.drawable.ic_check else R.drawable.ic_dash)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.picker_all_children),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = scheme.outline)
            Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                for (o in options) {
                    val on = o.id in pickedIds
                    val locked = !ChildChoice.canUntick(pickedIds, o.id)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = ROW)
                            .clip(RoundedCornerShape(10.dp))
                            .toggleable(value = on, enabled = !locked, role = Role.Checkbox) {
                                onChange(ChildChoice.toggle(pickedIds, o.id, ids))
                            }
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.alpha(if (locked) 0.38f else 1f)) {
                            if (on) CheckBox(o.color, Color.White, R.drawable.ic_check) else EmptyBox(o.color)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            o.name,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                expanded = false
                                onChange(listOf(o.id))
                            },
                            modifier = Modifier.heightIn(min = 40.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Text(
                                stringResource(R.string.picker_only),
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                color = scheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The picker's menu: [width] wide, `surfaceContainerHighest` with a 1 dp outline border, radius 16.
 * With more [rows] than fit, it scrolls, capped so that half a row shows at the bottom.
 */
@Composable
private fun PickerMenu(
    expanded: Boolean,
    width: Dp,
    rows: Int,
    header: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 5 rows (4 below the "All children" header and its divider), then the next row cut through its
    // name. The menu's top and bottom padding are outside the scrolling part.
    val cap = MENU_PADDING * 2 + (ROW + ROW_GAP) * (if (header) 4 else 5) + ROW * 0.6f + if (header) ROW + 9.dp else 0.dp
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).heightIn(max = cap).padding(horizontal = 6.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = scheme.surfaceContainerHighest,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, scheme.outline),
    ) {
        content()
    }
}

/** A 20 dp box, radius 6, filled with [fill] and holding [icon] (a check or a dash) in [mark]. */
@Composable
private fun CheckBox(fill: Color, mark: Color, icon: Int) {
    Box(Modifier.size(20.dp).background(fill, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = mark, modifier = Modifier.size(16.dp))
    }
}

/** An unticked 20 dp box: a 2 dp outline in the child's color. */
@Composable
private fun EmptyBox(color: Color) {
    Box(Modifier.size(20.dp).border(2.dp, color, RoundedCornerShape(6.dp)))
}

@Composable
private fun Chevron(size: Dp) {
    Icon(
        painterResource(R.drawable.ic_expand),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(size),
    )
}

/** The chosen children's dots, overlapping by 3 dp, each ringed 2 dp in the button's [ring] color. */
@Composable
private fun DotStack(colors: List<Color>, size: Dp, ring: Color) {
    val step = size - 3.dp
    Box(Modifier.width(size + 4.dp + step * (colors.size - 1)).height(size + 4.dp)) {
        colors.forEachIndexed { i, c ->
            Box(
                Modifier
                    .offset(x = step * i)
                    .size(size + 4.dp)
                    .background(ring, CircleShape)
                    .padding(2.dp)
                    .background(c, CircleShape),
            )
        }
    }
}
