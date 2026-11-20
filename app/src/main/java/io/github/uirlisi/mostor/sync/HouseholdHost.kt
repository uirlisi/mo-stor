package io.github.uirlisi.mostor.sync

import io.github.uirlisi.mostor.core.JoinWay
import io.github.uirlisi.mostor.data.Repository

/** The phone's households as joining needs them (SPEC §9.4); the app's container implements it. */
interface HouseholdHost {
    /** A new household with its own database (and device id), not active yet. */
    suspend fun create(): NewHousehold

    /** Makes [id] the active household: the screens, the notification and sync follow. */
    suspend fun activate(id: String)

    /** Takes away a household made for a join that didn't happen (its database holds nothing yet). */
    suspend fun discard(id: String)

    /** The device ids of all of this phone's households' databases. */
    suspend fun deviceIds(): Set<String>

    /** Every household's repository, the active one included. */
    suspend fun repositories(): List<Repository>

    /** The active household's own name (null while it has only its default, "Household N"). */
    fun activeName(): String?

    /** The name of this phone's household that is household [householdId], if one is. */
    suspend fun holding(householdId: String): String?

    /** Household [id] (null: the active one) joined one the inviting phone calls [theirs] ([io.github.uirlisi.mostor.core.HouseholdList.joined]). */
    suspend fun joined(id: String?, theirs: String?, way: JoinWay)
}

data class NewHousehold(val id: String, val repository: Repository)
