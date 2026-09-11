package com.anthonyrohde.f250scan.core.ford

import com.anthonyrohde.f250scan.core.adapter.CanBus

/**
 * Known module addresses, organised as a vehicle profile.
 *
 * ## How to read this file
 *
 * These entries are the well-established Ford diagnostic addresses. They are
 * used as *probe candidates and naming hints*, not as a declaration of what is
 * in your truck. The authoritative answer always comes from
 * [com.anthonyrohde.f250scan.core.session.ModuleDiscovery], which asks each
 * address whether anything is home and records what replied.
 *
 * That ordering matters for a 2022 Super Duty specifically. From roughly 2020
 * Ford routes diagnostics through a Gateway Module, so a module that physically
 * lives on MS-CAN may still answer on HS-CAN1 via the gateway. Rather than try
 * to model the gateway's routing table - which is undocumented and varies by
 * build - the app probes every selected bus and reports where each module
 * actually answered.
 */
object VehicleProfiles {

    /**
     * 2022 F-250 Super Duty (P558 platform, 2020-2022 facelift).
     *
     * Bus hints follow Ford's usual split: powertrain and chassis on HS-CAN1,
     * body and infotainment on MS-CAN, gateway and driver assistance on
     * HS-CAN2.
     */
    val SUPER_DUTY_2022: List<FordModule> = listOf(

        // ---------------------------------------------------------- powertrain
        FordModule(
            code = "PCM", name = "Powertrain Control Module",
            requestId = 0x7E0,
            expectedBuses = listOf(CanBus.HS_CAN1),
            category = ModuleCategory.POWERTRAIN,
            description = "Engine management. On the 6.7L Power Stroke this owns " +
                "fuel rail pressure, turbo vane position, EGR, glow plugs and the " +
                "DPF/SCR aftertreatment strategy.",
        ),
        FordModule(
            code = "TCM", name = "Transmission Control Module",
            requestId = 0x7E1,
            expectedBuses = listOf(CanBus.HS_CAN1),
            category = ModuleCategory.POWERTRAIN,
            description = "10R140 ten-speed automatic control, adaptive shift learning.",
        ),
        FordModule(
            code = "TCCM", name = "Transfer Case Control Module",
            requestId = 0x732,
            expectedBuses = listOf(CanBus.HS_CAN1),
            category = ModuleCategory.POWERTRAIN,
            description = "Electronic shift-on-the-fly four wheel drive.",
        ),
        FordModule(
            code = "SOBDM", name = "Secondary On-Board Diagnostic Module",
            requestId = 0x7E2,
            expectedBuses = listOf(CanBus.HS_CAN1),
            category = ModuleCategory.POWERTRAIN,
            description = "Battery monitoring and DC-DC conversion where fitted.",
        ),

        // ------------------------------------------------------------- chassis
        FordModule(
            code = "ABS", name = "Anti-Lock Brake / Stability Control Module",
            requestId = 0x760,
            expectedBuses = listOf(CanBus.HS_CAN1),
            category = ModuleCategory.CHASSIS,
            description = "ABS, traction control, roll stability, trailer sway control.",
            safetyCritical = true,
        ),
        FordModule(
            code = "PSCM", name = "Power Steering Control Module",
            requestId = 0x730,
            expectedBuses = listOf(CanBus.HS_CAN1),
            category = ModuleCategory.CHASSIS,
            description = "Electric power assist steering.",
            safetyCritical = true,
        ),
        FordModule(
            code = "TRM", name = "Trailer Module",
            requestId = 0x765,
            expectedBuses = listOf(CanBus.HS_CAN1, CanBus.MS_CAN),
            category = ModuleCategory.CHASSIS,
            description = "Integrated trailer brake controller and trailer lighting.",
        ),

        // ---------------------------------------------------------------- body
        FordModule(
            code = "BCM", name = "Body Control Module",
            requestId = 0x726,
            expectedBuses = listOf(CanBus.MS_CAN, CanBus.HS_CAN1),
            category = ModuleCategory.BODY,
            description = "Lighting, locks, wipers, horn, and most of the configuration " +
                "people want to change. The main As-Built target.",
        ),
        FordModule(
            code = "IPC", name = "Instrument Panel Cluster",
            requestId = 0x720,
            expectedBuses = listOf(CanBus.MS_CAN, CanBus.HS_CAN1),
            category = ModuleCategory.BODY,
            description = "Gauge cluster and message centre. Owns odometer and " +
                "displayed-unit configuration.",
        ),
        FordModule(
            code = "SCCM", name = "Steering Column Control Module",
            requestId = 0x724,
            expectedBuses = listOf(CanBus.MS_CAN, CanBus.HS_CAN1),
            category = ModuleCategory.BODY,
            description = "Stalk switches, steering wheel controls, clock spring.",
        ),
        FordModule(
            code = "DSM", name = "Driver Seat Module",
            requestId = 0x712,
            expectedBuses = listOf(CanBus.MS_CAN),
            category = ModuleCategory.BODY,
            description = "Memory seat, power adjust, seat heating and cooling.",
        ),
        FordModule(
            code = "HVAC", name = "Heating Ventilation and Air Conditioning Module",
            requestId = 0x733,
            expectedBuses = listOf(CanBus.MS_CAN),
            category = ModuleCategory.BODY,
            description = "Climate control, including dual zone and rear systems.",
        ),
        FordModule(
            code = "PAM", name = "Parking Aid Module",
            requestId = 0x736,
            expectedBuses = listOf(CanBus.MS_CAN, CanBus.HS_CAN2),
            category = ModuleCategory.DRIVER_ASSIST,
            description = "Ultrasonic parking sensors and park assist.",
        ),
        FordModule(
            code = "GSM", name = "Gear Shift Module",
            requestId = 0x732,
            expectedBuses = listOf(CanBus.HS_CAN1),
            category = ModuleCategory.BODY,
            description = "Column or rotary shifter position sensing.",
        ),

        // -------------------------------------------------------- infotainment
        FordModule(
            code = "APIM", name = "Accessory Protocol Interface Module (SYNC 4)",
            requestId = 0x7D0,
            expectedBuses = listOf(CanBus.MS_CAN, CanBus.HS_CAN2),
            category = ModuleCategory.INFOTAINMENT,
            description = "SYNC head unit. Large As-Built configuration surface: " +
                "feature enablement, navigation, connectivity.",
        ),
        FordModule(
            code = "ACM", name = "Audio Control Module",
            requestId = 0x727,
            expectedBuses = listOf(CanBus.MS_CAN),
            category = ModuleCategory.INFOTAINMENT,
            description = "Amplifier and audio routing.",
        ),
        FordModule(
            code = "FCIM", name = "Front Controls Interface Module",
            requestId = 0x740,
            expectedBuses = listOf(CanBus.MS_CAN),
            category = ModuleCategory.INFOTAINMENT,
            description = "Centre stack switch panel.",
        ),
        FordModule(
            code = "TCU", name = "Telematics Control Unit",
            requestId = 0x754,
            expectedBuses = listOf(CanBus.MS_CAN, CanBus.HS_CAN2),
            category = ModuleCategory.INFOTAINMENT,
            description = "FordPass modem and embedded connectivity.",
        ),

        // -------------------------------------------------------------- safety
        FordModule(
            code = "RCM", name = "Restraints Control Module",
            requestId = 0x737,
            expectedBuses = listOf(CanBus.HS_CAN1),
            category = ModuleCategory.SAFETY,
            description = "Airbags, seatbelt pretensioners, crash recording.",
            safetyCritical = true,
        ),
        FordModule(
            code = "OCS", name = "Occupant Classification System Module",
            requestId = 0x751,
            expectedBuses = listOf(CanBus.HS_CAN1),
            category = ModuleCategory.SAFETY,
            description = "Passenger seat occupancy sensing for airbag suppression.",
            safetyCritical = true,
        ),

        // ------------------------------------------------------- driver assist
        FordModule(
            code = "IPMA", name = "Image Processing Module A",
            requestId = 0x706,
            expectedBuses = listOf(CanBus.HS_CAN2, CanBus.HS_CAN1),
            category = ModuleCategory.DRIVER_ASSIST,
            description = "Forward camera: lane keeping, traffic sign recognition, " +
                "auto high beam.",
            safetyCritical = true,
        ),
        FordModule(
            code = "CCM", name = "Cruise Control Module",
            requestId = 0x764,
            expectedBuses = listOf(CanBus.HS_CAN2, CanBus.HS_CAN1),
            category = ModuleCategory.DRIVER_ASSIST,
            description = "Adaptive cruise radar where fitted.",
            safetyCritical = true,
        ),
        FordModule(
            code = "SODL", name = "Side Obstacle Detection - Left",
            requestId = 0x7A4,
            expectedBuses = listOf(CanBus.HS_CAN2),
            category = ModuleCategory.DRIVER_ASSIST,
            description = "Blind spot monitoring, left rear radar.",
        ),
        FordModule(
            code = "SODR", name = "Side Obstacle Detection - Right",
            requestId = 0x7A5,
            expectedBuses = listOf(CanBus.HS_CAN2),
            category = ModuleCategory.DRIVER_ASSIST,
            description = "Blind spot monitoring, right rear radar.",
        ),

        // ------------------------------------------------------------- network
        FordModule(
            code = "GWM", name = "Gateway Module",
            requestId = 0x716,
            expectedBuses = listOf(CanBus.HS_CAN1, CanBus.HS_CAN2),
            category = ModuleCategory.NETWORK,
            description = "Routes diagnostics between buses. On 2020+ Fords this is " +
                "often why a body module answers on HS-CAN1.",
        ),
    )

