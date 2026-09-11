package com.anthonyrohde.f250scan.core.pid

import com.anthonyrohde.f250scan.core.util.u8

/** A decoded live-data sample. */
data class PidValue(
    val pid: Pid,
    val value: Double,
    val raw: ByteArray,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    val formatted: String get() = pid.format(value)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PidValue) return false
        return pid.id == other.pid.id && value == other.value
    }

    override fun hashCode(): Int = 31 * pid.id + value.hashCode()
}

/**
 * A readable live parameter.
 *
 * @param byteCount payload bytes the module returns, used to validate a reply
 *   before decoding it. Decoding a short reply would silently produce a
 *   plausible-looking wrong number, which is worse than an error.
 */
data class Pid(
    val id: Int,
    val name: String,
    val unit: String,
    val byteCount: Int,
    val minValue: Double,
    val maxValue: Double,
    /** OBD-II service to request this under. Mode 01 is current live data. */
    val mode: Int = 0x01,
    val decimals: Int = 1,
    private val decoder: (ByteArray) -> Double,
) {
    val hexId: String get() = id.toString(16).uppercase().padStart(2, '0')

    fun decode(payload: ByteArray): PidValue? {
        if (payload.size < byteCount) return null
        return PidValue(this, decoder(payload), payload.copyOf())
    }

    fun format(value: Double): String {
        val number = if (decimals == 0) {
            value.toLong().toString()
        } else {
            String.format("%.${decimals}f", value)
        }
        return if (unit.isEmpty()) number else "$number $unit"
    }

    /** Fraction of the way through the expected range, for gauge rendering. */
    fun normalise(value: Double): Double =
        if (maxValue <= minValue) 0.0
        else ((value - minValue) / (maxValue - minValue)).coerceIn(0.0, 1.0)
}

/**
 * Standard OBD-II mode 01 parameters (SAE J1979).
 *
 * These are genuinely standard and work on any compliant vehicle, which is why
 * they are given as fixed formulas rather than discovered. The parameters a
 * 6.7L Power Stroke owner actually wants most - individual EGT sensors, DPF
 * soot load, turbo vane position, DEF level, injector balance rates - are
 * mostly *not* in this list: they live behind Ford-specific identifiers on the
 * PCM and are found by the module scan instead. See docs/PROTOCOL.md.
 */
object PidCatalog {

    val SUPPORTED_PIDS_01_20 = 0x00
    val SUPPORTED_PIDS_21_40 = 0x20
    val SUPPORTED_PIDS_41_60 = 0x40

    val ENGINE_RPM = Pid(
        id = 0x0C, name = "Engine speed", unit = "rpm", byteCount = 2,
        minValue = 0.0, maxValue = 4000.0, decimals = 0,
    ) { ((it.u8(0) * 256) + it.u8(1)) / 4.0 }

    val VEHICLE_SPEED = Pid(
        id = 0x0D, name = "Vehicle speed", unit = "km/h", byteCount = 1,
        minValue = 0.0, maxValue = 200.0, decimals = 0,
    ) { it.u8(0).toDouble() }

    val COOLANT_TEMP = Pid(
        id = 0x05, name = "Engine coolant temperature", unit = "°C", byteCount = 1,
        minValue = -40.0, maxValue = 130.0, decimals = 0,
    ) { (it.u8(0) - 40).toDouble() }

    val ENGINE_LOAD = Pid(
        id = 0x04, name = "Calculated engine load", unit = "%", byteCount = 1,
        minValue = 0.0, maxValue = 100.0,
    ) { it.u8(0) * 100.0 / 255.0 }

    val INTAKE_MAP = Pid(
        id = 0x0B, name = "Intake manifold pressure", unit = "kPa", byteCount = 1,
        minValue = 0.0, maxValue = 255.0, decimals = 0,
    ) { it.u8(0).toDouble() }

    val INTAKE_AIR_TEMP = Pid(
        id = 0x0F, name = "Intake air temperature", unit = "°C", byteCount = 1,
        minValue = -40.0, maxValue = 120.0, decimals = 0,
    ) { (it.u8(0) - 40).toDouble() }

    val MAF_RATE = Pid(
        id = 0x10, name = "Mass air flow", unit = "g/s", byteCount = 2,
        minValue = 0.0, maxValue = 655.0, decimals = 2,
    ) { ((it.u8(0) * 256) + it.u8(1)) / 100.0 }

    val THROTTLE_POSITION = Pid(
        id = 0x11, name = "Throttle position", unit = "%", byteCount = 1,
        minValue = 0.0, maxValue = 100.0,
    ) { it.u8(0) * 100.0 / 255.0 }

    val RUN_TIME = Pid(
        id = 0x1F, name = "Run time since engine start", unit = "s", byteCount = 2,
        minValue = 0.0, maxValue = 65535.0, decimals = 0,
    ) { ((it.u8(0) * 256) + it.u8(1)).toDouble() }

    val DISTANCE_WITH_MIL = Pid(
        id = 0x21, name = "Distance travelled with MIL on", unit = "km", byteCount = 2,
        minValue = 0.0, maxValue = 65535.0, decimals = 0,
    ) { ((it.u8(0) * 256) + it.u8(1)).toDouble() }

    val FUEL_RAIL_PRESSURE = Pid(
        id = 0x23, name = "Fuel rail gauge pressure", unit = "kPa", byteCount = 2,
        minValue = 0.0, maxValue = 655350.0, decimals = 0,
    ) { ((it.u8(0) * 256) + it.u8(1)) * 10.0 }

    val COMMANDED_EGR = Pid(
        id = 0x2C, name = "Commanded EGR", unit = "%", byteCount = 1,
        minValue = 0.0, maxValue = 100.0,
    ) { it.u8(0) * 100.0 / 255.0 }

