package com.anthonyrohde.truckscan.core.pid

import com.anthonyrohde.truckscan.core.util.u8

/**
 * Standard OBD-II mode 01 parameters (SAE J1979).
 *
 * These are genuinely standardised, which is why they are given as fixed
 * formulas rather than discovered. The vehicle is still asked which it supports
 * before any are offered, so an unsupported parameter never reaches a gauge.
 *
 * ## On the diesel-specific entries
 *
 * The parameters a 6.7L owner actually wants - exhaust gas temperature per
 * sensor, rail pressure, injection timing, turbo data - are in the standard,
 * but several pack multiple sensors into one response behind a support
 * bitmask. Those layouts are implemented from the standard's description and
 * are the least-proven part of this file: if a reading looks wrong on the
 * truck, suspect the byte offsets here before suspecting the sensor. Each is
 * marked below.
 *
 * Ford's own extended parameters - DPF soot load, turbo vane position, DEF
 * quality, injector balance rates - are *not* standard PIDs and are not here.
 * They sit behind manufacturer identifiers on the PCM; the identifier
 * discovery scan finds them, but what they mean is not public.
 */
object PidCatalog {

    // ------------------------------------------------------------ decoders

    private fun byteAt(index: Int): (ByteArray) -> Double = { it.u8(index).toDouble() }

    private fun word(index: Int, scale: Double, offset: Double = 0.0): (ByteArray) -> Double =
        { ((it.u8(index) * 256) + it.u8(index + 1)) * scale + offset }

    private fun percentByte(index: Int): (ByteArray) -> Double =
        { it.u8(index) * 100.0 / 255.0 }

    private fun tempByte(index: Int): (ByteArray) -> Double =
        { (it.u8(index) - 40).toDouble() }

    /** Fuel trim: 0 is neutral, +/-100% at the extremes. */
    private fun trimByte(index: Int): (ByteArray) -> Double =
        { (it.u8(index) - 128) * 100.0 / 128.0 }

    /**
     * Reads one sensor from a bitmask-packed multi-sensor response.
     *
     * Layout per the standard: byte 0 is a bitmask of which sensors are
     * present, then [width] bytes for each present sensor, packed with no gaps.
     * So a sensor's offset depends on how many lower-numbered sensors are
     * supported - which is why this counts bits rather than using a fixed
     * stride. Returns NaN when the sensor is absent, which [Pid.decode] rejects.
     */
    private fun packedSensor(
        sensorIndex: Int,
        width: Int = 2,
        convert: (ByteArray, Int) -> Double,
    ): (ByteArray) -> Double = { payload ->
        val mask = payload.u8(0)
        if (mask and (1 shl sensorIndex) == 0) {
            Double.NaN
        } else {
            var offset = 1
            for (lower in 0 until sensorIndex) {
                if (mask and (1 shl lower) != 0) offset += width
            }
            if (offset + width > payload.size) Double.NaN else convert(payload, offset)
        }
    }

    /** Exhaust gas temperature scaling, shared by the EGT and DPF temperature PIDs. */
    private fun egtCelsius(payload: ByteArray, offset: Int): Double =
        (((payload.u8(offset) * 256) + payload.u8(offset + 1)) / 10.0) - 40.0

    // -------------------------------------------------------------- zones

    private fun coolantZones(): List<GaugeZone> = listOf(
        GaugeZone(-40.0, 70.0, ZoneSeverity.CAUTION, "Not warmed up"),
        GaugeZone(70.0, 105.0, ZoneSeverity.NORMAL, "Normal"),
        GaugeZone(105.0, 113.0, ZoneSeverity.WARNING, "Running hot"),
        GaugeZone(113.0, 200.0, ZoneSeverity.CRITICAL, "Overheating"),
    )

    private fun egtZones(): List<GaugeZone> = listOf(
        GaugeZone(-40.0, 650.0, ZoneSeverity.NORMAL, "Normal"),
        GaugeZone(650.0, 750.0, ZoneSeverity.WARNING, "High - ease off"),
        GaugeZone(750.0, 1200.0, ZoneSeverity.CRITICAL, "Damaging"),
    )

    // --------------------------------------------------------- powertrain

    val ENGINE_RPM = Pid(
        key = "rpm", id = 0x0C, name = "Engine speed", shortName = "RPM",
        unit = "rpm", byteCount = 2, minValue = 0.0, maxValue = 4000.0, decimals = 0,
        style = GaugeStyle.DIAL,
        zones = listOf(
            GaugeZone(0.0, 3000.0, ZoneSeverity.NORMAL, "Normal"),
            GaugeZone(3000.0, 3400.0, ZoneSeverity.WARNING, "High"),
            GaugeZone(3400.0, 6000.0, ZoneSeverity.CRITICAL, "Over-rev"),
        ),
        decoder = word(0, 0.25),
    )

