package com.anthonyrohde.truckscan.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.anthonyrohde.truckscan.core.transport.ObdTransport
import com.anthonyrohde.truckscan.core.transport.TransportClosedException
import com.anthonyrohde.truckscan.core.transport.TransportException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic (RFCOMM/SPP) transport.
 *
 * This is the path for an OBDLink MX+, which is the adapter most people will
 * use with a phone. Classic rather than BLE because SPP gives a plain byte
 * stream with far better throughput than GATT notifications, and throughput is
 * what decides live-data sample rate.
 */
@SuppressLint("MissingPermission")
class BluetoothSppTransport(
    private val device: BluetoothDevice,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ObdTransport {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val readBuffer = ByteArray(4096)

    override val isOpen: Boolean get() = socket?.isConnected == true

    override val description: String get() = "${device.name ?: "Unknown"} (${device.address})"

    override suspend fun open() = withContext(dispatcher) {
        if (isOpen) return@withContext

        try {
            val created = device.createRfcommSocketToServiceRecord(SPP_UUID)
            created.connect()
            socket = created
            input = created.inputStream
            output = created.outputStream
        } catch (e: IOException) {
            runCatching { socket?.close() }
            socket = null
            throw TransportException(
                "Could not connect to $description over Bluetooth. Check the adapter " +
                    "is paired, plugged into the OBD port, and not still connected to " +
                    "another app or phone.",
                e,
            )
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(dispatcher) {
        val stream = output ?: throw TransportClosedException()
        try {
            stream.write(bytes)
            stream.flush()
        } catch (e: IOException) {
            throw TransportException("Lost the Bluetooth link to $description", e)
        }
    }

    /**
     * Reads whatever has arrived, waiting up to [timeoutMillis] for the first byte.
     *
     * `available()` is polled rather than calling the blocking `read`, because
     * a blocking read on an RFCOMM socket cannot be interrupted and would
     * strand the coroutine when the user cancels a scan.
     */
    override suspend fun read(timeoutMillis: Long): ByteArray = withContext(dispatcher) {
        val stream = input ?: throw TransportClosedException()
        val deadline = System.currentTimeMillis() + timeoutMillis

        try {
            while (System.currentTimeMillis() < deadline) {
                val available = stream.available()
                if (available > 0) {
                    val count = stream.read(readBuffer, 0, minOf(available, readBuffer.size))
                    if (count > 0) return@withContext readBuffer.copyOf(count)
                }
                delay(POLL_INTERVAL_MS)
            }
        } catch (e: IOException) {
            throw TransportException("Lost the Bluetooth link to $description", e)
        }
        ByteArray(0)
    }

    override suspend fun close() = withContext(dispatcher) {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    companion object {
        /** The standard Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * 4 ms keeps latency low without spinning the CPU. At 500 kbps a full
         * 8-byte frame takes well under a millisecond on the wire, so the
         * bottleneck is the Bluetooth link, not this.
         */
        private const val POLL_INTERVAL_MS = 4L
    }
}
