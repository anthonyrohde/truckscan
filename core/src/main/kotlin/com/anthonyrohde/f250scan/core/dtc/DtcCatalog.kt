package com.anthonyrohde.f250scan.core.dtc

/**
 * Descriptions for trouble codes and failure type bytes.
 *
 * ## Scope, honestly stated
 *
 * The generic SAE J2012 codes below are standardised and reliable. Ford's own
 * manufacturer-specific range (P1xxx, and most B/C/U codes above the generic
 * block) runs to many thousands of entries, is not published, and is exactly
 * the body of knowledge that makes a commercial tool worth paying for. This
 * catalog therefore covers:
 *
 *  - the generic powertrain codes, with emphasis on the ones a 6.7L Power
 *    Stroke actually sets (fuel rail, turbo, EGR, DPF, SCR),
 *  - the network U-codes, which are generic and very useful when a module
 *    drops off the bus,
 *  - the J2012-DA failure type bytes, which are standardised and turn a bare
 *    code into an actionable one.
 *
 * Anything absent returns an explicit "no description" rather than a guess.
 * Showing a raw code is honest; inventing a description is not, and would
 * send someone chasing the wrong part.
 */
object DtcCatalog {

    /** Generic SAE J2012 powertrain and network codes. */
    private val CODES: Map<String, String> = mapOf(
        // --- Fuel and air metering
        "P0087" to "Fuel rail/system pressure too low",
        "P0088" to "Fuel rail/system pressure too high",
        "P0091" to "Fuel pressure regulator 1 control circuit low",
        "P0092" to "Fuel pressure regulator 1 control circuit high",
        "P0093" to "Fuel system large leak detected",
        "P0094" to "Fuel system small leak detected",
        "P0101" to "Mass air flow sensor circuit range/performance",
        "P0102" to "Mass air flow sensor circuit low input",
        "P0103" to "Mass air flow sensor circuit high input",
        "P0107" to "Manifold absolute pressure sensor circuit low",
        "P0108" to "Manifold absolute pressure sensor circuit high",
        "P0112" to "Intake air temperature sensor 1 circuit low",
        "P0113" to "Intake air temperature sensor 1 circuit high",
        "P0116" to "Engine coolant temperature sensor range/performance",
        "P0117" to "Engine coolant temperature sensor circuit low",
        "P0118" to "Engine coolant temperature sensor circuit high",
        "P0128" to "Coolant thermostat below regulating temperature",
        "P0180" to "Fuel temperature sensor A circuit malfunction",
        "P0190" to "Fuel rail pressure sensor circuit malfunction",
        "P0191" to "Fuel rail pressure sensor range/performance",
        "P0192" to "Fuel rail pressure sensor circuit low",
        "P0193" to "Fuel rail pressure sensor circuit high",

        // --- Injectors
        "P0201" to "Injector circuit open - cylinder 1",
        "P0202" to "Injector circuit open - cylinder 2",
        "P0203" to "Injector circuit open - cylinder 3",
        "P0204" to "Injector circuit open - cylinder 4",
        "P0205" to "Injector circuit open - cylinder 5",
        "P0206" to "Injector circuit open - cylinder 6",
        "P0207" to "Injector circuit open - cylinder 7",
        "P0208" to "Injector circuit open - cylinder 8",
        "P0263" to "Cylinder 1 contribution/balance fault",
        "P0266" to "Cylinder 2 contribution/balance fault",
        "P0269" to "Cylinder 3 contribution/balance fault",
        "P0272" to "Cylinder 4 contribution/balance fault",
        "P0275" to "Cylinder 5 contribution/balance fault",
        "P0278" to "Cylinder 6 contribution/balance fault",
        "P0281" to "Cylinder 7 contribution/balance fault",
        "P0284" to "Cylinder 8 contribution/balance fault",

        // --- Boost and turbo
        "P0234" to "Turbocharger overboost condition",
        "P0235" to "Turbocharger boost sensor A circuit malfunction",
        "P0236" to "Turbocharger boost sensor A range/performance",
        "P0237" to "Turbocharger boost sensor A circuit low",
        "P0238" to "Turbocharger boost sensor A circuit high",
        "P0299" to "Turbocharger/supercharger underboost",
        "P0045" to "Turbocharger boost control solenoid circuit open",
        "P0046" to "Turbocharger boost control solenoid range/performance",
        "P2262" to "Turbocharger boost pressure not detected - mechanical",
        "P2263" to "Turbocharger/supercharger boost system performance",

        // --- EGR
        "P0401" to "Exhaust gas recirculation flow insufficient",
        "P0402" to "Exhaust gas recirculation flow excessive",
        "P0403" to "Exhaust gas recirculation control circuit malfunction",
        "P0404" to "Exhaust gas recirculation control circuit range/performance",
        "P0405" to "Exhaust gas recirculation sensor A circuit low",
        "P0406" to "Exhaust gas recirculation sensor A circuit high",
        "P0409" to "Exhaust gas recirculation sensor A circuit malfunction",
        "P046C" to "Exhaust gas recirculation position sensor circuit range/performance",
        "P2457" to "Exhaust gas recirculation cooler efficiency below threshold",

        // --- Glow plugs and starting aids
        "P0670" to "Glow plug module control circuit malfunction",
        "P0671" to "Glow plug cylinder 1 circuit malfunction",
        "P0672" to "Glow plug cylinder 2 circuit malfunction",
        "P0673" to "Glow plug cylinder 3 circuit malfunction",
        "P0674" to "Glow plug cylinder 4 circuit malfunction",
        "P0675" to "Glow plug cylinder 5 circuit malfunction",
        "P0676" to "Glow plug cylinder 6 circuit malfunction",
        "P0677" to "Glow plug cylinder 7 circuit malfunction",
        "P0678" to "Glow plug cylinder 8 circuit malfunction",

        // --- Exhaust aftertreatment: DPF
        "P2002" to "Diesel particulate filter efficiency below threshold - bank 1",
        "P2003" to "Diesel particulate filter efficiency below threshold - bank 2",
        "P2452" to "Diesel particulate filter pressure sensor A circuit",
        "P2453" to "Diesel particulate filter pressure sensor A range/performance",
        "P2454" to "Diesel particulate filter pressure sensor A circuit low",
        "P2455" to "Diesel particulate filter pressure sensor A circuit high",
        "P242F" to "Diesel particulate filter restriction - ash accumulation",
        "P2458" to "Diesel particulate filter regeneration duration",
        "P2459" to "Diesel particulate filter regeneration frequency",
        "P246B" to "Diesel particulate filter restriction - soot accumulation",
        "P2463" to "Diesel particulate filter restriction - soot accumulation",

        // --- Exhaust gas temperature
        "P0544" to "Exhaust gas temperature sensor circuit bank 1 sensor 1",
        "P0545" to "Exhaust gas temperature sensor circuit low bank 1 sensor 1",
        "P0546" to "Exhaust gas temperature sensor circuit high bank 1 sensor 1",
        "P2032" to "Exhaust gas temperature sensor circuit low bank 1 sensor 2",
        "P2033" to "Exhaust gas temperature sensor circuit high bank 1 sensor 2",
        "P246C" to "Diesel particulate filter restriction - forced limited power",

        // --- Exhaust aftertreatment: SCR / DEF
        "P204F" to "Reductant system performance",
        "P2047" to "Reductant injector circuit open - bank 1 unit 1",
        "P2048" to "Reductant injector circuit low - bank 1 unit 1",
        "P2049" to "Reductant injector circuit high - bank 1 unit 1",
        "P207F" to "Reductant quality performance",
        "P203F" to "Reductant level too low",
        "P20EE" to "SCR NOx catalyst efficiency below threshold - bank 1",
        "P20E8" to "Reductant pressure too low",
        "P20E9" to "Reductant pressure too high",
        "P2BAC" to "NOx exceedence - insufficient reductant quality",
        "P229E" to "NOx sensor circuit bank 1 sensor 2",
        "P2200" to "NOx sensor circuit bank 1 sensor 1",
        "P2201" to "NOx sensor circuit range/performance bank 1 sensor 1",

        // --- Crankcase, oil, cooling
        "P0521" to "Engine oil pressure sensor range/performance",
        "P0522" to "Engine oil pressure sensor low voltage",
        "P0523" to "Engine oil pressure sensor high voltage",
        "P0196" to "Engine oil temperature sensor range/performance",
        "P0597" to "Thermostat heater control circuit open",
        "P0598" to "Thermostat heater control circuit low",
        "P0599" to "Thermostat heater control circuit high",

        // --- Vehicle and electrical
        "P0500" to "Vehicle speed sensor A malfunction",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0602" to "Control module programming error",
        "P0603" to "Internal control module keep-alive memory error",
        "P0604" to "Internal control module random access memory error",
        "P0605" to "Internal control module read-only memory error",
        "P0606" to "Control module processor fault",
        "P060A" to "Internal control module monitoring processor performance",
        "P061B" to "Internal control module torque calculation performance",
        "P0610" to "Control module vehicle options error",
        "P1000" to "OBD system readiness test not complete",
        "P1001" to "Key on engine running self test not able to complete",

        // --- Transmission
        "P0700" to "Transmission control system malfunction",
        "P0701" to "Transmission control system range/performance",
        "P0715" to "Input/turbine speed sensor circuit malfunction",
        "P0720" to "Output speed sensor circuit malfunction",
        "P0730" to "Incorrect gear ratio",
        "P0741" to "Torque converter clutch circuit performance or stuck off",
        "P0868" to "Transmission fluid pressure low",

        // --- Network / lost communication (generic and very useful)
        "U0001" to "High speed CAN communication bus fault",
        "U0010" to "Medium speed CAN communication bus fault",
        "U0073" to "Control module communication bus A off",
        "U0074" to "Control module communication bus B off",
        "U0100" to "Lost communication with engine control module",
        "U0101" to "Lost communication with transmission control module",
        "U0102" to "Lost communication with transfer case control module",
        "U0103" to "Lost communication with gear shift module",
        "U0121" to "Lost communication with anti-lock brake system module",
        "U0122" to "Lost communication with vehicle dynamics control module",
        "U0126" to "Lost communication with steering angle sensor module",
        "U0131" to "Lost communication with power steering control module",
        "U0140" to "Lost communication with body control module",
        "U0146" to "Lost communication with gateway module A",
        "U0151" to "Lost communication with restraints control module",
        "U0155" to "Lost communication with instrument panel cluster",
        "U0164" to "Lost communication with HVAC control module",
        "U0198" to "Lost communication with telematics control module",
        "U0199" to "Lost communication with door control module A",
        "U0212" to "Lost communication with steering column control module",
        "U0253" to "Lost communication with audio front control module",
        "U0256" to "Lost communication with front controls interface module",
        "U0300" to "Internal control module software incompatibility",
        "U0401" to "Invalid data received from engine control module",
        "U0402" to "Invalid data received from transmission control module",
        "U0415" to "Invalid data received from anti-lock brake system module",
        "U0422" to "Invalid data received from body control module",
        "U3000" to "Control module internal fault",
        "U3003" to "Battery voltage out of range",
    )

