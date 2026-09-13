package com.anthonyrohde.truckscan.core.adapter

/**
 * The CAN buses reachable through a J1962 (OBD-II) connector on a Ford.
 *
 * ## Which of these an adapter can actually reach
 *
 * An OBD adapter has a fixed set of CAN transceivers, each soldered to fixed
 * connector pins. Selecting a "bus" is really selecting a transceiver, and no
 * command can attach a transceiver to a pin it is not wired to.
 *
 * The STN chipset in the OBDLink family has exactly three, and its protocol
 * table names them. Read off the adapter itself with `STP xx` / `STPRS`:
 *
 * ```
 * 31/32  HS CAN (ISO 11898, 500K/11B, 500K/29B)   pins 6/14
 * 33/34  HS CAN (ISO 15765, 500K/11B, 500K/29B)   pins 6/14
 * 35/36  HS CAN (ISO 15765, 250K/11B, 250K/29B)   pins 6/14
 * 51/52  MS CAN (ISO 11898, 125K/11B, 125K/29B)   pins 3/11
 * 53/54  MS CAN (ISO 15765, 125K/11B, 125K/29B)   pins 3/11
 * 61/64  SW CAN (ISO 11898/15765, 33K)            pin 1
 * ```
 *
 * There is no entry for pins 12/13 or 9, because no transceiver is wired to
 * them. [HS_CAN2] and [HS_CAN3] therefore carry a null [stnProtocol]: they are
 * real buses in the truck and unreachable through this connector, and saying so
 * is better than silently scanning HS-CAN1 a second time under their name.
 *
 * Pin assignments are the documented Ford usage. Which buses are populated
 * varies by model and year, so module discovery treats these as candidates to
 * probe, never as fact.
 */
enum class CanBus(
    val displayName: String,
    val canHighPin: Int,
    val canLowPin: Int,
    val bitrateBps: Int,
    val extendedAddressing: Boolean,
    /**
     * STN protocol number for 11-bit ISO 15765 on this bus, or null when no
     * transceiver reaches it. The 29-bit variant is always this plus one -
     * that is how the whole table is laid out, not an assumption.
     *
     * The bitrate is part of the protocol, which is why no separate `STPBR` is
     * sent: the one time this project set a rate separately it set 125 kbps on
     * the *high speed* protocol, and spent a session wondering why MS-CAN
     * returned CAN ERROR.
     */
    val stnProtocol: Int?,
) {
    /** Powertrain/chassis bus. Always present. Standard OBD-II lives here. */
    HS_CAN1(
        "HS-CAN1", canHighPin = 6, canLowPin = 14, bitrateBps = 500_000,
        extendedAddressing = false, stnProtocol = 0x33,
    ),

    /** Body/infotainment bus on most Fords: BCM, IPC, APIM, ACM, DSM. */
    MS_CAN(
        "MS-CAN", canHighPin = 3, canLowPin = 11, bitrateBps = 125_000,
        extendedAddressing = false, stnProtocol = 0x53,
    ),

    /** Second high-speed bus used from roughly 2017 onward: gateway, ADAS. */
    HS_CAN2(
        "HS-CAN2", canHighPin = 12, canLowPin = 13, bitrateBps = 500_000,
        extendedAddressing = false, stnProtocol = null,
    ),

    /** Third high-speed bus, present on some 2020+ platforms. */
    HS_CAN3(
        "HS-CAN3", canHighPin = 1, canLowPin = 9, bitrateBps = 500_000,
        extendedAddressing = false, stnProtocol = null,
    ),
    ;

    val isHighSpeed: Boolean get() = bitrateBps >= 500_000

    /** The protocol number to send, accounting for 11- vs 29-bit addressing. */
    val stnProtocolNumber: Int?
        get() = stnProtocol?.let { if (extendedAddressing) it + 1 else it }

    /**
     * True when some adapter transceiver is wired to this bus's pins.
     *
     * False does not mean the bus is absent from the truck. It means nothing
     * plugged into the OBD connector can see it, so the honest thing is to say
     * so rather than scan a different bus and label the results with this one.
     */
    val hasAdapterPath: Boolean get() = stnProtocol != null

    override fun toString(): String = "$displayName (pins $canHighPin/$canLowPin @ ${bitrateBps / 1000}kbps)"
}