    val VEHICLE_SPEED = Pid(
        key = "speed", id = 0x0D, name = "Vehicle speed", shortName = "Speed",
        unit = "km/h", byteCount = 1, minValue = 0.0, maxValue = 160.0, decimals = 0,
        style = GaugeStyle.DIAL, decoder = byteAt(0),
    )

    val ENGINE_LOAD = Pid(
        key = "load", id = 0x04, name = "Calculated engine load", shortName = "Load",
        unit = "%", byteCount = 1, minValue = 0.0, maxValue = 100.0,
        decoder = percentByte(0),
    )

    val ABSOLUTE_LOAD = Pid(
        key = "abs_load", id = 0x43, name = "Absolute load value", shortName = "Abs load",
        unit = "%", byteCount = 2, minValue = 0.0, maxValue = 200.0,
        decoder = word(0, 100.0 / 255.0),
    )

    val DEMANDED_TORQUE = Pid(
        key = "torque_demand", id = 0x61, name = "Driver demanded torque",
        shortName = "Torque req", unit = "%", byteCount = 1,
        minValue = -125.0, maxValue = 130.0, decimals = 0,
    ) { (it.u8(0) - 125).toDouble() }

    val ACTUAL_TORQUE = Pid(
        key = "torque_actual", id = 0x62, name = "Actual engine torque",
        shortName = "Torque", unit = "%", byteCount = 1,
        minValue = -125.0, maxValue = 130.0, decimals = 0,
    ) { (it.u8(0) - 125).toDouble() }

    val REFERENCE_TORQUE = Pid(
        key = "torque_ref", id = 0x63, name = "Engine reference torque",
        shortName = "Ref torque", unit = "Nm", byteCount = 2,
        minValue = 0.0, maxValue = 2500.0, decimals = 0, decoder = word(0, 1.0),
    )

    // -------------------------------------------------------- temperatures

    val COOLANT_TEMP = Pid(
        key = "coolant", id = 0x05, name = "Engine coolant temperature",
        shortName = "Coolant", unit = "°C", byteCount = 1,
        minValue = -40.0, maxValue = 130.0, decimals = 0,
        zones = coolantZones(), decoder = tempByte(0),
    )

    val ENGINE_OIL_TEMP = Pid(
        key = "oil_temp", id = 0x5C, name = "Engine oil temperature",
        shortName = "Oil temp", unit = "°C", byteCount = 1,
        minValue = -40.0, maxValue = 160.0, decimals = 0,
        zones = listOf(
            GaugeZone(-40.0, 70.0, ZoneSeverity.CAUTION, "Cold"),
            GaugeZone(70.0, 120.0, ZoneSeverity.NORMAL, "Normal"),
            GaugeZone(120.0, 135.0, ZoneSeverity.WARNING, "Hot"),
            GaugeZone(135.0, 250.0, ZoneSeverity.CRITICAL, "Too hot"),
        ),
        decoder = tempByte(0),
    )

    val INTAKE_AIR_TEMP = Pid(
        key = "iat", id = 0x0F, name = "Intake air temperature", shortName = "Intake air",
        unit = "°C", byteCount = 1, minValue = -40.0, maxValue = 120.0, decimals = 0,
        decoder = tempByte(0),
    )

    val AMBIENT_AIR_TEMP = Pid(
        key = "ambient", id = 0x46, name = "Ambient air temperature", shortName = "Ambient",
        unit = "°C", byteCount = 1, minValue = -40.0, maxValue = 60.0, decimals = 0,
        decoder = tempByte(0),
    )

    val CHARGE_AIR_TEMP = Pid(
        key = "cact", id = 0x77, name = "Charge air cooler temperature",
        shortName = "Intercooler", unit = "°C", byteCount = 3,
        minValue = -40.0, maxValue = 160.0, decimals = 0,
        decoder = packedSensor(0) { p, o -> (p.u8(o) - 40).toDouble() },
    )

    val EGR_TEMP = Pid(
        key = "egr_temp", id = 0x6B, name = "EGR temperature", shortName = "EGR temp",
        unit = "°C", byteCount = 3, minValue = -40.0, maxValue = 600.0, decimals = 0,
        decoder = packedSensor(0) { p, o -> (p.u8(o) - 40).toDouble() },
    )