    /** Lookup by request address, for naming whatever discovery turns up. */
    private val BY_REQUEST_ID: Map<Int, FordModule> =
        SUPER_DUTY_2022.associateBy { it.requestId }

    fun moduleForRequestId(requestId: Int): FordModule? = BY_REQUEST_ID[requestId]

    fun moduleByCode(code: String): FordModule? =
        SUPER_DUTY_2022.firstOrNull { it.code.equals(code, ignoreCase = true) }

    /**
     * Addresses a discovery sweep should try.
     *
     * Ford puts 11-bit diagnostic addresses in 0x700-0x7FF, with the powertrain
     * block at 0x7E0-0x7E7. We sweep the whole range rather than only known
     * addresses, because finding an unnamed module that answers is far more
     * useful than assuming this file is complete - the truck is the authority,
     * not the table above.
     */
    fun discoveryAddresses(): List<Int> = (0x700..0x7FF).toList()

    /** Functional (broadcast) address for legacy OBD-II requests. */
    const val OBD_FUNCTIONAL_REQUEST = 0x7DF

    /**
     * Standard OBD-II responders answer in 0x7E8-0x7EF. Used when talking mode
     * 01/03/09 to "the vehicle" rather than to a named module.
     */
    const val OBD_RESPONSE_RANGE_START = 0x7E8
    const val OBD_RESPONSE_RANGE_END = 0x7EF
}
