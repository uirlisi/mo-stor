package io.github.uirlisi.mostor.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TextButton
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.ui.MoStorSwitch
import io.github.uirlisi.mostor.R
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.core.Volume
import io.github.uirlisi.mostor.core.VolumeUnit
import io.github.uirlisi.mostor.data.Keys
import io.github.uirlisi.mostor.data.ThemeChoice
import io.github.uirlisi.mostor.ui.applyThemeChoice
import io.github.uirlisi.mostor.ui.childColor
import io.github.uirlisi.mostor.sheet.BigButton
import io.github.uirlisi.mostor.ui.AppUi
import io.github.uirlisi.mostor.ui.Route
import java.time.LocalDate
import java.time.ZoneId

/** The settings pages (SPEC §6.6). [CHILD] takes the child's id, or none for a new child. */
enum class SettingsPage { MAIN, HOUSEHOLD, CHILD, SHOWN, PRESETS, BACKUP, ABOUT }

@Composable
fun SettingsScreen(ui: AppUi, page: SettingsPage = SettingsPage.MAIN, arg: String? = null) {
    when (page) {
        SettingsPage.MAIN -> MainSettings(ui)
        SettingsPage.HOUSEHOLD -> HouseholdPage(ui)
        SettingsPage.CHILD -> ChildEditor(ui, arg)
        SettingsPage.SHOWN -> ShownSettings(ui)
        SettingsPage.PRESETS -> PresetSettings(ui)
        SettingsPage.BACKUP -> BackupPage(ui)
        SettingsPage.ABOUT -> AboutPage(ui)
    }
}

/** Minutes for the overrun choices; −1 stores "never" (LocalSettings reads a negative as never). */
private val FEED_NUDGE = listOf(30, 45, 60, 90, -1)
private val FEED_GIVE_UP = listOf(120, 180, 240, -1)
private val SLEEP_NUDGE = listOf(240, 360, 480, -1)
private val SLEEP_GIVE_UP = listOf(720, 840, 960, -1)
private val AUTO_CLOSE = listOf(2, 4, 6, 10, 0)