    /**
     * Exhaust gas temperature, bank 1 sensors 1-4.
     *
     * Four sensors behind one bitmask-packed PID. The most useful diesel
     * readings in the standard set, and the layout most worth verifying
     * against the truck.
     */
    val EGT_1 = egtSensor("egt1", 0x78, 0, "Exhaust gas temp 1", "EGT 1")
    val EGT_2 = egtSensor("egt2", 0x78, 1, "Exhaust gas temp 2", "EGT 2")
    val EGT_3 = egtSensor("egt3", 0x78, 2, "Exhaust gas temp 3", "EGT 3")
    val EGT_4 = egtSensor("egt4", 0x78, 3, "Exhaust gas temp 4", "EGT 4")

    private fun egtSensor(key: String, id: Int, index: Int, name: String, short: String) = Pid(
        key = key, id = id, name = name, shortName = short, unit = "°C",
        byteCount = 3, minValue = -40.0, maxValue = 900.0, decimals = 0,
        zones = egtZones(), decoder = packedSensor(index, 2, ::egtCelsius),
    )

    val DPF_TEMP = Pid(
        key = "dpf_temp", id = 0x7C, name = "DPF temperature", shortName = "DPF temp",
        unit = "°C", byteCount = 9, minValue = -40.0, maxValue = 850.0, decimals = 0,
        zones = listOf(
            GaugeZone(-40.0, 500.0, ZoneSeverity.NORMAL, "Normal"),
            GaugeZone(500.0, 700.0, ZoneSeverity.CAUTION, "Regenerating"),
            GaugeZone(700.0, 1200.0, ZoneSeverity.WARNING, "Very hot"),
        ),
    ) { egtCelsius(it, 1) }

    // ----------------------------------------------------------- pressures

    val INTAKE_MAP = Pid(
        key = "map", id = 0x0B, name = "Intake manifold pressure", shortName = "Boost",
        unit = "kPa", byteCount = 1, minValue = 0.0, maxValue = 255.0, decimals = 0,
        decoder = byteAt(0),
    )

    val BAROMETRIC_PRESSURE = Pid(
        key = "baro", id = 0x33, name = "Barometric pressure", shortName = "Baro",
        unit = "kPa", byteCount = 1, minValue = 0.0, maxValue = 120.0, decimals = 0,
        decoder = byteAt(0),
    )

    val FUEL_RAIL_PRESSURE = Pid(
        key = "rail_gauge", id = 0x23, name = "Fuel rail gauge pressure",
        shortName = "Rail press", unit = "kPa", byteCount = 2,
        minValue = 0.0, maxValue = 250_000.0, decimals = 0, decoder = word(0, 10.0),
    )

    val FUEL_RAIL_ABSOLUTE = Pid(
        key = "rail_abs", id = 0x59, name = "Fuel rail absolute pressure",
        shortName = "Rail abs", unit = "kPa", byteCount = 2,
        minValue = 0.0, maxValue = 250_000.0, decimals = 0, decoder = word(0, 10.0),
    )

    val FUEL_PRESSURE = Pid(
        key = "fuel_press", id = 0x0A, name = "Fuel pressure", shortName = "Fuel press",
        unit = "kPa", byteCount = 1, minValue = 0.0, maxValue = 765.0, decimals = 0,
    ) { it.u8(0) * 3.0 }

    val EXHAUST_PRESSURE = Pid(
        key = "exh_press", id = 0x73, name = "Exhaust pressure", shortName = "Exh press",
        unit = "kPa", byteCount = 3, minValue = 0.0, maxValue = 500.0, decimals = 0,
        decoder = packedSensor(0) { p, o -> ((p.u8(o) * 256) + p.u8(o + 1)) / 128.0 },
    )

    // ------------------------------------------------------------ air flow

    val MAF_RATE = Pid(
        key = "maf", id = 0x10, name = "Mass air flow", shortName = "MAF",
        unit = "g/s", byteCount = 2, minValue = 0.0, maxValue = 400.0, decimals = 2,
        decoder = word(0, 0.01),
    )

    // -------------------------------------------------------- driver input

    val THROTTLE_POSITION = Pid(
        key = "throttle", id = 0x11, name = "Throttle position", shortName = "Throttle",
        unit = "%", byteCount = 1, minValue = 0.0, maxValue = 100.0,
        decoder = percentByte(0),
    )

    val RELATIVE_THROTTLE = Pid(
        key = "throttle_rel", id = 0x45, name = "Relative throttle position",
        shortName = "Throttle rel", unit = "%", byteCount = 1,
        minValue = 0.0, maxValue = 100.0, decoder = percentByte(0),
    )