    /**
     * SAE J2012-DA failure type bytes.
     *
     * These are standardised across manufacturers and carry most of the
     * diagnostic value in a Ford code: `P0299:1C` (voltage out of range) points
     * somewhere very different from `P0299:23` (signal stuck low).
     */
    private val FAILURE_TYPES: Map<Int, String> = mapOf(
        0x00 to "no sub type information",
        0x01 to "general electrical failure",
        0x02 to "general signal failure",
        0x04 to "system internal failure",
        0x08 to "component failure",
        0x11 to "circuit short to ground",
        0x12 to "circuit short to battery",
        0x13 to "circuit open",
        0x14 to "circuit short to ground or open",
        0x15 to "circuit short to battery or open",
        0x16 to "circuit voltage below threshold",
        0x17 to "circuit voltage above threshold",
        0x18 to "circuit current below threshold",
        0x19 to "circuit current above threshold",
        0x1A to "circuit resistance below threshold",
        0x1B to "circuit resistance above threshold",
        0x1C to "circuit voltage out of range",
        0x1D to "circuit current out of range",
        0x1E to "circuit not plausible",
        0x1F to "circuit intermittent or erratic",
        0x21 to "signal amplitude below minimum",
        0x22 to "signal amplitude above maximum",
        0x23 to "signal stuck low",
        0x24 to "signal stuck high",
        0x25 to "signal shape or waveform failure",
        0x26 to "signal rate of change below threshold",
        0x27 to "signal rate of change above threshold",
        0x28 to "signal bias level out of range",
        0x29 to "signal invalid",
        0x2A to "signal erratic",
        0x2B to "signal cross coupled",
        0x2F to "signal erratic or intermittent",
        0x31 to "no signal",
        0x36 to "signal frequency too high",
        0x37 to "signal frequency too low",
        0x38 to "signal frequency incorrect",
        0x3A to "signal invalid",
        0x41 to "general checksum failure",
        0x42 to "general memory failure",
        0x43 to "special memory failure",
        0x44 to "data memory failure",
        0x45 to "program memory failure",
        0x46 to "calibration or parameter memory failure",
        0x47 to "watchdog or safety processor failure",
        0x48 to "supervision software failure",
        0x49 to "internal electronic failure",
        0x4A to "incorrect component installed",
        0x4B to "over temperature",
        0x51 to "not programmed",
        0x52 to "deactivated",
        0x53 to "not configured or not installed",
        0x54 to "missing calibration",
        0x55 to "not programmed or blank",
        0x56 to "invalid or missing configuration",
        0x61 to "signal calculation failure",
        0x62 to "signal compare failure",
        0x63 to "actuator stuck",
        0x64 to "signal plausibility failure",
        0x65 to "signal had component failure",
        0x68 to "event information",
        0x71 to "actuator stuck",
        0x72 to "actuator stuck open",
        0x73 to "actuator stuck closed",
        0x74 to "actuator slipping",
        0x75 to "emergency position not reachable",
        0x76 to "incorrect mounting position",
        0x77 to "commanded position not reachable",
        0x78 to "alignment or adjustment incorrect",
        0x79 to "mechanical linkage failure",
        0x7A to "mechanical failure",
        0x7B to "valve stuck",
        0x81 to "invalid serial data received",
        0x82 to "alive or sequence counter incorrect",
        0x83 to "signal protection value incorrect",
        0x84 to "signal above allowable range",
        0x85 to "signal below allowable range",
        0x86 to "signal invalid",
        0x87 to "missing message",
        0x88 to "bus off",
        0x92 to "performance or incorrect operation",
        0x93 to "no operation",
        0x94 to "unexpected operation",
        0x95 to "incorrect assembly",
        0x96 to "component internal failure",
        0x97 to "component or system operation obstructed or blocked",
        0x98 to "component or system over temperature",
        0x99 to "component or system operating conditions",
    )

    const val UNKNOWN_CODE = "No description available - not in the generic SAE catalog. " +
        "This is most likely a Ford-specific code; check an Oasis or Ford service lookup."

    /** Description for a base code, with the failure type appended when known. */
    fun describe(code: String, failureTypeByte: Int? = null): String {
        val base = CODES[code.uppercase()]
        val ftb = failureTypeByte
            ?.takeIf { it != 0 }
            ?.let { FAILURE_TYPES[it] }

        return when {
            base != null && ftb != null -> "$base - $ftb"
            base != null -> base
            ftb != null -> "$UNKNOWN_CODE Failure type: $ftb."
            else -> UNKNOWN_CODE
        }
    }

    fun describeFailureType(byte: Int): String? = FAILURE_TYPES[byte]

    /** True when we have a real description, so the UI can style it differently. */
    fun isKnown(code: String): Boolean = CODES.containsKey(code.uppercase())

    val size: Int get() = CODES.size
}
