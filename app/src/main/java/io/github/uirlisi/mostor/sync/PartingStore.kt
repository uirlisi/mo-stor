package io.github.uirlisi.mostor.sync

import io.github.uirlisi.mostor.core.Member
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.core.Standing
import io.github.uirlisi.mostor.data.HouseholdSync

/**
 * This phone's side of a parting session (SPEC §9.4): it left the household without reaching any
 * of its phones, and now meets one. Run as an ordinary sync session (protocol version 3), so the
 * other phone needs nothing new: it gets this phone's "left" row, merges it, lets it in once more
 * as a leaving phone, and takes its own records from before it left ([HouseholdSync.partingRows]).
 * Nothing comes back into this database: the other phone's list of phones and rows are dropped, and
 * the vector this phone sends is the other phone's own, so it has next to nothing to send.
 */
internal class PartingStore(private val sync: HouseholdSync, private val parting: HouseholdSync.Parting) : SyncStore {
    @Volatile
    private var theirs: Map<String, String> = emptyMap()

    override suspend fun vector(): Map<String, String> = theirs

    override suspend fun vectorFor(peerId: String): Map<String, String> = theirs

    override suspend fun rowsSince(vector: Map<String, String>): Merge.Rows {
        theirs = vector
        return sync.partingRows(parting, vector)
    }

    override suspend fun applyRows(rows: Merge.Rows): Int = 0

    override suspend fun members(): List<Member> = listOf(parting.row)

    override suspend fun applyMembers(theirs: List<Member>): Int = 0

    /** The other phone proved it holds the household key; only a phone claiming this one's id is refused. */
    override suspend fun standing(deviceId: String): Standing = Standing.ACTIVE

    override suspend fun told(deviceId: String) = Unit
}
