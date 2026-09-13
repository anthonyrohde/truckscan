package com.anthonyrohde.truckscan.core.adapter

/**
 * Deciding whether a reply came from an adapter or from a wrong line rate.
 *
 * A serial link configured at the wrong speed does not go silent. It delivers
 * bytes, framed wrongly - so "we received something" is not evidence of
 * anything, and treating it as evidence is how a link ends up established at a
 * rate the adapter is not speaking.
 */
object AdapterProbe {

    /** Chipset and vendor names that appear in an ATI or @1 reply. */
    private val KNOWN_NAMES = listOf("ELM", "STN", "OBDLINK", "SCANTOOL")

    /**
     * True when [reply] is plausibly an adapter answering, rather than noise.
     *
     * A recognised name is conclusive. Failing that the ELM prompt will do,
     * but only alongside text that is overwhelmingly printable: misframed
     * bytes scatter control characters and high bytes through the buffer, and
     * a stray 0x3E among them is not a prompt.
     */
    fun looksLikeAdapter(reply: String): Boolean {
        if (reply.isBlank()) return false
        val upper = reply.uppercase()
        if (KNOWN_NAMES.any { upper.contains(it) }) return true
        if (!reply.contains('>')) return false
        return printableFraction(reply) >= PRINTABLE_THRESHOLD
    }

    /** Proportion of [text] that is printable ASCII, carriage return or newline. */
    fun printableFraction(text: String): Double {
        if (text.isEmpty()) return 0.0
        val printable = text.count { it.code in 0x20..0x7E || it.code == 0x0D || it.code == 0x0A }
        return printable.toDouble() / text.length
    }

    private const val PRINTABLE_THRESHOLD = 0.9
}
