package io.github.uirlisi.mostor.sync

import android.util.Base64
import io.github.uirlisi.mostor.core.HlcStamp
import io.github.uirlisi.mostor.core.Member
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.core.Roster
import io.github.uirlisi.mostor.core.Standing
import io.github.uirlisi.mostor.data.BackupJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * This phone as the other phone sees it: its device id and its name ("Galaxy S23"). [otherIds] are
 * the device ids of this phone's other households' databases (SPEC §9.4): another phone presenting
 * any of them, or [deviceId], is refused.
 */
data class Me(val deviceId: String, val name: String, val otherIds: Set<String> = emptySet()) {
    fun isMine(id: String): Boolean = id == deviceId || id in otherIds
}

/** What a sync session needs from the database. The repository implements it. */
interface SyncStore {
    /** Per origin, the newest stamp held (`Sync.vector`). */
    suspend fun vector(): Map<String, String>

    /**
     * The vector to send [peerId]: empty (send me everything) until the first session with it has
     * finished, since rows that arrived while pairing are only a part of that phone's data and
     * would otherwise hide the rest (found on the phones; DECISIONS, "Sync protocol").
     */
    suspend fun vectorFor(peerId: String): Map<String, String>

    /** The rows a phone with [vector] lacks (`Sync.since`). */
    suspend fun rowsSince(vector: Map<String, String>): Merge.Rows

    /** Merges rows from the other phone in one transaction; returns how many rows changed here. */
    suspend fun applyRows(rows: Merge.Rows): Int

    /** The household's phones as this phone has them, removed ones included (SPEC §9.4). */
    suspend fun members(): List<Member>

    /** Merges the other phone's list of the household's phones; returns how many rows changed here. */
    suspend fun applyMembers(theirs: List<Member>): Int

    /** Whether [deviceId] is a phone of the household here, was removed from it, or isn't known. */
    suspend fun standing(deviceId: String): Standing

    /** This phone has just told [deviceId] that it was removed from the household. */
    suspend fun told(deviceId: String)
}

/**
 * The other phone refused this one: [removed] when it was removed from the household, by [by];
 * [already] when, joining, it found it already holds this household.
 */
class RefusedByPeer(val removed: Boolean, val peerId: String?, val peerName: String?, val by: String?, val already: Boolean = false) :
    ProtocolException(if (removed) "refused: removed" else if (already) "refused: already in the household" else "refused: not known there")

/** Joining: one of this phone's databases already is in household [householdId], so it didn't join again. */
class AlreadyIn(val householdId: String) : ProtocolException("already in the household")

/** This phone refused [peerId]: removed from the household ([removed]) or not known here. */
class RefusedPeer(val peerId: String, val removed: Boolean) :
    ProtocolException(if (removed) "refused a removed phone" else "refused a phone not known here")

/**
 * The sync session (SPEC §9.3), in a strict turn order so neither phone writes while the other is
 * writing (a Bluetooth channel's buffers are small; two phones both sending a large message at
 * once could wait on each other forever):
 *
 * ```
 * client                                   server
 * hello {n_c}                       →
 *                                   ←      hello {n_s}
 *   (keys: HKDF(household key, n_c‖n_s), one per direction; everything below is encrypted)
 * auth {device, name, members}      →
 *   (the server merges the client's list of phones; a phone removed or not known: refuse)
 *                                   ←      auth {device, name, vector, members}
 *   (the client merges the server's list; a phone removed or not known: refuse)
 * vector, rows for the server       →
 *   (the server merges the client's rows)
 *                                   ←      rows for the client
 *   (the client merges the server's rows)
 * done                              →
 * ```
 * The household's list of phones (SPEC §9.4) goes whole both ways before either phone decides
 * whether to let the other in, so a phone added by a third phone is known as soon as it shows up,
 * and a removal reaches the removed phone, which is told with `refuse {why: removed}`. Each phone
 * sends its vector only once it knows the other phone, because the first session with a phone asks
 * for everything ([SyncStore.vectorFor]). Rows travel as the JSON backup's format (strictly read),
 * gzipped. A phone without the household key fails at the first encrypted message.
 */
