package io.github.uirlisi.mostor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.uirlisi.mostor.ui.AppShell
import io.github.uirlisi.mostor.ui.MoStorTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The main app (SPEC §6.5): Today · Log · Patterns · Stats · Settings. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        // Every screen holds the repository it started with: another household (SPEC §9.4) means
        // starting again on its database, on the same screen.
        val household = c.households.list().activeId
        lifecycleScope.launch {
            c.households.flow.map { it.activeId }.distinctUntilChanged().collect { if (it != household) recreate() }
        }
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
