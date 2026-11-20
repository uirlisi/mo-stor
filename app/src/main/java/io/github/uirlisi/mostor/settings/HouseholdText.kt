package io.github.uirlisi.mostor.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uirlisi.mostor.container

private const val OPEN = '\u0001'
private const val CLOSE = '\u0002'

/** Marks [name] (a household's) for [styled], which shows it like code. Pass the result as a string resource's argument. */
internal fun code(name: String): String = OPEN + name.filter { it != OPEN && it != CLOSE } + CLOSE

/** [text] with every name marked by [code] shown like code: monospaced, on a tinted background. */
@Composable
internal fun styled(text: String): AnnotatedString {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    val style = SpanStyle(fontFamily = FontFamily.Monospace, background = tint)
    val pad = SpanStyle(background = tint)
    return remember(text, style) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val open = text.indexOf(OPEN, i)
                if (open < 0) {
                    append(text, i, text.length)
                    break
                }
                append(text, i, open)
                val close = text.indexOf(CLOSE, open + 1).let { if (it < 0) text.length else it }
                // A narrow no-break space each side (in the text's own font), so the tint doesn't hug the letters.
                withStyle(pad) { append(' ') }
                withStyle(style) { append(text, open + 1, close) }
                withStyle(pad) { append(' ') }
                i = close + 1
            }
        }
    }
}

/** The active household's name (its default, "Household N", if it has none), marked by [code]. */
@Composable
internal fun activeHouseholdCode(): String {
    val c = LocalContext.current.container
    val list by c.households.flow.collectAsStateWithLifecycle()
    return code(c.nameOf(list.active))
}
