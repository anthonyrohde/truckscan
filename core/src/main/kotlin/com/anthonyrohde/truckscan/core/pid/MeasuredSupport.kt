package com.anthonyrohde.truckscan.core.pid

import com.anthonyrohde.truckscan.core.util.Hex

/**
 * What one real PCM answered when asked what it supports.
 *
 * Every gauge this app offers has until now come from a catalogue assembled
 * from documentation, and the only thing the vehicle ever said about it was
 * silence for the ones it does not have. These are the raw supported-PID
 * bitmaps read from a 2022 F-250 Super Duty (VIN beginning 1FT8W2BT8N) with the
 * engine running, recorded verbatim so the list below is evidence rather than a
 * claim.
 *
 * Read them with `0201000000000000`, `0201200000000000` and so on: four bytes
 * of flags for the next 32 PIDs, the last bit saying whether the following
 * bitmap exists. Asking for 0xC0 and 0xE0 returned `7F 01 31`, request out of
 * range, which is how the chain ends.
 *
 * This is a record of one truck, not a specification. It belongs here so a
 * gauge that can never work on this vehicle can be marked as such instead of
 * sitting blank, and so the next person can see what was actually asked.
 */
object MeasuredSupport {

    /** Bitmap responses as they came back, keyed by the PID that was asked. */
    val SUPER_DUTY_2022_PCM_BITMAPS: Map<Int, String> = linkedMapOf(
        0x00 to "98190017",
        0x20 to "8003A001",
        0x40 to "C4C08019",
        0x60 to "FBEBA34F",
        0x80 to "EBF6002D",
        0xA0 to "CC000000",
    )

    /**
     * Every PID the PCM claims, decoded from [SUPER_DUTY_2022_PCM_BITMAPS].
     *
     * Includes the 0x20/0x40/0x60/0x80/0xA0 continuation markers, because they
     * are supported PIDs in their own right and dropping them here would make
     * the list disagree with the bitmaps it came from.
     */
    val SUPER_DUTY_2022_PCM: Set<Int> = SUPER_DUTY_2022_PCM_BITMAPS
        .flatMap { (base, hex) -> PidCatalog.decodeSupportMask(base, Hex.decode(hex)) }
        .toSortedSet()

    /** The continuation markers, which carry no vehicle data. */
    val CONTINUATION_MARKERS: Set<Int> = setOf(0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)

    /** Supported PIDs that actually carry a reading. */
    val SUPER_DUTY_2022_PCM_DATA: Set<Int> =
        SUPER_DUTY_2022_PCM - CONTINUATION_MARKERS

    /**
     * Catalogue entries this truck's PCM can answer.
     *
     * A gauge outside this set is not broken and not a bug - the vehicle simply
     * does not offer that measurement, and saying so is better than showing an
     * empty dial.
     */
    fun supportedCatalogPids(): List<Pid> =
        PidCatalog.ALL.filter { it.id in SUPER_DUTY_2022_PCM_DATA }

    /** Catalogue entries this truck will never answer. */
    fun unsupportedCatalogPids(): List<Pid> =
        PidCatalog.ALL.filter { it.id !in SUPER_DUTY_2022_PCM_DATA }
}
