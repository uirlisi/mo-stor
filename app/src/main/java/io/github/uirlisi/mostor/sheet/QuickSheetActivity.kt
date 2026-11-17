package io.github.uirlisi.mostor.sheet

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.uirlisi.mostor.MainActivity
import io.github.uirlisi.mostor.container
import io.github.uirlisi.mostor.ui.MoStorTheme

/**
 * The quick sheet (SPEC §6.3): a translucent bottom sheet over whatever is on screen, including the
 * secure lock screen. It doesn't turn the screen on and stays out of recents (see the manifest).
 *
 * Whether it may show over the lock screen is decided by the manifest, before any window exists:
 * this entry has `showWhenLocked`, [QuickSheetAfterUnlockActivity] doesn't, and the notification
 * opens one or the other according to the "Quick sheet on the lock screen" setting. With the second,
 * Android asks for the unlock itself, and nothing of the app shows on the lock screen first.
 */
open class QuickSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val c = container
        val focus = intent.getStringExtra(EXTRA_FOCUS_EVENT)
        // A launcher shortcut records once, when the sheet opens (not again after a rotation).
        val shortcut = if (savedInstanceState == null) intent.action?.takeIf { it in SHORTCUTS } else null
        setContent {
            MoStorTheme {
                QuickSheet(
                    repository = c.repository,
                    appScope = c.appScope,
                    focusEventId = focus,
                    shortcut = shortcut,
                    onClose = ::finish,
                    onOpenApp = ::openApp,
                )
            }
        }
    }

    /** Opening the sheet syncs with nearby phones, quietly; the sheet follows as rows arrive (SPEC §9.6). */
    override fun onStart() {
        super.onStart()
        container.sync.onOpen()
    }

    /** [Open app] asks for an unlock when the phone is locked. */
    private fun openApp() {
        val km = getSystemService(KeyguardManager::class.java)
        val start = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = start()
                },
            )
        } else {
            start()
        }
    }

    companion object {
        /** An event to bring to the top: the "has no end" notification opens the sheet on it. */
        const val EXTRA_FOCUS_EVENT = "focus_event"

        /** The launcher shortcuts' actions (res/xml/shortcuts.xml, SPEC §6.4). */
        const val ACTION_WET = "io.github.uirlisi.mostor.action.WET"
        const val ACTION_POOP = "io.github.uirlisi.mostor.action.POOP"
        const val ACTION_LEFT = "io.github.uirlisi.mostor.action.LEFT"
        const val ACTION_RIGHT = "io.github.uirlisi.mostor.action.RIGHT"
        private val SHORTCUTS = setOf(ACTION_WET, ACTION_POOP, ACTION_LEFT, ACTION_RIGHT)
    }
}

/**
 * The quick sheet when it isn't allowed on the lock screen: the same screen without
 * `showWhenLocked`, so a tap on the notification of a locked phone asks for the unlock first.
 */
class QuickSheetAfterUnlockActivity : QuickSheetActivity()