    val ACCELERATOR_D = Pid(
        key = "pedal_d", id = 0x49, name = "Accelerator pedal position D",
        shortName = "Pedal D", unit = "%", byteCount = 1,
        minValue = 0.0, maxValue = 100.0, decoder = percentByte(0),
    )

    val ACCELERATOR_E = Pid(
        key = "pedal_e", id = 0x4A, name = "Accelerator pedal position E",
        shortName = "Pedal E", unit = "%", byteCount = 1,
        minValue = 0.0, maxValue = 100.0, decoder = percentByte(0),
    )

    val COMMANDED_THROTTLE = Pid(
        key = "throttle_cmd", id = 0x4C, name = "Commanded throttle actuator",
        shortName = "Throttle cmd", unit = "%", byteCount = 1,
        minValue = 0.0, maxValue = 100.0, decoder = percentByte(0),
    )

    // --------------------------------------------------------------- fuel

    val FUEL_LEVEL = Pid(
        key = "fuel_level", id = 0x2F, name = "Fuel tank level", shortName = "Fuel",
        unit = "%", byteCount = 1, minValue = 0.0, maxValue = 100.0, decimals = 0,
        zones = listOf(
            GaugeZone(0.0, 10.0, ZoneSeverity.CRITICAL, "Nearly empty"),
            GaugeZone(10.0, 25.0, ZoneSeverity.CAUTION, "Low"),
            GaugeZone(25.0, 101.0, ZoneSeverity.NORMAL, "OK"),
        ),
        decoder = percentByte(0),
    )

    val FUEL_RATE = Pid(
        key = "fuel_rate", id = 0x5E, name = "Engine fuel rate", shortName = "Fuel rate",
        unit = "L/h", byteCount = 2, minValue = 0.0, maxValue = 120.0, decimals = 2,
        decoder = word(0, 0.05),
    )

    val INJECTION_TIMING = Pid(
        key = "inj_timing", id = 0x5D, name = "Fuel injection timing",
        shortName = "Inj timing", unit = "°", byteCount = 2,
        minValue = -210.0, maxValue = 302.0, decimals = 1,
        decoder = word(0, 1.0 / 128.0, -210.0),
    )

    val TIMING_ADVANCE = Pid(
        key = "timing_adv", id = 0x0E, name = "Timing advance", shortName = "Timing",
        unit = "°", byteCount = 1, minValue = -64.0, maxValue = 64.0,
    ) { (it.u8(0) / 2.0) - 64.0 }

    val SHORT_TRIM_1 = Pid(
        key = "stft1", id = 0x06, name = "Short term fuel trim bank 1",
        shortName = "STFT B1", unit = "%", byteCount = 1,
        minValue = -100.0, maxValue = 99.2, decoder = trimByte(0),
    )

    val LONG_TRIM_1 = Pid(
        key = "ltft1", id = 0x07, name = "Long term fuel trim bank 1",
        shortName = "LTFT B1", unit = "%", byteCount = 1,
        minValue = -100.0, maxValue = 99.2, decoder = trimByte(0),
    )

    // ---------------------------------------------------------------- EGR

    val COMMANDED_EGR = Pid(
        key = "egr_cmd", id = 0x2C, name = "Commanded EGR", shortName = "EGR cmd",
        unit = "%", byteCount = 1, minValue = 0.0, maxValue = 100.0,
        decoder = percentByte(0),
    )

    val EGR_ERROR = Pid(
        key = "egr_err", id = 0x2D, name = "EGR error", shortName = "EGR error",
        unit = "%", byteCount = 1, minValue = -100.0, maxValue = 99.2,
        decoder = trimByte(0),
    )

    // ------------------------------------------------------------ electrical

    val CONTROL_MODULE_VOLTAGE = Pid(
        key = "voltage", id = 0x42, name = "Control module voltage", shortName = "Voltage",
        unit = "V", byteCount = 2, minValue = 8.0, maxValue = 16.0, decimals = 2,
        zones = listOf(
            GaugeZone(0.0, 11.5, ZoneSeverity.CRITICAL, "Flat"),
            GaugeZone(11.5, 12.4, ZoneSeverity.CAUTION, "Low"),
            GaugeZone(12.4, 15.0, ZoneSeverity.NORMAL, "Healthy"),
            GaugeZone(15.0, 30.0, ZoneSeverity.WARNING, "Overcharging"),
        ),
        decoder = word(0, 0.001),
    )

    // ------------------------------------------------------------- counters