object SyncSession {
    const val VERSION = 4

    data class Result(val peerId: String, val peerName: String, val received: Int, val sent: Int)

    suspend fun client(link: Link, householdKey: ByteArray, me: Me, store: SyncStore): Result {
        val nc = SyncCrypto.randomBytes(16)
        link.send(json("hello", "v" to VERSION, "n" to b64(nc)))
        val hello = parse(link.receive(), "hello")
        checkVersion(hello)
        val ns = unb64(hello.getString("n"))
        val secure = SecureLink(link, key(householdKey, nc, ns, "c2s"), key(householdKey, nc, ns, "s2c"))

        secure.sendJson(json("auth", "device" to me.deviceId, "name" to me.name, "members" to membersJson(store.members())))
        val theirAuth = secure.receiveJson("auth")
        val peerId = theirAuth.getString("device")
        store.applyMembers(membersOf(theirAuth))
        admit(secure, link, peerId, me, store)
        val mine = store.rowsSince(vectorOf(theirAuth))
        secure.sendJson(json("vector", "vector" to JSONObject(store.vectorFor(peerId) as Map<*, *>)))
        secure.sendRows(mine)
        val theirRows = secure.receiveRows()
        val changed = store.applyRows(theirRows)
        secure.sendJson(json("done"))
        link.awaitPeerClose()
        return Result(peerId, theirAuth.getString("name"), changed, mine.size())
    }

    suspend fun server(link: Link, householdKey: ByteArray, me: Me, store: SyncStore): Result {
        val hello = parse(link.receive(), "hello")
        checkVersion(hello)
        val nc = unb64(hello.getString("n"))
        val ns = SyncCrypto.randomBytes(16)
        link.send(json("hello", "v" to VERSION, "n" to b64(ns)))
        val secure = SecureLink(link, key(householdKey, nc, ns, "s2c"), key(householdKey, nc, ns, "c2s"))

        val theirAuth = secure.receiveJson("auth")
        val peerId = theirAuth.getString("device")
        store.applyMembers(membersOf(theirAuth))
        admit(secure, link, peerId, me, store)
        secure.sendJson(
            json(
                "auth", "device" to me.deviceId, "name" to me.name,
                "vector" to JSONObject(store.vectorFor(peerId) as Map<*, *>), "members" to membersJson(store.members()),
            ),
        )
        val theirVector = vectorOf(secure.receiveJson("vector"))
        val theirRows = secure.receiveRows()
        val changed = store.applyRows(theirRows)
        val mine = store.rowsSince(theirVector)
        secure.sendRows(mine)
        secure.receiveJson("done")
        return Result(peerId, theirAuth.getString("name"), changed, mine.size())
    }

    /**
     * Lets [peerId] in if it is a phone of the household here, or one leaving it (its last session:
     * its leaving and its last records reach the household); else tells it why not (with who
     * removed it), waits for it to close, and throws [RefusedPeer]. A phone claiming one of this
     * phone's own device ids is never let in: in a household where every other phone was removed,
     * a modified removed phone could otherwise pass as this one (SPEC §9.4).
     */
    private suspend fun admit(secure: SecureLink, link: Link, peerId: String, me: Me, store: SyncStore) {
        val standing = if (me.isMine(peerId)) Standing.UNKNOWN else store.standing(peerId)
        if (standing == Standing.ACTIVE || standing == Standing.LEFT) return
        val removed = standing == Standing.REMOVED
        val members = store.members()
        val by = members.firstOrNull { it.deviceId == peerId }?.removedBy?.let { id -> members.firstOrNull { it.deviceId == id }?.name }
        secure.sendJson(
            json("refuse", "why" to if (removed) "removed" else "unknown", "device" to me.deviceId, "name" to me.name, "by" to (by ?: me.name)),
        )
        if (removed) store.told(peerId)
        link.awaitPeerClose()
        // Two phones that removed each other before they synced: the merged list says this phone
        // was removed too. Both meant it, so both go, each keeping its records (SPEC §9.4).
        if (removed && store.standing(me.deviceId) == Standing.REMOVED) {
            val mine = members.firstOrNull { it.deviceId == me.deviceId }?.removedBy
            throw RefusedByPeer(removed = true, peerId = peerId, peerName = null, by = members.firstOrNull { it.deviceId == mine }?.name)
        }
        throw RefusedPeer(peerId, removed)
    }

