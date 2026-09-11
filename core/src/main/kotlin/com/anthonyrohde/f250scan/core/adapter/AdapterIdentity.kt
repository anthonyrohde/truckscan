package com.anthonyrohde.f250scan.core.adapter

/**
 * What we learned about the connected adapter during initialisation.
 *
 * The distinction that matters is [isStn]: STN-chipset adapters (OBDLink EX,
 * MX+, CX and the SX) implement the ST command set, which can retune the CAN
 * controller and re-route the connector pins. That is the capability the whole
 * multi-bus feature set depends on. Everything else is an ELM327 or a clone of
 * one, and is limited to the powertrain bus.
 */
data class AdapterIdentity(
    /** Raw response to `ATI`, e.g. "ELM327 v1.5" or "STN2120 v5.6.7". */
    val elmIdentifier: String,
    /** Raw response to `STI` on STN hardware, empty elsewhere. */
    val stnIdentifier: String = "",
    /** Raw response to `@1`, the adapter's device description. */
    val deviceDescription: String = "",
) {
    val isStn: Boolean get() = stnIdentifier.isNotBlank()

    /**
     * True when we believe the adapter can reach buses other than HS-CAN1.
     *
     * We only claim this for STN hardware. ELM327 variants with a physical
     * HS/MS switch can also do it, but nothing in the protocol tells us the
     * switch exists or which way it is thrown, so we do not pretend to know.
     */
    val supportsMultiBus: Boolean get() = isStn

    val model: String
        get() = when {
            stnIdentifier.isNotBlank() -> stnIdentifier.trim()
            elmIdentifier.isNotBlank() -> elmIdentifier.trim()
            else -> "Unknown adapter"
        }

    /** Shown verbatim in the UI when the user picks a bus we cannot reach. */
    fun multiBusLimitationMessage(bus: CanBus): String =
        "$model is an ELM327-class adapter wired to pins 6/14 only, so it cannot " +
            "reach ${bus.displayName} (pins ${bus.canHighPin}/${bus.canLowPin}). " +
            "Reaching the body and infotainment modules needs an STN-based adapter " +
            "such as an OBDLink EX or MX+."
}
