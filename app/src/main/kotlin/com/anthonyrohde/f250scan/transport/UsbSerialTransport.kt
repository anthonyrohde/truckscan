package com.anthonyrohde.f250scan.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.anthonyrohde.f250scan.core.transport.ObdTransport
import com.anthonyrohde.f250scan.core.transport.TransportClosedException
import com.anthonyrohde.f250scan.core.transport.TransportException
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * USB serial transport, for a wired adapter on an OTG cable.
 *
 * This is the fastest and most reliable option - no radio, no pairing, no
 * dropouts - and is what to use for anything long-running such as a full
 * As-Built read or a logging session. An OBDLink EX connects this way.
 *
 * The trade-off is physical: the phone is tethered to the OBD port, and many
 * phones will not supply bus power to the adapter while also charging.
 */
class UsbSerialTransport(
    private val context: Context,
    private val driver: UsbSerialDriver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ObdTransport {

    private var port: UsbSerialPort? = null
    private val readBuffer = ByteArray(4096)

    /** Line rate actually accepted by the driver. Surfaced for the log. */
    var negotiatedBaudRate: Int = 0
        private set

    override val isOpen: Boolean get() = port?.isOpen == true

    override val description: String
        get() {
            val name = driver.device.productName ?: "USB adapter ${driver.device.deviceId}"
            return if (negotiatedBaudRate > 0) {
                "$name @ ${negotiatedBaudRate / 1000} kbaud"
            } else {
                name
            }
        }

    override suspend fun open() = withContext(dispatcher) {
        if (isOpen) return@withContext

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(driver.device)) {
            throw TransportException(
                "Permission to use $description has not been granted. Unplug and " +
                    "replug the adapter, then allow access when Android asks.",
            )
        }

        val connection = manager.openDevice(driver.device)
            ?: throw TransportException("Could not open $description")

        try {
            val serialPort = driver.ports.first()
            serialPort.open(connection)
            negotiatedBaudRate = negotiateBaudRate(serialPort)
            serialPort.dtr = true
            serialPort.rts = true
            port = serialPort
        } catch (e: IOException) {
            runCatching { port?.close() }
            port = null
            throw TransportException("Could not configure $description", e)
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(dispatcher) {
        val serialPort = port ?: throw TransportClosedException()
        try {
            serialPort.write(bytes, WRITE_TIMEOUT_MS)
        } catch (e: IOException) {
            throw TransportException("Lost the USB link to $description", e)
        }
    }

    override suspend fun read(timeoutMillis: Long): ByteArray = withContext(dispatcher) {
        val serialPort = port ?: throw TransportClosedException()
        try {
            val count = serialPort.read(readBuffer, timeoutMillis.toInt())
            if (count > 0) readBuffer.copyOf(count) else ByteArray(0)
        } catch (e: IOException) {
            throw TransportException("Lost the USB link to $description", e)
        }
    }

    override suspend fun close() = withContext(dispatcher) {
        runCatching { port?.close() }
        port = null
    }

    /**
     * Picks the fastest line rate the driver will accept.
     *
     * The OBDLink EX advertises 2,000 kbit/s, so the 115,200 this used to
     * hardcode left most of the link on the table. Rather than swap one guess
     * for another, this tries the candidates highest first and keeps the first
     * that is accepted.
     *
     * Two things make that safe. On a CDC virtual COM port the rate is
     * negotiated by USB itself and the setting is effectively advisory, so a
     * high value costs nothing. On a real UART bridge an unsupported rate is
     * rejected outright, and we fall through to the next candidate. Either way
     * 115,200 remains the floor, which is the rate every adapter accepts.
     */
    private fun negotiateBaudRate(serialPort: UsbSerialPort): Int {
        for (candidate in BAUD_CANDIDATES) {
            val accepted = runCatching {
                serialPort.setParameters(
                    candidate,
                    DATA_BITS,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE,
                )
            }.isSuccess
            if (accepted) return candidate
        }
        // Nothing was accepted; report the floor and let the first read or
        // write surface the real problem with a useful message.
        return BAUD_CANDIDATES.last()
    }

    companion object {
        /**
         * Line rates to try, fastest first.
         *
         * 2 Mbit/s is what the OBDLink EX advertises; the intermediate steps
         * cover bridges that cap lower, and 115,200 is the universal floor.
         */
        private val BAUD_CANDIDATES = listOf(2_000_000, 1_000_000, 500_000, 115_200)

        private const val DATA_BITS = 8
        private const val WRITE_TIMEOUT_MS = 2_000

        private const val ACTION_USB_PERMISSION = "com.anthonyrohde.f250scan.USB_PERMISSION"

        /** Adapters currently attached. */
        fun findDrivers(context: Context): List<UsbSerialDriver> {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        }

        /** Asks Android for permission to talk to [driver]. */
        fun requestPermission(context: Context, driver: UsbSerialDriver) {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val intent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE,
            )
            manager.requestPermission(driver.device, intent)
        }
    }
}
