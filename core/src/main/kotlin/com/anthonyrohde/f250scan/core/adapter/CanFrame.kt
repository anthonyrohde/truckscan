package com.anthonyrohde.f250scan.core.adapter

import com.anthonyrohde.f250scan.core.util.Hex
import com.anthonyrohde.f250scan.core.util.toHex

/**
 * A single raw CAN frame as seen on the wire.
 *
 * We work at frame level rather than letting the adapter reassemble messages,
 * because ISO-TP reassembly in :core gives us control over flow-control timing
 * and lets us handle the non-standard addressing some Ford modules use. It also
 * means the entire transport protocol is testable from a byte array.
 */
data class CanFrame(
    val id: Int,
    val data: ByteArray,
) {
    val isExtendedId: Boolean get() = id > 0x7FF

    override fun toString(): String = "${Hex.encode(id, if (isExtendedId) 8 else 3)}#${data.toHex()}"

    // data class equals/hashCode would compare the ByteArray by reference.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanFrame) return false
        return id == other.id && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * id + data.contentHashCode()

    companion object {
        /**
         * Parses one adapter response line into a frame.
         *
         * With headers on (ATH1) and spaces off (ATS0) a line is the CAN ID
         * followed by the payload, e.g. `7E8064100BE3FA813`. The ID width is
         * not self-describing in that format, so the caller passes the
         * addressing mode currently configured on the bus.
         *
         * Returns null for anything that is not a frame - status words like
         * `OK`, `SEARCHING...` and `NO DATA` all land here and are the
         * adapter layer's business, not ours.
         */
        fun parse(line: String, extendedId: Boolean): CanFrame? {
            val clean = line.filterNot { it.isWhitespace() || it == '>' }
            if (clean.isEmpty() || !Hex.isHex(clean)) return null

            val idChars = if (extendedId) 8 else 3
            // Need the ID plus at least one payload byte, and the payload must
            // be whole bytes. Anything else is a truncated or corrupt line.
            if (clean.length < idChars + 2) return null
            if ((clean.length - idChars) % 2 != 0) return null

            val id = clean.substring(0, idChars).toIntOrNull(16) ?: return null
            val payload = Hex.decodeOrNull(clean.substring(idChars)) ?: return null
            return CanFrame(id, payload)
        }
    }
}
