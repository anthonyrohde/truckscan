package com.anthonyrohde.truckscan.core.adapter

/**
 * Adapter command sequences that put a bus into a usable state.
 *
 * ## What this file got wrong, and how it was found
 *
 * It used to send `STP 33` for every bus and then set the rate separately with
 * `STPBR <bitrate>`. Sweeping the adapter's own protocol table with `STP xx`
 * followed by `STPRS` showed what `STP 33` actually is:
 *
 * ```
 * 33  HS CAN (ISO 15765, 500K/11B)
 * 53  MS CAN (ISO 15765, 125K/11B)
 * ```
 *
 * So `STP 33` + `STPBR 125000` selected the **high speed** transceiver on pins
 * 6/14 and told it to run at 125 kbps - a 125 kbps node on a 500 kbps bus,
 * which cannot acknowledge anything. That is why every MS-CAN attempt on the
 * truck returned `CAN ERROR` and never once returned `NO DATA`. The five
 * ELM327 protocol B candidates failed the same way for the same reason: `ATPB`
 * sets a CAN controller's bitrate and options, and says nothing about which
 * pins the transceiver is attached to.
 *
 * The fix is to take the protocol number from [CanBus.stnProtocolNumber] and
 * send nothing else. The bitrate is part of the protocol, and setting it
 * separately is how it came to contradict the protocol in the first place.
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
     * The native ST sequence: one command, naming the transceiver and the rate
     * together.
     *
     * Null when no transceiver is wired to this bus's pins, which is the case
     * for HS-CAN2 and HS-CAN3.
     */
    private fun stnSequence(bus: CanBus): Sequence? {
        val protocol = bus.stnProtocolNumber ?: return null
        return Sequence(
            label = "STN protocol %02X".format(protocol),
            // One command. Four ways of reaching the powertrain bus were tried
            // on a running truck, each from a clean reset - ATSP6, STP 33 alone,
            // STP 33 then STPO, STP 33 then STPBR 500000 - and all four got the
            // PCM to answer. STP alone opens a protocol, so STPBR is redundant
            // and STPO is redundant. The NO DATA that suggested otherwise came
            // from an earlier run where the truck had gone to sleep.
            commands = listOf("STP %02X".format(protocol)),
        )
    }

    /**
     * ELM327 fallbacks.
     *
     * The ELM327 command set has no concept of a second transceiver: `ATSP6`
     * and protocol B both drive pins 6/14. The protocol B candidates are kept
     * only for ELM327 variants with a *physical* HS/MS switch, where the pins
     * have already been changed by hand and the chip just needs the right
     * bitrate. On an STN adapter they were measured to do nothing useful, so
     * they are not offered for MS-CAN there.
     */
    private fun elmSequences(bus: CanBus, identity: AdapterIdentity): List<Sequence> {
        if (!bus.hasAdapterPath) return emptyList()

        if (bus.isHighSpeed) {
            val sp = if (bus.extendedAddressing) "ATSP7" else "ATSP6"
            return listOf(Sequence("ELM standard 500kbps", listOf(sp)))
        }

        if (identity.isStn) return emptyList()

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

    /**
     * Full preference-ordered list of sequences to try for [bus].
     *
     * Empty means there is no way to reach the bus with this adapter, which
     * callers must report rather than treat as a failed attempt.
     */
    fun candidatesFor(bus: CanBus, identity: AdapterIdentity): List<Sequence> = buildList {
        if (identity.isStn) stnSequence(bus)?.let { add(it) }
        addAll(elmSequences(bus, identity))
    }
}
