package com.anthonyrohde.f250scan.core.adapter

/**
 * The CAN buses reachable through a J1962 (OBD-II) connector on a Ford.
 *
 * A plain ELM327 clone is wired only to pins 6/14 and therefore can only ever
 * see [HS_CAN1]. Reaching the body and infotainment modules needs an adapter
 * whose pins can be re-routed - the STN-based OBDLink EX/MX+ family, or one of
 * the ELM327 variants with a physical HS/MS switch. This enum exists so the UI
 * can say plainly "your adapter cannot see this bus" instead of just failing to
 * find half the truck's modules.
 *
 * Pin assignments below are the documented Ford usage. Which buses are actually
 * populated varies by model and year, so module discovery treats these as
 * candidates to probe, never as fact.
 */
enum class CanBus(
    val displayName: String,
    val canHighPin: Int,
    val canLowPin: Int,
    val bitrateBps: Int,
    val extendedAddressing: Boolean,
) {
    /** Powertrain/chassis bus. Always present. Standard OBD-II lives here. */
    HS_CAN1("HS-CAN1", canHighPin = 6, canLowPin = 14, bitrateBps = 500_000, extendedAddressing = false),

    /** Body/infotainment bus on most Fords: BCM, IPC, APIM, ACM, DSM. */
    MS_CAN("MS-CAN", canHighPin = 3, canLowPin = 11, bitrateBps = 125_000, extendedAddressing = false),

    /** Second high-speed bus used from roughly 2017 onward: gateway, ADAS. */
    HS_CAN2("HS-CAN2", canHighPin = 12, canLowPin = 13, bitrateBps = 500_000, extendedAddressing = false),

    /** Third high-speed bus, present on some 2020+ platforms. */
    HS_CAN3("HS-CAN3", canHighPin = 1, canLowPin = 9, bitrateBps = 500_000, extendedAddressing = false),
    ;

    val isHighSpeed: Boolean get() = bitrateBps >= 500_000

    override fun toString(): String = "$displayName (pins $canHighPin/$canLowPin @ ${bitrateBps / 1000}kbps)"
}