    private fun key(hk: ByteArray, nc: ByteArray, ns: ByteArray, dir: String) = SyncCrypto.hkdf(hk, nc + ns, "mostor sync $dir")

    private fun vectorOf(message: JSONObject): Map<String, String> {
        val v = message.getJSONObject("vector")
        return v.keys().asSequence().associateWith { v.getString(it) }
    }

    private fun checkVersion(hello: JSONObject) {
        if (hello.optInt("v", -1) != VERSION) throw ProtocolException("protocol version ${hello.opt("v")}")
    }
}

/**
 * Pairing (SPEC §9.4): the phone that adds ("Invite a phone") and the one that joins ("Join a household") agree a
 * key with P-256 ECDH, and both show a 6-digit code made from the exchange. The adder commits to
 * its key first, so a phone in the middle can't pick keys to make the two codes match. When the
 * owner confirms on both, the adder sends the household key, its id and name, and its children and
 * their meds (for child matching on the joining phone), and the household's list of phones.
 *
 * ```
 * joiner                                   adder
 * pair                              →
 *                                   ←      commit {SHA-256(pub_a ‖ n_a)}
 * key {pub_b, n_b}                  →
 *                                   ←      key {pub_a, n_a}
 *   (both: code from ECDH and the transcript; the owner compares; encrypted from here)
 * confirm {ok}                      ↔      confirm {ok}
 * me {device, name}                 →
 *                                   ←      household {id, key, device, name, members, household_name}, rows (children, meds)
 * joined (or refuse {already})      →
 * ```
 */
object Pairing {
    /** [householdName]: the inviting phone's own name for the household, if it gave one; copied once by the joiner. */
    data class Offer(
        val householdId: String,
        val householdKey: ByteArray,
        val rows: Merge.Rows,
        val members: List<Member> = emptyList(),
        val householdName: String? = null,
    )

    data class Joined(val peerId: String, val peerName: String)

    data class Household(
        val id: String,
        val key: ByteArray,
        val peerId: String,
        val peerName: String,
        val rows: Merge.Rows,
        val members: List<Member> = emptyList(),
        val householdName: String? = null,
    )

    /** Thrown when either owner says the codes don't match. */
    class Declined : Exception("the codes weren't confirmed")

