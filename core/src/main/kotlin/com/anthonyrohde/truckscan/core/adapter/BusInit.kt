package com.anthonyrohde.truckscan.core.adapter

/**
 * Adapter command sequences that put a bus into a usable state.
 *
 * ## Why this file has candidates rather than constants
 *
 * Selecting 500 kbps HS-CAN is completely standard (`ATSP6`) and works on
 * everything. Selecting 125 kbps MS-CAN is not: it needs the ELM327
 * user-defined "protocol B", whose options byte has been documented
 * inconsistently across ELM327 datasheet revisions and is outright wrong on
 * many clones.
 *
 * Rather than hard-code one magic number and hope, we keep the plausible
 * sequences here in preference order and let [ElmAdapter.selectBus] probe them,
 * keeping the first that yields real bus traffic. On STN hardware the native
 * `STP`/`STPBR` path is tried first and is expected to succeed outright, which
 * is the main reason to recommend that hardware.
 *
 * If you bench-verify the correct options byte for your specific adapter,
 * pin it by moving that sequence to the head of the list.
 */
object BusInit {

    data class Sequence(val label: String, val commands: List<String>)

    /** Commands applied once per connection, before any bus is selected. */
    val COMMON_SETUP: List<String> = listOf(
        "ATE0",   // echo off - we do not want our own commands back
        "ATL0",   // no linefeeds, we split on CR ourselves
        "ATS0",   // no spaces in responses; smaller frames, faster reads
        "ATH1",   // headers on - we need the CAN ID of every frame
        "ATAL",   // allow long (>7 byte) messages
        "ATCAF0", // CAN auto-formatting OFF: we do our own ISO-TP
        "ATCFC0", // adapter must not auto-send flow control; we send it
        "ATAT1",  // adaptive timing on, but bounded by ATST below
    )

    /**
     * Native ST sequences. `STPBR` sets the bitrate of the current protocol in
     * plain bits per second, which sidesteps the ELM divisor arithmetic
     * entirely. `STP 33` selects ISO 15765 11-bit; `STP 34` selects 29-bit.
     */
    private fun stnSequence(bus: CanBus): Sequence {
        val protocol = if (bus.extendedAddressing) "STP 34" else "STP 33"
        return Sequence(
            label = "STN native (${bus.bitrateBps / 1000}kbps)",
            commands = listOf(protocol, "STPBR ${bus.bitrateBps}", "STPBRR"),
        )
    }

    /** ELM327 fallbacks, including the user-protocol-B variants for MS-CAN. */
    private fun elmSequences(bus: CanBus): List<Sequence> {
        if (bus.isHighSpeed) {
            val sp = if (bus.extendedAddressing) "ATSP7" else "ATSP6"
            return listOf(Sequence("ELM standard 500kbps", listOf(sp)))
        }

        // 125 kbps: data rate = 500 / divisor, so divisor = 4.
        val divisor = (500_000 / bus.bitrateBps).coerceAtLeast(1)
        val div = divisor.toString(16).uppercase().padStart(2, '0')

        // The options byte is the uncertain value. These are the variants seen
        // in the wild for 11-bit, variable-DLC, 125 kbps operation.
        val optionCandidates = listOf("C0", "40", "01", "11", "80")

        return optionCandidates.map { opts ->
            Sequence(
                label = "ELM protocol B (options $opts, divisor $div)",
                commands = listOf("ATPB $opts $div", "ATSPB"),
            )
        }
    }

    /** Full preference-ordered list of sequences to try for [bus]. */
    fun candidatesFor(bus: CanBus, identity: AdapterIdentity): List<Sequence> = buildList {
        if (identity.isStn) add(stnSequence(bus))
        addAll(elmSequences(bus))
    }
}
