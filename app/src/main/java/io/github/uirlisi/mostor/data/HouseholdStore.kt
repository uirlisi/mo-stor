package io.github.uirlisi.mostor.data

import android.content.Context
import io.github.uirlisi.mostor.core.HouseholdList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's own store (SPEC §4.3): which households this phone holds and which one is active, in
 * Android's SharedPreferences (not in any household's database, and never synced or backed up).
 * Read once, then kept in memory; every change is written at once ([android.content.SharedPreferences.Editor.commit]),
 * so a household made just before the process dies is still on the list.
 */
class HouseholdStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state: MutableStateFlow<HouseholdList> by lazy { MutableStateFlow(load()) }

    val flow: StateFlow<HouseholdList> get() = state.asStateFlow()

    fun list(): HouseholdList = state.value

    /**
     * Applies [change] and saves the result; returns it. [durable] waits for the write (a household
     * added or removed); a rename doesn't need to.
     */
    @Synchronized
    fun update(durable: Boolean = true, change: (HouseholdList) -> HouseholdList): HouseholdList {
        val next = change(state.value)
        if (next != state.value) {
            val edit = prefs.edit().putString(KEY, next.encode())
            if (durable) edit.commit() else edit.apply()
            state.value = next
        }
        return next
    }

    /**
     * The saved list; the first start (and an upgrade from 0.6.x) begins with the existing database as
     * Household 1. A deleted household's file that is still there (the app stopped before it was
     * deleted) is deleted now, before anything opens it. Household files the list doesn't know come
     * back on it, so a lost store never hides records.
     */
    @Synchronized
    private fun load(): HouseholdList {
        val saved = HouseholdList.decode(prefs.getString(KEY, null))
        var list = saved ?: HouseholdList.first()
        for (file in list.pendingDelete) {
            // Never a file a household on the list still uses.
            if (list.slots.any { it.file == file }) {
                list = list.deleted(file)
                continue
            }
            context.deleteDatabase(file)
            if (context.databaseList()?.contains(file) != true) list = list.deleted(file)
        }
        val files = context.databaseList()?.toList().orEmpty()
        list = list.adopt(files)
        if (list != saved) prefs.edit().putString(KEY, list.encode()).commit()
        return list
    }

    companion object {
        private const val PREFS = "phone"
        private const val KEY = "households"
    }
}