    /**
     * The adding phone. [confirm] shows the code and returns the owner's answer; [offer] gets the
     * joining phone's id and name.
     */
    suspend fun adder(link: Link, me: Me, offer: suspend (Joined) -> Offer, confirm: suspend (code: String) -> Boolean): Joined {
        parse(link.receive(), "pair").let { if (it.optInt("v", -1) != SyncSession.VERSION) throw ProtocolException("protocol version") }
        val kp = SyncCrypto.keyPair()
        val pubA = SyncCrypto.encode(kp.public)
        val na = SyncCrypto.randomBytes(16)
        link.send(json("commit", "c" to b64(SyncCrypto.sha256(pubA, na))))
        val theirKey = parse(link.receive(), "key")
        val pubB = unb64(theirKey.getString("pub"))
        val nb = unb64(theirKey.getString("n"))
        link.send(json("key", "pub" to b64(pubA), "n" to b64(na)))

        val shared = SyncCrypto.agree(kp.private, SyncCrypto.decodePublic(pubB))
        val transcript = SyncCrypto.sha256(pubA, pubB, na, nb)
        val secure = SecureLink(link, SyncCrypto.hkdf(shared, transcript, "mostor pair a2b"), SyncCrypto.hkdf(shared, transcript, "mostor pair b2a"))
        exchangeConfirmations(secure, confirm(SyncCrypto.pairingCode(shared, transcript)))

        val who = secure.receiveJson("me").let { Joined(it.getString("device"), it.getString("name")) }
        if (me.isMine(who.peerId)) throw ProtocolException("the other phone claims this phone's id")
        val o = offer(who)
        secure.sendJson(
            json(
                "household", "id" to o.householdId, "key" to b64(o.householdKey), "device" to me.deviceId, "name" to me.name,
                "members" to membersJson(o.members), "household_name" to o.householdName,
            ),
        )
        secure.sendRows(o.rows)
        secure.receiveJson("joined")
        return who
    }

    /**
     * The joining phone. [alreadyIn] tells whether one of this phone's databases already is in the
     * offered household: then it refuses (so the adder doesn't list it twice) and throws [AlreadyIn].
     */
    suspend fun joiner(
        link: Link,
        me: Me,
        alreadyIn: suspend (householdId: String) -> Boolean = { false },
        confirm: suspend (code: String) -> Boolean,
    ): Household {
        link.send(json("pair", "v" to SyncSession.VERSION))
        val commit = unb64(parse(link.receive(), "commit").getString("c"))
        val kp = SyncCrypto.keyPair()
        val pubB = SyncCrypto.encode(kp.public)
        val nb = SyncCrypto.randomBytes(16)
        link.send(json("key", "pub" to b64(pubB), "n" to b64(nb)))
        val theirKey = parse(link.receive(), "key")
        val pubA = unb64(theirKey.getString("pub"))
        val na = unb64(theirKey.getString("n"))
        if (!SyncCrypto.sha256(pubA, na).contentEquals(commit)) throw ProtocolException("the other phone changed its key")

        val shared = SyncCrypto.agree(kp.private, SyncCrypto.decodePublic(pubA))
        val transcript = SyncCrypto.sha256(pubA, pubB, na, nb)
        val secure = SecureLink(link, SyncCrypto.hkdf(shared, transcript, "mostor pair b2a"), SyncCrypto.hkdf(shared, transcript, "mostor pair a2b"))
        exchangeConfirmations(secure, confirm(SyncCrypto.pairingCode(shared, transcript)))

        secure.sendJson(json("me", "device" to me.deviceId, "name" to me.name))
        val h = secure.receiveJson("household")
        val rows = secure.receiveRows()
        val id = h.getString("id")
        if (alreadyIn(id)) {
            secure.sendJson(json("refuse", "why" to "already"))
            runCatching { link.awaitPeerClose() }
            throw AlreadyIn(id)
        }
        secure.sendJson(json("joined"))
        link.awaitPeerClose()
        val key = unb64(h.getString("key"))
        if (key.size != 32) throw ProtocolException("household key size")
        if (me.isMine(h.getString("device"))) throw ProtocolException("the other phone claims this phone's id")
        return Household(id, key, h.getString("device"), h.getString("name"), rows, membersOf(h), h.optStringOrNull("household_name"))
    }

    private fun exchangeConfirmations(secure: SecureLink, mine: Boolean) {
        secure.sendJson(json("confirm", "ok" to mine))
        val theirs = secure.receiveJson("confirm").getBoolean("ok")
        if (!mine || !theirs) throw Declined()
    }
}

// ---- Message helpers ----

internal fun json(type: String, vararg fields: Pair<String, Any?>): ByteArray =
    JSONObject().put("t", type).apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }.toString().toByteArray(Charsets.UTF_8)

