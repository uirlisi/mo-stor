package io.github.uirlisi.mostor.sync

import android.util.Base64
import io.github.uirlisi.mostor.core.Merge
import io.github.uirlisi.mostor.data.BackupJson
import org.json.JSONObject

/** This phone as the other phone sees it: its device id and its name ("Galaxy S23"). */
data class Me(val deviceId: String, val name: String)

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

    /** Whether [deviceId] is a phone this one syncs with (not forgotten). */
    suspend fun knows(deviceId: String): Boolean
}

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
 * auth {device, name}               →
 *                                   ←      auth {device, name, vector}
 * vector, rows for the server       →
 *   (the server merges the client's rows)
 *                                   ←      rows for the client
 *   (the client merges the server's rows)
 * done                              →
 * ```
 * Each phone sends its vector only once it knows the other phone, because the first session with a
 * phone asks for everything ([SyncStore.vectorFor]). Rows travel as the JSON backup's format
 * (strictly read), gzipped. A phone that isn't a known peer is refused after its auth; a phone
 * without the household key fails at the first message.
 */
object SyncSession {
    const val VERSION = 2

    data class Result(val peerId: String, val peerName: String, val received: Int, val sent: Int)

    suspend fun client(link: Link, householdKey: ByteArray, me: Me, store: SyncStore): Result {
        val nc = SyncCrypto.randomBytes(16)
        link.send(json("hello", "v" to VERSION, "n" to b64(nc)))
        val hello = parse(link.receive(), "hello")
        checkVersion(hello)
        val ns = unb64(hello.getString("n"))
        val secure = SecureLink(link, key(householdKey, nc, ns, "c2s"), key(householdKey, nc, ns, "s2c"))

        secure.sendJson(json("auth", "device" to me.deviceId, "name" to me.name))
        val theirAuth = secure.receiveJson("auth")
        val peerId = theirAuth.getString("device")
        if (!store.knows(peerId)) throw ProtocolException("not a known phone")
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
        if (!store.knows(peerId)) {
            secure.sendJson(json("refuse", "why" to "not paired"))
            throw ProtocolException("not a known phone")
        }
        secure.sendJson(json("auth", "device" to me.deviceId, "name" to me.name, "vector" to JSONObject(store.vectorFor(peerId) as Map<*, *>)))
        val theirVector = vectorOf(secure.receiveJson("vector"))
        val theirRows = secure.receiveRows()
        val changed = store.applyRows(theirRows)
        val mine = store.rowsSince(theirVector)
        secure.sendRows(mine)
        secure.receiveJson("done")
        return Result(peerId, theirAuth.getString("name"), changed, mine.size())
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
 * Pairing (SPEC §9.4): the phone that adds ("Add a phone") and the one that joins ("Join") agree a
 * key with P-256 ECDH, and both show a 6-digit code made from the exchange. The adder commits to
 * its key first, so a phone in the middle can't pick keys to make the two codes match. When the
 * owner confirms on both, the adder sends the household key, its id and name, and its children and
 * their meds (for child matching on the joining phone).
 *
 * ```
 * joiner                                   adder
 * pair                              →
 *                                   ←      commit {SHA-256(pub_a ‖ n_a)}
 * key {pub_b, n_b}                  →
 *                                   ←      key {pub_a, n_a}
 *   (both: code from ECDH and the transcript; the owner compares; encrypted from here)
 * confirm {ok}                      ↔      confirm {ok}
 *                                   ←      household {id, key, device, name}, rows (children, meds)
 * joined {device, name}             →
 * ```
 */
object Pairing {
    data class Offer(val householdId: String, val householdKey: ByteArray, val rows: Merge.Rows)

    data class Joined(val peerId: String, val peerName: String)

    data class Household(val id: String, val key: ByteArray, val peerId: String, val peerName: String, val rows: Merge.Rows)

    /** Thrown when either owner says the codes don't match. */
    class Declined : Exception("the codes weren't confirmed")

    /** The adding phone. [confirm] shows the code and returns the owner's answer. */
    suspend fun adder(link: Link, me: Me, offer: suspend () -> Offer, confirm: suspend (code: String) -> Boolean): Joined {
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

        val o = offer()
        secure.sendJson(json("household", "id" to o.householdId, "key" to b64(o.householdKey), "device" to me.deviceId, "name" to me.name))
        secure.sendRows(o.rows)
        val joined = secure.receiveJson("joined")
        return Joined(joined.getString("device"), joined.getString("name"))
    }

    /** The joining phone. */
    suspend fun joiner(link: Link, me: Me, confirm: suspend (code: String) -> Boolean): Household {
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

        val h = secure.receiveJson("household")
        val rows = secure.receiveRows()
        secure.sendJson(json("joined", "device" to me.deviceId, "name" to me.name))
        link.awaitPeerClose()
        val key = unb64(h.getString("key"))
        if (key.size != 32) throw ProtocolException("household key size")
        return Household(h.getString("id"), key, h.getString("device"), h.getString("name"), rows)
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

internal fun parse(bytes: ByteArray, expected: String): JSONObject {
    val o = try {
        JSONObject(bytes.utf8())
    } catch (e: org.json.JSONException) {
        throw ProtocolException("not a message")
    }
    val t = o.optString("t")
    if (t == "refuse") throw ProtocolException("refused: ${o.optString("why")}")
    if (t != expected) throw ProtocolException("expected $expected, got $t")
    return o
}

internal fun SecureLink.sendJson(body: ByteArray) = send(SecureLink.JSON, body)

internal fun SecureLink.receiveJson(expected: String): JSONObject {
    val (type, body) = receive()
    if (type != SecureLink.JSON) throw ProtocolException("expected a message")
    return parse(body, expected)
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

internal fun Merge.Rows.size() = children.size + events.size + segments.size + presets.size

internal fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

internal fun unb64(s: String): ByteArray = try {
    Base64.decode(s, Base64.NO_WRAP)
} catch (e: IllegalArgumentException) {
    throw ProtocolException("bad base64")
}
