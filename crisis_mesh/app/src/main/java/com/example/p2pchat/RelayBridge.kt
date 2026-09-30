package com.example.p2pchat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID

/**
 * Talks to an ESP32 range-extender relay (see /esp32_relay) over BLE GATT.
 *
 * The ESP32 can't take part in Google's Nearby Connections, so this is a second,
 * independent transport: it scans for the relay's service UUID, connects, and then
 *  - [send] fragments a packet into <=200-byte frames and writes them to the relay,
 *  - incoming frames (relay notifications) are reassembled and handed to
 *    [Listener.onPacket] as the original bytes.
 *
 * A "packet" is just the same JSON PayloadDto NearbyManager already exchanges, so
 * everything above this class treats the relay like one more peer.
 * All [Listener] callbacks and all public methods run on the main thread.
 */
@SuppressLint("MissingPermission")
class RelayBridge(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onPacket(bytes: ByteArray)
        fun onLinkChanged(up: Boolean, relayName: String?)
    }

    companion object {
        private const val TAG = "RelayBridge"

        // Must match the ESP32 firmware.
        val SERVICE_UUID: UUID = UUID.fromString("7b1c0001-2f6a-4d5e-9c3a-5a6f0d1e2b70")
        val RX_UUID: UUID = UUID.fromString("7b1c0002-2f6a-4d5e-9c3a-5a6f0d1e2b70") // we write
        val TX_UUID: UUID = UUID.fromString("7b1c0003-2f6a-4d5e-9c3a-5a6f0d1e2b70") // we get notified
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MAGIC: Byte = 0xA7.toByte()
        private const val HDR_LEN = 8
        private const val CHUNK = 192                    // 8 + 192 = 200 = firmware MAX_FRAME
        private const val MAX_FRAGS = 32
        private const val REQUEST_MTU = 247
        private const val MIN_MTU = HDR_LEN + CHUNK + 3  // notify/write payload is MTU - 3

        private const val SCAN_WINDOW_MS = 10_000L
        private const val RESCAN_DELAY_MS = 5_000L
        private const val LINK_SETUP_TIMEOUT_MS = 15_000L
        private const val PARTIAL_TIMEOUT_MS = 20_000L
        private const val SEEN_CAP = 128
    }

    private val handler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    private var running = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = 23
    private var ready = false

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    private class Partial(val cnt: Int) {
        val parts = arrayOfNulls<ByteArray>(cnt)
        var got = 0
        val startedAt = System.currentTimeMillis()
    }

    private val partials = HashMap<Int, Partial>()
    // ids of packets we sent or already delivered - ignore if they come around again
    private val seenIds = LinkedHashSet<Int>()

    val isReady: Boolean get() = ready

    // ------------------------------------------------------------ lifecycle

    fun start() {
        if (running) return
        running = true
        scan()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        val g = gatt
        gatt = null
        resetLinkState()
        try {
            g?.disconnect()
            g?.close()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------- scanning

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            retryLater()
        }
    }
    private val rescan = Runnable { scan() }

    private fun retryLater() {
        handler.removeCallbacks(rescan)
        if (running) handler.postDelayed(rescan, RESCAN_DELAY_MS)
    }

    private fun scan() {
        if (!running || gatt != null || scanning) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            retryLater()
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            handler.removeCallbacks(scanTimeout)
            handler.postDelayed(scanTimeout, SCAN_WINDOW_MS)
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed: ${e.message}")
            retryLater()
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (running && scanning && gatt == null) {
                    stopScan()
                    connect(result.device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                retryLater()
            }
        }
    }

    // ----------------------------------------------------------- connection

    private val linkTimeout = Runnable {
        if (!ready) gatt?.let { onLost(it) }
    }

    private fun connect(device: BluetoothDevice) {
        negotiatedMtu = 23
        handler.removeCallbacks(linkTimeout)
        handler.postDelayed(linkTimeout, LINK_SETUP_TIMEOUT_MS)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun resetLinkState() {
        rxChar = null
        ready = false
        writeQueue.clear()
        writeInFlight = false
        partials.clear()
        negotiatedMtu = 23
        handler.removeCallbacks(linkTimeout)
    }

    private fun onLost(g: BluetoothGatt) {
        try {
            g.close()
        } catch (_: Exception) {
        }
        if (g !== gatt) return
        gatt = null
        val wasReady = ready
        resetLinkState()
        if (wasReady) listener.onLinkChanged(false, null)
        if (running) retryLater()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(REQUEST_MTU)) g.discoverServices()
            } else {
                handler.post { onLost(g) }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            val rx = svc?.getCharacteristic(RX_UUID)
            val tx = svc?.getCharacteristic(TX_UUID)
            val cccd = tx?.getDescriptor(CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null || cccd == null) {
                handler.post { onLost(g) }
                return
            }
            if (negotiatedMtu < MIN_MTU) {
                Log.w(TAG, "Relay link needs MTU >= $MIN_MTU, got $negotiatedMtu")
                handler.post { onLost(g) }
                return
            }
            rxChar = rx
            g.setCharacteristicNotification(tx, true)
            if (!writeDescriptor(g, cccd)) handler.post { onLost(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != CCCD_UUID) return
            handler.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    ready = true
                    handler.removeCallbacks(linkTimeout)
                    val name = try { g.device.name } catch (_: Exception) { null }
                    listener.onLinkChanged(true, name)
                } else {
                    onLost(g)
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                writeInFlight = false
                pumpWrites()
            }
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            handler.post { onFrame(copy) }
        }

        // API < 33
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // handled above
            val copy = c.value?.copyOf() ?: return
            handler.post { onFrame(copy) }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = data
            g.writeCharacteristic(c)
        }

    // -------------------------------------------------------------- sending

    /** Fragment [packet] and queue it for the relay. Silently dropped if the link is down. */
    fun send(packet: ByteArray) {
        if (!ready || packet.isEmpty()) return
        val cnt = (packet.size + CHUNK - 1) / CHUNK
        if (cnt > MAX_FRAGS) {
            Log.w(TAG, "Packet too large for relay (${packet.size} bytes), not sent")
            return
        }
        val id = random.nextInt()
        rememberSeen(id)
        for (i in 0 until cnt) {
            val from = i * CHUNK
            val to = minOf(from + CHUNK, packet.size)
            val frame = ByteArray(HDR_LEN + (to - from))
            frame[0] = MAGIC
            frame[1] = 0 // ttl: the relay sets it
            frame[2] = (id ushr 24).toByte()
            frame[3] = (id ushr 16).toByte()
            frame[4] = (id ushr 8).toByte()
            frame[5] = id.toByte()
            frame[6] = i.toByte()
            frame[7] = cnt.toByte()
            System.arraycopy(packet, from, frame, HDR_LEN, to - from)
            writeQueue.addLast(frame)
        }
        pumpWrites()
    }

    private fun pumpWrites() {
        if (writeInFlight) return
        val g = gatt ?: return
        val rx = rxChar ?: return
        val next = writeQueue.removeFirstOrNull() ?: return
        writeInFlight = true
        if (!writeChar(g, rx, next)) {
            writeInFlight = false
            writeQueue.clear()
        }
    }

    // ------------------------------------------------------------ receiving

    private fun onFrame(f: ByteArray) {
        if (f.size <= HDR_LEN || f[0] != MAGIC) return
        val id = ((f[2].toInt() and 0xFF) shl 24) or ((f[3].toInt() and 0xFF) shl 16) or
            ((f[4].toInt() and 0xFF) shl 8) or (f[5].toInt() and 0xFF)
        val idx = f[6].toInt() and 0xFF
        val cnt = f[7].toInt() and 0xFF
        if (cnt == 0 || cnt > MAX_FRAGS || idx >= cnt) return
        if (id in seenIds) return

        val now = System.currentTimeMillis()
        partials.entries.removeAll { now - it.value.startedAt > PARTIAL_TIMEOUT_MS }

        val p = partials.getOrPut(id) { Partial(cnt) }
        if (p.cnt != cnt) {
            partials.remove(id)
            return
        }
        if (p.parts[idx] == null) {
            p.parts[idx] = f.copyOfRange(HDR_LEN, f.size)
            p.got++
        }
        if (p.got == p.cnt) {
            partials.remove(id)
            rememberSeen(id)
            val out = ByteArrayOutputStream()
            for (part in p.parts) out.write(part!!)
            listener.onPacket(out.toByteArray())
        }
    }

    private fun rememberSeen(id: Int) {
        seenIds.add(id)
        if (seenIds.size > SEEN_CAP) seenIds.remove(seenIds.first())
    }
}
