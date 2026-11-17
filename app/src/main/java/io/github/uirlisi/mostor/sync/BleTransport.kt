package io.github.uirlisi.mostor.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bluetooth for sync (DECISIONS 2026-11-14, "Phase 5 spike: transport"): BLE advertising with the
 * app's service UUID, and an insecure L2CAP channel. The channel's security comes from the app's own
 * pairing and keys, so no system pairing is needed.
 *
 * Advertisement service data, 10 bytes: kind (1: pairing, 2: sync), the L2CAP PSM (2), a session
 * token (3), and for sync a tag (4) = HMAC(household key, the first 6 bytes), so a phone connects
 * only to its own household and a stranger can't tell two sessions apart. No device name is sent.
 */
@SuppressLint("MissingPermission") // every caller checks [hasPermissions] first
class BleTransport(private val context: Context) {
    data class Found(val device: BluetoothDevice, val kind: Int, val psm: Int, val token: Int, val tag: ByteArray, val header: ByteArray)

    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun hasPermissions(): Boolean = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    val isOn: Boolean get() = adapter?.isEnabled == true

    /**
     * Listens on an L2CAP channel and advertises it until [Advertising.stop]. [tagFor] makes the tag
     * from the header (kind, PSM, token); [onSocket] runs for each phone that connects, on its own thread.
     */
    fun advertise(kind: Int, tagFor: (ByteArray) -> ByteArray, onSocket: (BluetoothSocket) -> Unit): Advertising {
        val a = adapter ?: throw IllegalStateException("no Bluetooth")
        val server = a.listenUsingInsecureL2capChannel()
        val header = ByteBuffer.allocate(6).put(kind.toByte()).putShort(server.psm.toShort())
            .put(SyncCrypto.randomBytes(3)).array()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE), header + tagFor(header).copyOf(4))
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val started = CompletableDeferred<Int>()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { started.complete(0) }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Advertising failed: $errorCode")
                started.complete(errorCode)
            }
        }
        val adv = Advertising(server, callback)
        Thread {
            while (adv.running) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                Thread { onSocket(socket) }.start()
            }
        }.start()
        a.bluetoothLeAdvertiser?.startAdvertising(settings, data, callback) ?: throw IllegalStateException("no advertiser")
        return adv
    }

    inner class Advertising(private val server: BluetoothServerSocket, private val callback: AdvertiseCallback) {
        @Volatile var running = true
            private set

        fun stop() {
            running = false
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
            runCatching { server.close() }
        }
    }

    /** Scans in the foreground for the first advertisement of [kind] that [accept] takes. */
    suspend fun find(kind: Int, timeoutMs: Long, accept: (Found) -> Boolean = { true }): Found? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<Found?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                parse(result)?.takeIf { it.kind == kind && accept(it) }?.let { found.complete(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                found.complete(null)
            }
        }
        scanner.startScan(filters(kind), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        return try {
            withTimeoutOrNull(timeoutMs) { found.await() }
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    /** Connects to an advertised channel. Blocking; closes the socket on failure. */
    suspend fun connect(f: Found): BluetoothSocket = withContext(Dispatchers.IO) {
        f.device.createInsecureL2capChannel(f.psm).also { s ->
            try {
                s.connect()
            } catch (e: Exception) {
                runCatching { s.close() }
                throw e
            }
        }
    }

    /**
     * Android's own scan for sync advertisements, which wakes [SyncScanReceiver] even with the app
     * closed or its process gone (not after a force stop, nor across a reboot: registered again on
     * app start and on boot). Low power; replaces any earlier registration.
     */
    fun registerBackground(): Boolean = runCatching {
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build()
        scanner.startScan(filters(KIND_SYNC), settings, backgroundIntent()) == 0
    }.getOrElse { e ->
        Log.w(TAG, "Background scan not registered: ${e.javaClass.simpleName}")
        false
    }

    fun unregisterBackground() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(backgroundIntent()) }
    }

    private fun backgroundIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, SyncScanReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    companion object {
        private const val TAG = "MoStor"
        const val KIND_PAIR = 1
        const val KIND_SYNC = 2

        /** Mo Stór's sync service (random, fixed for good). */
        val SERVICE: UUID = UUID.fromString("12ca01f1-1d10-494e-8e7f-2b2e6240d3ff")

        val PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )

        /** Matches this kind by the first byte of the service data. */
        private fun filters(kind: Int) = listOf(
            ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE), byteArrayOf(kind.toByte()), byteArrayOf(0xFF.toByte())).build(),
        )

        fun parse(result: ScanResult): Found? {
            val bytes = result.scanRecord?.getServiceData(ParcelUuid(SERVICE)) ?: return null
            if (bytes.size < 10) return null
            val b = ByteBuffer.wrap(bytes)
            val kind = b.get().toInt()
            val psm = b.short.toInt() and 0xFFFF
            val token = ((b.get().toInt() and 0xFF) shl 16) or ((b.get().toInt() and 0xFF) shl 8) or (b.get().toInt() and 0xFF)
            return Found(result.device, kind, psm, token, bytes.copyOfRange(6, 10), bytes.copyOfRange(0, 6))
        }

        /** The sync advertisement's tag: only phones with the household key make or check it. */
        fun syncTag(householdKey: ByteArray, header: ByteArray): ByteArray =
            SyncCrypto.hmac(householdKey, "mostor adv".toByteArray(), header).copyOf(4)
    }
}