    val RUN_TIME = Pid(
        key = "runtime", id = 0x1F, name = "Run time since start", shortName = "Run time",
        unit = "s", byteCount = 2, minValue = 0.0, maxValue = 7200.0, decimals = 0,
        style = GaugeStyle.BAR, isCounter = true, decoder = word(0, 1.0),
    )

    val DISTANCE_WITH_MIL = Pid(
        key = "dist_mil", id = 0x21, name = "Distance with MIL on", shortName = "Dist MIL",
        unit = "km", byteCount = 2, minValue = 0.0, maxValue = 5000.0, decimals = 0,
        style = GaugeStyle.BAR, isCounter = true, decoder = word(0, 1.0),
    )

    val DISTANCE_SINCE_CLEAR = Pid(
        key = "dist_clear", id = 0x31, name = "Distance since codes cleared",
        shortName = "Dist clear", unit = "km", byteCount = 2,
        minValue = 0.0, maxValue = 20000.0, decimals = 0,
        style = GaugeStyle.BAR, isCounter = true, decoder = word(0, 1.0),
    )

    val TIME_WITH_MIL = Pid(
        key = "time_mil", id = 0x4D, name = "Time run with MIL on", shortName = "Time MIL",
        unit = "min", byteCount = 2, minValue = 0.0, maxValue = 6000.0, decimals = 0,
        style = GaugeStyle.BAR, isCounter = true, decoder = word(0, 1.0),
    )

    val TIME_SINCE_CLEAR = Pid(
        key = "time_clear", id = 0x4E, name = "Time since codes cleared",
        shortName = "Time clear", unit = "min", byteCount = 2,
        minValue = 0.0, maxValue = 60000.0, decimals = 0,
        style = GaugeStyle.BAR, isCounter = true, decoder = word(0, 1.0),
    )

    val WARMUPS_SINCE_CLEAR = Pid(
        key = "warmups", id = 0x30, name = "Warm-ups since codes cleared",
        shortName = "Warm-ups", unit = "", byteCount = 1,
        minValue = 0.0, maxValue = 255.0, decimals = 0,
        style = GaugeStyle.BAR, isCounter = true, decoder = byteAt(0),
    )

    /** Every parameter, ordered roughly by how often it is wanted. */
    val ALL: List<Pid> = listOf(
        ENGINE_RPM, VEHICLE_SPEED, COOLANT_TEMP, ENGINE_OIL_TEMP,
        INTAKE_MAP, CONTROL_MODULE_VOLTAGE, FUEL_LEVEL,
        EGT_1, EGT_2, EGT_3, EGT_4, DPF_TEMP,
        ENGINE_LOAD, ABSOLUTE_LOAD, THROTTLE_POSITION, RELATIVE_THROTTLE,
        COMMANDED_THROTTLE, ACCELERATOR_D, ACCELERATOR_E,
        FUEL_RAIL_PRESSURE, FUEL_RAIL_ABSOLUTE, FUEL_PRESSURE, FUEL_RATE,
        INJECTION_TIMING, TIMING_ADVANCE, SHORT_TRIM_1, LONG_TRIM_1,
        MAF_RATE, CHARGE_AIR_TEMP, INTAKE_AIR_TEMP, AMBIENT_AIR_TEMP,
        BAROMETRIC_PRESSURE, EXHAUST_PRESSURE, EGR_TEMP,
        COMMANDED_EGR, EGR_ERROR,
        DEMANDED_TORQUE, ACTUAL_TORQUE, REFERENCE_TORQUE,
        RUN_TIME, DISTANCE_WITH_MIL, DISTANCE_SINCE_CLEAR,
        TIME_WITH_MIL, TIME_SINCE_CLEAR, WARMUPS_SINCE_CLEAR,
    )

    private val BY_KEY: Map<String, Pid> = ALL.associateBy { it.key }

    fun byKey(key: String): Pid? = BY_KEY[key]

    /** Every parameter carried by a given OBD PID. Usually one; four for EGT. */
    fun byPidId(id: Int): List<Pid> = ALL.filter { it.id == id }

    /** A sensible starting dashboard for a diesel. */
    val DEFAULT_SELECTION: List<String> = listOf(
        ENGINE_RPM.key, VEHICLE_SPEED.key, COOLANT_TEMP.key,
        INTAKE_MAP.key, CONTROL_MODULE_VOLTAGE.key, ENGINE_LOAD.key,
    )

    /**
     * Decodes a supported-PID bitmask response (PIDs 0x00/0x20/0x40...).
     *
     * Four bytes, most significant bit first, describing the next 32 PIDs.
     * Asking the vehicle what it supports beats probing blindly: one request
     * instead of 32, and no spurious no-data results in the log.
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