@Composable
private fun MainSettings(ui: AppUi) {
    val repo = ui.repository
    val fmt = ui.fmt
    val context = LocalContext.current
    val home by remember { repo.homeStates() }.collectAsStateWithLifecycle(initialValue = null)
    val children by remember { repo.childrenFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val st = home ?: return
    val s = st.settings
    fun set(key: String, value: String?) = ui.quietly { repo.setSetting(key, value) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))

        // Households and phones (SPEC §9.4): this phone's households, and the open one's phones.
        Section(R.string.set_household)
        val households by context.container.households.flow.collectAsStateWithLifecycle()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { ui.open(Route.Settings(SettingsPage.HOUSEHOLD)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(context.container.nameOf(households.active), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_next), contentDescription = null)
        }
        Hint(R.string.set_sync_hint)

        // Children (synced)
        Section(R.string.set_children)
        children.forEachIndexed { i, c ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { ui.open(Route.Settings(SettingsPage.CHILD, c.id)) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(14.dp).background(childColor(c, i), CircleShape))
                Text(fmt.childName(c.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (c.archivedAt != null) Text(stringResource(R.string.set_archived), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(onClick = { ui.open(Route.Settings(SettingsPage.CHILD)) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.set_add_child))
        }

        // Visibility and presets
        Section(R.string.set_types)
        LinkRow(R.string.set_shown) { ui.open(Route.Settings(SettingsPage.SHOWN)) }
        LinkRow(R.string.set_supplements) { ui.open(Route.Settings(SettingsPage.PRESETS)) }

        // Bottle and pump (per phone)
        Section(R.string.set_bottle_pump)
        Label(R.string.set_units)
        ChipRow {
            FilterChip(s.unit == VolumeUnit.ML, { set(Keys.UNIT, VolumeUnit.ML.code) }, { Text(stringResource(R.string.set_unit_ml)) })
            FilterChip(s.unit == VolumeUnit.FL_OZ, { set(Keys.UNIT, VolumeUnit.FL_OZ.code) }, { Text(stringResource(R.string.set_unit_oz)) })
        }
        Label(R.string.set_default_bottle)
        DefaultAmount(s.defaultBottleMl, s.unit, ui) { set(Keys.DEFAULT_BOTTLE_ML, it?.toString()) }
        Label(R.string.set_default_pump)
        DefaultAmount(s.defaultPumpMl, s.unit, ui) { set(Keys.DEFAULT_PUMP_ML, it?.toString()) }

        // Timers (per phone)
        Section(R.string.set_timers)
        Hint(R.string.set_timers_hint)
        LimitChoice(R.string.set_feed_nudge, s.breastLimits.nudgeAfterMs, FEED_NUDGE, ui) { set(Keys.NUDGE_BREAST_MIN, it) }
        LimitChoice(R.string.set_feed_give_up, s.breastLimits.giveUpAfterMs, FEED_GIVE_UP, ui) { set(Keys.GIVE_UP_BREAST_MIN, it) }
        LimitChoice(R.string.set_sleep_nudge, s.sleepLimits.nudgeAfterMs, SLEEP_NUDGE, ui) { set(Keys.NUDGE_SLEEP_MIN, it) }
        LimitChoice(R.string.set_sleep_give_up, s.sleepLimits.giveUpAfterMs, SLEEP_GIVE_UP, ui) { set(Keys.GIVE_UP_SLEEP_MIN, it) }

        // Day start (per phone)
        Section(R.string.set_day_start)
        Hint(R.string.set_day_start_hint)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(stringResource(R.string.sheet_less), Modifier.widthIn(min = 72.dp)) { set(Keys.DAY_START_HOUR, ((s.dayStartHour + 23) % 24).toString()) }
            val at = LocalDate.now().atTime(s.dayStartHour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            Text(fmt.time(at), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 120.dp))
            BigButton(stringResource(R.string.sheet_more), Modifier.widthIn(min = 72.dp)) { set(Keys.DAY_START_HOUR, ((s.dayStartHour + 1) % 24).toString()) }
        }

        // Lock screen (per phone)
        Section(R.string.set_lock_screen)
        SwitchRow(R.string.set_lock_sheet, stringResource(R.string.set_lock_sheet_hint), s.lockScreenSheet) { set(Keys.LOCK_SCREEN_SHEET, it.toString()) }
        SwitchRow(R.string.set_hide_details, stringResource(R.string.set_hide_details_hint, stringResource(R.string.app_name)), s.hideDetailsOnLockScreen) {
            set(Keys.HIDE_DETAILS, it.toString())
        }
        // Android uses the hidden version only when the phone hides lock-screen content (DECISIONS).
        TextButton(onClick = {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }) { Text(stringResource(R.string.set_notification_settings)) }
        SwitchRow(R.string.set_keep_notification, stringResource(R.string.set_keep_notification_hint), s.keepNotification) {
            set(Keys.KEEP_NOTIFICATION, it.toString())
        }

        // Quick sheet (per phone)
        Section(R.string.set_quick_sheet)
        Label(R.string.set_auto_close)
        ChipRow {
            for (sec in AUTO_CLOSE) {
                val selected = if (sec == 0) s.autoCloseMs == null else s.autoCloseMs == sec * 1_000L
                FilterChip(selected, { set(Keys.AUTO_CLOSE_S, sec.toString()) }, {
                    Text(if (sec == 0) stringResource(R.string.set_never) else stringResource(R.string.set_seconds, sec))
                })
            }
        }

        // Theme (per phone, Phase 6)
        Section(R.string.set_theme)
        ChipRow {
            for ((choice, label) in listOf(
                ThemeChoice.SYSTEM to R.string.set_theme_system,
                ThemeChoice.LIGHT to R.string.set_theme_light,
                ThemeChoice.DARK to R.string.set_theme_dark,
            )) {
                FilterChip(s.theme == choice, {
                    set(Keys.THEME, choice.code)
                    applyThemeChoice(context, choice)
                }, { Text(stringResource(label)) })
            }
        }
        Hint(R.string.set_theme_hint)

        // Backup and import
        Section(R.string.set_backup)
        Hint(R.string.set_backup_warning)
        LinkRow(R.string.set_backup_open) { ui.open(Route.Settings(SettingsPage.BACKUP)) }

        Section(R.string.set_about)
        LinkRow(R.string.set_about) { ui.open(Route.Settings(SettingsPage.ABOUT)) }
    }
}

/** "Last used", or a fixed starting amount with [−] [+]. */
@Composable
private fun DefaultAmount(ml: Double?, unit: VolumeUnit, ui: AppUi, onChange: (Double?) -> Unit) {
    val amount = ml?.let { Volume.snapped(it, unit) }
    ChipRow {
        FilterChip(ml == null, { onChange(null) }, { Text(stringResource(R.string.set_last_used)) })
        BigButton(stringResource(R.string.sheet_less), Modifier.widthIn(min = 56.dp)) {
            val base = amount ?: unit.firstAmount
            onChange(Volume.toMl((base - unit.step).coerceAtLeast(unit.step), unit))
        }
        Text(
            if (amount == null) "—" else ui.fmt.volume(Volume.toMl(amount, unit), unit),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        BigButton(stringResource(R.string.sheet_more), Modifier.widthIn(min = 56.dp)) {
            onChange(Volume.toMl((amount?.plus(unit.step)) ?: unit.firstAmount, unit))
        }
    }
}

/** A nudge or give-up time: fixed choices plus "Never". A value set another way shows as its own chip. */
@Composable
private fun LimitChoice(label: Int, currentMs: Long?, choices: List<Int>, ui: AppUi, onPick: (String) -> Unit) {
    Label(label)
    val current = currentMs?.let { (it / 60_000).toInt() } ?: -1
    ChipRow {
        val all = if (current in choices) choices else (choices.dropLast(1) + current + -1).distinct().sortedBy { if (it < 0) Int.MAX_VALUE else it }
        for (m in all) {
            FilterChip(
                current == m,
                { onPick(m.toString()) },
                { Text(if (m < 0) stringResource(R.string.set_never) else ui.fmt.duration(m * 60_000L)) },
            )
        }
    }
}

@Composable
internal fun Section(res: Int) = Section(AnnotatedString(stringResource(res)))

@Composable
internal fun Section(text: AnnotatedString) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun Label(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Hint(res: Int) = Hint(AnnotatedString(stringResource(res)))

/**
 * A string whose one argument is the app's name ("Mo Stór", or "Mo Stór dev"): no string but
 * `app_name` spells it. Extra arguments are ignored, so a string without the name works too.
 */
@Composable
internal fun namedString(res: Int): String = stringResource(res, stringResource(R.string.app_name))

@Composable
internal fun Hint(text: AnnotatedString) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A settings page's action button. */
@Composable
internal fun SettingsButton(res: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(res)) }
}

@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
internal fun LinkRow(res: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(res), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_next), contentDescription = null)
    }
}

@Composable
internal fun SwitchRow(title: Int, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        MoStorSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/** A settings sub-page: a back arrow, a title, and scrolling content. */
@Composable
internal fun SubPage(ui: AppUi, title: String, bottom: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { ui.back() }, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.edit_back))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        bottom?.let {
            HorizontalDivider()
            it()
        }
    }
}
