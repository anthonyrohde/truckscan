package com.anthonyrohde.truckscan.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.anthonyrohde.truckscan.core.adapter.AdapterProbe
import com.anthonyrohde.truckscan.core.transport.ObdTransport
import com.anthonyrohde.truckscan.core.transport.TransportClosedException
import com.anthonyrohde.truckscan.core.transport.TransportException
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
            serialPort.dtr = true
            serialPort.rts = true
            val rate = negotiateBaudRate(serialPort)
            if (rate == null) {
                // Leave nothing open behind us: a port still held here cannot
                // be reopened, so the retry the message asks for would fail
                // for a different reason than the one that caused it.
                runCatching { serialPort.close() }
                throw TransportException(
                    "$description is plugged in but did not answer at any line " +
                        "rate. Unplug it, plug it back in, and try again.",
                )
            }
            negotiatedBaudRate = rate
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
     * Finds the line rate the adapter is actually speaking, by asking it.
     *
     * The previous version set a rate and treated setParameters() returning
     * without throwing as proof it worked. It is not. On a CDC virtual COM
     * port that call only sends a line-coding request, and the device is free
     * to ignore it - so the first and fastest candidate was always "accepted"
     * and the link was left at 2 Mbit/s talking to an adapter sitting at its
     * power-up default. Every response came back empty, which surfaced as
     * "Unknown adapter" and, because no STN identity could be read, as an
     * adapter with no multi-bus support.
     *
     * The only reliable test is whether the adapter answers. ATI is the right
     * probe: every ELM327 and every clone implements it, it changes no state,
     * and its reply is recognisable.
     *
     * 115,200 is tried first because that is where ELM327 and STN adapters
     * power up. Going faster is a separate step - an STN moves only when told
     * to with STBR, and both ends must switch together - so it is not
     * something to attempt by guessing at the host end.
     */
    private fun negotiateBaudRate(serialPort: UsbSerialPort): Int? {
        for (candidate in BAUD_CANDIDATES) {
            val configured = runCatching {
                serialPort.setParameters(
                    candidate,
                    DATA_BITS,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE,
                )
            }.isSuccess
            if (!configured) continue
            if (respondsToProbe(serialPort)) return candidate
        }
        // Nothing answered at any rate. Say so, rather than reporting a
        // successful connection to an adapter that is not talking - which is
        // what the old code did, and it looked exactly like a dead vehicle.
        runCatching {
            serialPort.setParameters(
                DEFAULT_BAUD,
                DATA_BITS,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
        }
        return null
    }

    /** True when the adapter answers ATI at the rate currently configured. */
    private fun respondsToProbe(serialPort: UsbSerialPort): Boolean = runCatching {
        // Discard whatever the previous rate left in the buffers, or a partial
        // frame read as garbage will be mistaken for a reply. Not every driver
        // implements the hardware purge, so the read-drain below is what this
        // actually relies on.
        runCatching { serialPort.purgeHwBuffers(true, true) }
        drain(serialPort)

        serialPort.write("\r".toByteArray(), PROBE_WRITE_TIMEOUT_MS)
        drain(serialPort)
        serialPort.write("ATI\r".toByteArray(), PROBE_WRITE_TIMEOUT_MS)

        // Collect briefly rather than taking the first read: a reply can
        // arrive split across packets.
        val reply = StringBuilder()
        val deadline = System.currentTimeMillis() + PROBE_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            val n = serialPort.read(readBuffer, PROBE_READ_TIMEOUT_MS)
            if (n > 0) reply.append(String(readBuffer, 0, n, Charsets.US_ASCII))
            if (reply.contains('>')) break
        }
        AdapterProbe.looksLikeAdapter(reply.toString())
    }.getOrDefault(false)

    private fun drain(serialPort: UsbSerialPort) {
        while (serialPort.read(readBuffer, PROBE_READ_TIMEOUT_MS) > 0) {
            // Discard.
        }
    }

    companion object {
        /**
         * Line rates to try, in the order worth trying them.
         *
         * 115,200 leads because it is where ELM327 and STN adapters power up,
         * so it is the rate an adapter is speaking unless something has moved
         * it. The faster entries cover an adapter left at a higher rate by a
         * previous session; the slower ones cover older bridges.
         */
        private val BAUD_CANDIDATES =
            listOf(115_200, 2_000_000, 1_000_000, 500_000, 38_400, 9_600)

        private const val DATA_BITS = 8
        private const val WRITE_TIMEOUT_MS = 2_000

        /** Where ELM327 and STN adapters power up, and the fallback. */
        private const val DEFAULT_BAUD = 115_200

        private const val PROBE_WRITE_TIMEOUT_MS = 500
        private const val PROBE_READ_TIMEOUT_MS = 120
        private const val PROBE_WINDOW_MS = 400L

        private const val ACTION_USB_PERMISSION = "com.anthonyrohde.truckscan.USB_PERMISSION"

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
