package io.github.uirlisi.mostor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.uirlisi.mostor.ui.AppShell
import io.github.uirlisi.mostor.ui.MoStorTheme
import kotlinx.coroutines.launch

/** The main app (SPEC §6.5): Today · Log · Settings, with Patterns and Stats to come in Phase 3. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        setContent {
            MoStorTheme {
                AppShell(c)
            }
        }
    }

    /**
     * Opening the app syncs with nearby phones first, quietly (SPEC §9.6), and brings back a
     * notification swiped away with "Keep the notification" off.
     */
    override fun onStart() {
        super.onStart()
        val c = container
        c.sync.onOpen()
        c.appScope.launch { c.notifier.showAgain() }
    }
}