/**
 * Reads a message of type [expected]. A refusal ends the session with [RefusedByPeer], but only when it
 * came [encrypted]: a phone of the household only ever refuses through [SecureLink], and a refusal in
 * the clear could come from any device nearby (it would make this phone leave its household).
 */
internal fun parse(bytes: ByteArray, expected: String, encrypted: Boolean = false): JSONObject {
    val o = try {
        JSONObject(bytes.utf8())
    } catch (e: org.json.JSONException) {
        throw ProtocolException("not a message")
    }
    val t = o.optString("t")
    if (t == "refuse") {
        if (!encrypted) throw ProtocolException("a refusal in the clear")
        throw RefusedByPeer(
            removed = o.optString("why") == "removed",
            peerId = o.optString("device").takeIf { it.isNotEmpty() },
            peerName = o.optString("name").takeIf { it.isNotEmpty() },
            by = o.optString("by").takeIf { it.isNotEmpty() },
            already = o.optString("why") == "already",
        )
    }
    if (t != expected) throw ProtocolException("expected $expected, got $t")
    return o
}

internal fun SecureLink.sendJson(body: ByteArray) = send(SecureLink.JSON, body)

internal fun SecureLink.receiveJson(expected: String): JSONObject {
    val (type, body) = receive()
    if (type != SecureLink.JSON) throw ProtocolException("expected a message")
    return parse(body, expected, encrypted = true)
}

internal fun SecureLink.sendRows(rows: Merge.Rows) =
    send(SecureLink.GZIP_JSON, SyncCrypto.gzip(BackupJson.encode(rows, System.currentTimeMillis(), "sync").toByteArray(Charsets.UTF_8)))

internal fun SecureLink.receiveRows(): Merge.Rows {
    val (type, body) = receive()
    if (type != SecureLink.GZIP_JSON) throw ProtocolException("expected rows")
    val text = try {
        SyncCrypto.gunzip(body, 64 shl 20).utf8()
    } catch (e: Exception) {
        throw ProtocolException("rows didn't unpack")
    }
    return when (val read = BackupJson.decode(text)) {
        is BackupJson.Read.Ok -> read.backup.rows
        BackupJson.Read.Newer -> throw ProtocolException("rows from a newer version")
        BackupJson.Read.NotABackup -> throw ProtocolException("rows didn't read")
    }
}

/** The household's list of phones as JSON (SPEC §9.4). */
internal fun membersJson(members: List<Member>): JSONArray = JSONArray().apply {
    for (m in members) {
        put(
            JSONObject()
                .put("device", m.deviceId).put("name", m.name).put("added_by", m.addedBy ?: JSONObject.NULL)
                .put("added_at", m.addedAt).put("removed_at", m.removedAt ?: JSONObject.NULL)
                .put("removed_by", m.removedBy ?: JSONObject.NULL).put("hlc", m.hlc),
        )
    }
}

/** The list of phones in [message] (none when it has no `members`); a malformed row ends the session. */
internal fun membersOf(message: JSONObject): List<Member> {
    val a = message.optJSONArray("members") ?: return emptyList()
    return try {
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val hlc = o.getString("hlc")
            if (hlc != Roster.SEED_HLC) HlcStamp.parse(hlc)
            Member(
                deviceId = o.getString("device"),
                name = o.getString("name"),
                addedBy = o.optStringOrNull("added_by"),
                addedAt = o.getLong("added_at"),
                removedAt = if (o.isNull("removed_at")) null else o.getLong("removed_at"),
                removedBy = o.optStringOrNull("removed_by"),
                hlc = hlc,
            )
        }
    } catch (e: Exception) {
        throw ProtocolException("phones didn't read")
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

internal fun Merge.Rows.size() = children.size + events.size + segments.size + presets.size

internal fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

internal fun unb64(s: String): ByteArray = try {
    Base64.decode(s, Base64.NO_WRAP)
} catch (e: IllegalArgumentException) {
    throw ProtocolException("bad base64")
}
