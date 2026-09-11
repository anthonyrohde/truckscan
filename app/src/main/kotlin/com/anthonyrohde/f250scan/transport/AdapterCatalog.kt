package com.anthonyrohde.f250scan.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.anthonyrohde.f250scan.core.transport.ObdTransport
import com.anthonyrohde.f250scan.core.transport.SimulatedVehicleTransport

/** A connection the user can pick from. */
sealed class AdapterChoice {

    abstract val label: String
    abstract val detail: String

    /** Bluetooth Classic. Preferred for wireless: fastest and most robust. */
    data class Classic(
        val device: BluetoothDevice,
        override val label: String,
        override val detail: String,
    ) : AdapterChoice()

    /** BLE. Slower, but the only option for some adapters. */
    data class Ble(
        val device: BluetoothDevice,
        override val label: String,
        override val detail: String,
    ) : AdapterChoice()

    /** Wired over OTG. Fastest and most reliable of all. */
    data class Usb(
        val driverIndex: Int,
        override val label: String,
        override val detail: String,
    ) : AdapterChoice()

    /**
     * The built-in simulator.
     *
     * Kept in the shipped app on purpose: it lets the whole interface be
     * explored on the sofa, and makes it obvious whether a problem is with the
     * app or with the adapter.
     */
    data object Simulator : AdapterChoice() {
        override val label = "Simulated vehicle"
        override val detail = "No hardware required - explore the app without the truck"
    }
}

/**
 * Finds connectable adapters and builds transports for them.
 *
 * Also holds the advice about which hardware can do what, because that is the
 * single most consequential thing a new user gets wrong: a cheap ELM327 clone
 * connects fine and then appears to show that the truck has almost no modules.
 */
@SuppressLint("MissingPermission")
class AdapterCatalog(private val context: Context) {

    private var usbDrivers = emptyList<com.hoho.android.usbserial.driver.UsbSerialDriver>()

    fun hasBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** Everything we can offer right now, best option first. */
    fun available(): List<AdapterChoice> = buildList {
        usbDrivers = runCatching { UsbSerialTransport.findDrivers(context) }
            .getOrDefault(emptyList())

        usbDrivers.forEachIndexed { index, driver ->
            add(
                AdapterChoice.Usb(
                    driverIndex = index,
                    label = driver.device.productName ?: "USB serial adapter",
                    detail = "Wired - fastest and most reliable",
                ),
            )
        }

        if (hasBluetoothPermission()) {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager
            val bonded: Set<BluetoothDevice> = runCatching {
                manager?.adapter?.bondedDevices ?: emptySet()
            }.getOrDefault(emptySet())

            for (device in bonded) {
                val name = device.name ?: "Unnamed device"
                if (!looksLikeObdAdapter(name)) continue

                val isBleOnly = device.type == BluetoothDevice.DEVICE_TYPE_LE
                if (isBleOnly) {
                    add(AdapterChoice.Ble(device, name, "BLE - slower sample rates"))
                } else {
                    add(AdapterChoice.Classic(device, name, "Bluetooth - paired"))
                }
            }
        }

        add(AdapterChoice.Simulator)
    }

    fun createTransport(choice: AdapterChoice): ObdTransport = when (choice) {
        is AdapterChoice.Classic -> BluetoothSppTransport(choice.device)
        is AdapterChoice.Ble -> BleTransport(context, choice.device)
        is AdapterChoice.Usb -> UsbSerialTransport(
            context,
            usbDrivers.getOrNull(choice.driverIndex)
                ?: error("USB adapter is no longer attached"),
        )
        AdapterChoice.Simulator -> SimulatedVehicleTransport()
    }

    /**
     * Filters the paired-device list down to plausible adapters.
     *
     * A truck-adjacent phone is usually paired to the vehicle's own SYNC unit,
     * headphones and so on. Showing those as diagnostic adapters invites a
     * confusing failed connection.
     */
    private fun looksLikeObdAdapter(name: String): Boolean {
        val lower = name.lowercase()
        return KNOWN_ADAPTER_HINTS.any { lower.contains(it) }
    }

    companion object {
        private val KNOWN_ADAPTER_HINTS = listOf(
            "obdlink", "stn", "obdii", "obd2", "obd-ii", "obd",
            "elm327", "elm", "vgate", "vlinker", "viecar", "konnwei",
            "scantool", "carista", "veepeak",
        )

        /**
         * Shown on the connect screen. This is deliberately blunt, because the
         * hardware choice decides what the app can actually do.
         */
        const val HARDWARE_ADVICE =
            "Multi-bus access needs an STN-based adapter: an OBDLink EX (USB) or " +
                "MX+ (Bluetooth). Only those can re-route the connector pins to reach " +
                "MS-CAN and HS-CAN2, where the body, cluster and SYNC modules live. " +
                "A generic ELM327 clone is wired to pins 6/14 only: it will read the " +
                "engine and transmission, and will make the truck look as though it " +
                "has no other modules at all."
    }
}
