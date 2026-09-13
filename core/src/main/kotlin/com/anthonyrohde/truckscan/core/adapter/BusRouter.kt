package com.anthonyrohde.truckscan.core.adapter

/**
 * Keeps the adapter on the right bus for the module being addressed.
 *
 * ## Why this exists
 *
 * A module is only reachable while the adapter is configured for the bus it
 * sits on. Discovery selects each bus as it sweeps it, but everything
 * afterwards - reading faults, reading configuration, running a routine -
 * addresses modules in whatever order the caller likes, freely crossing buses.
 * Without routing, those operations run on whichever bus happened to be
 * selected last and simply time out against anything else.
 *
 * So every module-facing operation calls [ensureBus] first. The router makes
 * that cheap: a request for the bus already selected is a no-op, and
 * [ElmAdapter] remembers the initialisation sequence proven to work for each
 * bus, so returning to one skips the candidate probing it needed the first time.
 */
class BusRouter(
    private val adapter: ElmAdapter,
    private val logger: ((String) -> Unit)? = null,
) {
    /**
     * Set when the adapter can hold several buses at once and route by CAN ID
     * on its own, making a switch unnecessary rather than merely cheap.
     *
     * The OBDLink EX advertises exactly this - "simultaneous access to HS-CAN
     * and MS-CAN", as distinct from a toggle-switch adapter that is physically
     * on one bus at a time. It is left **off by default** because the ST
     * command set needed to put the adapter into that mode is not something
     * this project has been able to verify, and assuming it would silently
     * address modules on a bus that was never brought up.
     *
     * Turning it on is a one-line change once the behaviour is confirmed on
     * real hardware; until then sequential switching is correct everywhere and
     * merely leaves some speed on the table.
     */
    var simultaneousBusAccess: Boolean = false

    private val broughtUp = linkedSetOf<CanBus>()

    var switchCount: Int = 0
        private set

    /** Requests satisfied without touching the adapter. */
    var skippedCount: Int = 0
        private set

    val busesBroughtUp: Set<CanBus> get() = broughtUp

    /**
     * Makes [bus] addressable, switching only if necessary.
     *
     * @return true when the adapter was actually reconfigured.
     */
    suspend fun ensureBus(bus: CanBus): Boolean {
        if (adapter.selectedBus == bus) {
            skippedCount++
            return false
        }

        // An adapter that holds every bus it has brought up needs no switch.
        if (simultaneousBusAccess && bus in broughtUp) {
            skippedCount++
            return false
        }

        // An adapter wired only to pins 6/14 cannot reach anything else, and
        // failing here with the hardware explanation beats a bare timeout.
        if (bus != CanBus.HS_CAN1 && !adapter.adapterIdentity.supportsMultiBus) {
            throw AdapterException(adapter.adapterIdentity.multiBusLimitationMessage(bus))
        }

        logger?.invoke("Switching to ${bus.displayName}")
        // One exchange, same as a request: selectBus tries several candidate
        // sequences and, on a fresh bus, probes for traffic - several separate
        // commands that must not be interleaved with anything else touching
        // this adapter, or the switch itself is what gets corrupted.
        adapter.exclusive { adapter.selectBus(bus) }
        broughtUp += bus
        switchCount++
        return true
    }

    /** Forgets what has been brought up. Call on disconnect. */
    fun reset() {
        broughtUp.clear()
        switchCount = 0
        skippedCount = 0
    }
}
