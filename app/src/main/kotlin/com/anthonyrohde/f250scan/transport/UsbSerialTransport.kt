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

    override val isOpen: Boolean get() = port?.isOpen == true

    override val description: String
        get() = driver.device.productName ?: "USB adapter ${driver.device.deviceId}"

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
            // 115200 8N1 is what the STN adapters present over USB. The ELM327
            // baud setting is irrelevant here: this is a virtual COM port.
            serialPort.setParameters(
                BAUD_RATE,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
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

    companion object {
        private const val BAUD_RATE = 115_200
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