    val EGR_ERROR = Pid(
        id = 0x2D, name = "EGR error", unit = "%", byteCount = 1,
        minValue = -100.0, maxValue = 99.2,
    ) { (it.u8(0) - 128) * 100.0 / 128.0 }

    val FUEL_LEVEL = Pid(
        id = 0x2F, name = "Fuel tank level", unit = "%", byteCount = 1,
        minValue = 0.0, maxValue = 100.0,
    ) { it.u8(0) * 100.0 / 255.0 }

    val BAROMETRIC_PRESSURE = Pid(
        id = 0x33, name = "Barometric pressure", unit = "kPa", byteCount = 1,
        minValue = 0.0, maxValue = 255.0, decimals = 0,
    ) { it.u8(0).toDouble() }

    val CONTROL_MODULE_VOLTAGE = Pid(
        id = 0x42, name = "Control module voltage", unit = "V", byteCount = 2,
        minValue = 0.0, maxValue = 16.0, decimals = 2,
    ) { ((it.u8(0) * 256) + it.u8(1)) / 1000.0 }

    val ABSOLUTE_LOAD = Pid(
        id = 0x43, name = "Absolute load value", unit = "%", byteCount = 2,
        minValue = 0.0, maxValue = 25700.0,
    ) { ((it.u8(0) * 256) + it.u8(1)) * 100.0 / 255.0 }

    val AMBIENT_AIR_TEMP = Pid(
        id = 0x46, name = "Ambient air temperature", unit = "°C", byteCount = 1,
        minValue = -40.0, maxValue = 120.0, decimals = 0,
    ) { (it.u8(0) - 40).toDouble() }

    val ACCELERATOR_POSITION = Pid(
        id = 0x49, name = "Accelerator pedal position D", unit = "%", byteCount = 1,
        minValue = 0.0, maxValue = 100.0,
    ) { it.u8(0) * 100.0 / 255.0 }

    val ENGINE_OIL_TEMP = Pid(
        id = 0x5C, name = "Engine oil temperature", unit = "°C", byteCount = 1,
        minValue = -40.0, maxValue = 210.0, decimals = 0,
    ) { (it.u8(0) - 40).toDouble() }

    val FUEL_RATE = Pid(
        id = 0x5E, name = "Engine fuel rate", unit = "L/h", byteCount = 2,
        minValue = 0.0, maxValue = 3212.0, decimals = 2,
    ) { ((it.u8(0) * 256) + it.u8(1)) / 20.0 }

    val DEMANDED_TORQUE = Pid(
        id = 0x61, name = "Driver demanded engine torque", unit = "%", byteCount = 1,
        minValue = -125.0, maxValue = 130.0, decimals = 0,
    ) { (it.u8(0) - 125).toDouble() }

    val ACTUAL_TORQUE = Pid(
        id = 0x62, name = "Actual engine torque", unit = "%", byteCount = 1,
        minValue = -125.0, maxValue = 130.0, decimals = 0,
    ) { (it.u8(0) - 125).toDouble() }

    val REFERENCE_TORQUE = Pid(
        id = 0x63, name = "Engine reference torque", unit = "Nm", byteCount = 2,
        minValue = 0.0, maxValue = 65535.0, decimals = 0,
    ) { ((it.u8(0) * 256) + it.u8(1)).toDouble() }

    /**
     * DPF temperature, bank 1 (PID 0x7C).
     *
     * Four 16-bit fields; we surface the inlet reading, which is the one that
     * indicates an active regeneration. Scaling is (A*256+B)/10 - 40.
     */
    val DPF_TEMP_BANK1 = Pid(
        id = 0x7C, name = "DPF temperature (bank 1 inlet)", unit = "°C", byteCount = 9,
        minValue = -40.0, maxValue = 850.0, decimals = 0,
    ) { (((it.u8(1) * 256) + it.u8(2)) / 10.0) - 40.0 }

    /** Full list, ordered roughly by how often you would want to look at it. */
    val ALL: List<Pid> = listOf(
        ENGINE_RPM, VEHICLE_SPEED, COOLANT_TEMP, ENGINE_LOAD, ABSOLUTE_LOAD,
        INTAKE_MAP, INTAKE_AIR_TEMP, MAF_RATE, THROTTLE_POSITION,
        ACCELERATOR_POSITION, FUEL_RAIL_PRESSURE, FUEL_RATE, FUEL_LEVEL,
        COMMANDED_EGR, EGR_ERROR, ENGINE_OIL_TEMP, DPF_TEMP_BANK1,
        AMBIENT_AIR_TEMP, BAROMETRIC_PRESSURE, CONTROL_MODULE_VOLTAGE,
        DEMANDED_TORQUE, ACTUAL_TORQUE, REFERENCE_TORQUE,
        RUN_TIME, DISTANCE_WITH_MIL,
    )

    private val BY_ID: Map<Int, Pid> = ALL.associateBy { it.id }

    fun byId(id: Int): Pid? = BY_ID[id]

    /**
     * Decodes a supported-PID bitmask response (PIDs 0x00/0x20/0x40).
     *
     * Four bytes, most significant bit first, describing the next 32 PIDs.
     * Asking the vehicle what it supports beats probing blindly: it is one
     * request instead of 32 and avoids logging spurious no-data results.
     */
    fun decodeSupportMask(basePid: Int, payload: ByteArray): List<Int> {
        if (payload.size < 4) return emptyList()
        return buildList {
            for (byteIndex in 0 until 4) {
                val b = payload.u8(byteIndex)
                for (bit in 0 until 8) {
                    if (b and (0x80 shr bit) != 0) {
                        add(basePid + byteIndex * 8 + bit + 1)
                    }
                }
            }
        }
    }
}
