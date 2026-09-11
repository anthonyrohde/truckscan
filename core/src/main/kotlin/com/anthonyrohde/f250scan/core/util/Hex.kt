package com.anthonyrohde.f250scan.core.util

/** Hex helpers. Diagnostics work is almost entirely byte twiddling, so these
 *  get used everywhere and are kept allocation-light. */
object Hex {

    private val DIGITS = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray, separator: String = ""): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * (2 + separator.length))
        bytes.forEachIndexed { i, b ->
            if (i > 0) sb.append(separator)
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun encode(value: Int, width: Int): String =
        value.toString(16).uppercase().padStart(width, '0')

    /** Decodes a hex string, ignoring any whitespace. Throws on odd length or
     *  non-hex characters, because silently dropping a nibble in a diagnostic
     *  payload would corrupt a module write. */
    fun decode(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "Hex string has odd length: '$text'" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = digit(clean[i * 2], text)
            val lo = digit(clean[i * 2 + 1], text)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun decodeOrNull(text: String): ByteArray? = runCatching { decode(text) }.getOrNull()

    private fun digit(c: Char, context: String): Int {
        val d = Character.digit(c, 16)
        require(d >= 0) { "Illegal hex character '$c' in '$context'" }
        return d
    }

    fun isHex(text: String): Boolean {
        val clean = text.filterNot { it.isWhitespace() }
        return clean.isNotEmpty() && clean.all { Character.digit(it, 16) >= 0 }
    }
}

/** Unsigned read helpers. UDS payloads are big-endian throughout. */
fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

fun ByteArray.u24(index: Int): Int = (u8(index) shl 16) or (u8(index + 1) shl 8) or u8(index + 2)

fun ByteArray.u32(index: Int): Long =
    (u8(index).toLong() shl 24) or (u8(index + 1).toLong() shl 16) or
        (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong()

fun ByteArray.toHex(separator: String = ""): String = Hex.encode(this, separator)
