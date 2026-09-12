package com.anthonyrohde.truckscan.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.anthonyrohde.truckscan.core.transport.ObdTransport
import com.anthonyrohde.truckscan.core.transport.TransportClosedException
import com.anthonyrohde.truckscan.core.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Bluetooth Low Energy transport.
 *
 * Needed for BLE-only adapters such as the OBDLink CX, and for iOS-oriented
 * dongles generally. BLE is the slower option: every exchange is a GATT write
 * followed by a notification, which caps live-data sample rates well below what
 * Classic SPP manages. Prefer [BluetoothSppTransport] when the adapter supports
 * it.
 *
 * Characteristic UUIDs vary between adapters, and several vendors ship their
 * own. Rather than maintain a table, this discovers the first service that has
 * both a notifying characteristic and a writable one, which is what every
 * serial-over-BLE adapter looks like.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : ObdTransport {

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val incoming = Channel<ByteArray>(Channel.BUFFERED)
    private val writeLock = Mutex()

    private var connected = CompletableDeferred<Unit>()
    private var servicesReady = CompletableDeferred<Unit>()

    @Volatile
    private var open = false

    override val isOpen: Boolean get() = open

    override val description: String get() = "${device.name ?: "Unknown"} (${device.address}) BLE"

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!connected.isCompleted) connected.complete(Unit)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    open = false
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(
                            TransportException("BLE adapter disconnected during connect"),
                        )
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (!servicesReady.isCompleted) {
                    servicesReady.completeExceptionally(
                        TransportException("BLE service discovery failed (status $status)"),
                    )
                }
                return
            }

            // Find a service exposing both a notify and a write characteristic.
            for (service in g.services) {
                val notify = service.characteristics.firstOrNull {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                }
                val write = service.characteristics.firstOrNull {
                    it.properties and (
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        ) != 0
                }
                if (notify != null && write != null) {
                    notifyCharacteristic = notify
                    writeCharacteristic = write
                    break
                }
            }

            val notify = notifyCharacteristic
            if (notify == null || writeCharacteristic == null) {
                if (!servicesReady.isCompleted) {
                    servicesReady.completeExceptionally(
                        TransportException(
                            "$description does not expose a serial-style BLE service. " +
                                "If this adapter also supports Bluetooth Classic, pair " +
                                "it and connect that way instead.",
                        ),
                    )
                }
                return
            }

            // Larger MTU means fewer notifications per response, which matters
            // a lot for multi-frame reads.
            g.requestMtu(REQUESTED_MTU)

            g.setCharacteristicNotification(notify, true)
            notify.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)?.let { descriptor ->
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                    )
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(descriptor)
                }
            }

            open = true
            if (!servicesReady.isCompleted) servicesReady.complete(Unit)
        }

        @Deprecated("Required for API level below 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            characteristic.value?.let { incoming.trySend(it.copyOf()) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            incoming.trySend(value.copyOf())
        }
    }

    override suspend fun open() {
        if (open) return
        connected = CompletableDeferred()
        servicesReady = CompletableDeferred()

        gatt = device.connectGatt(context, false, callback)
            ?: throw TransportException("Could not open a BLE connection to $description")

        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
            ?: throw TransportException("$description did not respond to a BLE connection")

        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { servicesReady.await() }
            ?: throw TransportException("$description did not finish BLE service discovery")
    }

    override suspend fun write(bytes: ByteArray) = writeLock.withLock {
        val g = gatt ?: throw TransportClosedException()
        val characteristic = writeCharacteristic ?: throw TransportClosedException()

        // GATT writes are capped by the negotiated MTU, so long commands are
        // chunked. ELM commands are short, but a raw frame plus header can
        // still exceed a default 23-byte MTU.
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + MAX_CHUNK, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)

            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.value = chunk
                    g.writeCharacteristic(characteristic)
                }
            }
            if (!ok) throw TransportException("BLE write to $description was rejected")
            offset = end
        }
    }

    override suspend fun read(timeoutMillis: Long): ByteArray {
        if (!open) throw TransportClosedException()
        return withTimeoutOrNull(timeoutMillis) { incoming.receive() } ?: ByteArray(0)
    }

    override suspend fun close() {
        open = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
    }

    companion object {
        val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val REQUESTED_MTU = 517
        private const val MAX_CHUNK = 20
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
    }
}
