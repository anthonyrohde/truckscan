package com.anthonyrohde.truckscan.core.dtc

import com.anthonyrohde.truckscan.core.util.u8

/** The four DTC groups, taken from the top two bits of the first code byte. */
enum class DtcSystem(val letter: Char, val label: String) {
    POWERTRAIN('P', "Powertrain"),
    CHASSIS('C', "Chassis"),
    BODY('B', "Body"),
    NETWORK('U', "Network"),
    ;
    companion object {
        fun fromBits(bits: Int): DtcSystem = when (bits and 0b11) {
            0b00 -> POWERTRAIN
            0b01 -> CHASSIS
            0b10 -> BODY
            else -> NETWORK
        }
    }
}

/**
 * A DTC status byte (ISO 14229-1 Table D.1).
 *
 * The distinction that matters to someone standing at the truck is
 * [confirmed] versus [pending]: a pending code has failed once and may clear
 * itself, while a confirmed code has failed enough times to be stored and, if
 * [warningIndicatorRequested], to light the dash.
 */
@JvmInline
value class DtcStatus(val raw: Int) {
    val testFailed: Boolean get() = raw and 0x01 != 0
    val testFailedThisOperationCycle: Boolean get() = raw and 0x02 != 0
    val pending: Boolean get() = raw and 0x04 != 0
    val confirmed: Boolean get() = raw and 0x08 != 0
    val testNotCompletedSinceLastClear: Boolean get() = raw and 0x10 != 0
    val testFailedSinceLastClear: Boolean get() = raw and 0x20 != 0
    val testNotCompletedThisOperationCycle: Boolean get() = raw and 0x40 != 0
    val warningIndicatorRequested: Boolean get() = raw and 0x80 != 0

    /** True when the fault is present right now, not just historically. */
    val isCurrentlyFailing: Boolean get() = testFailed || testFailedThisOperationCycle

    fun describe(): String {
        val parts = buildList {
            if (confirmed) add("confirmed")
            if (pending) add("pending")
            if (testFailed) add("failing now")
            else if (testFailedSinceLastClear) add("historic")
            if (warningIndicatorRequested) add("MIL requested")
            if (testNotCompletedSinceLastClear) add("test incomplete")
            // Bit 6 had no case at all, so a module reporting 0x40 and nothing
            // else - a real and common answer - displayed as "no status flags
            // set", which reads as "no information" rather than as the
            // specific thing it means.
            if (testNotCompletedThisOperationCycle) add("not tested this cycle")
        }
        return if (parts.isEmpty()) "no status flags set" else parts.joinToString(", ")
    }
}

/**
 * A decoded diagnostic trouble code.
 *
 * @param failureTypeByte the UDS third code byte. Ford uses it heavily - the
 *   same base code with a different FTB is a different fault, e.g. a circuit
 *   short to ground versus an open circuit, so it is kept and displayed rather
 *   than discarded as some scan tools do.
 */
data class Dtc(
    val system: DtcSystem,
    val code: String,
    val failureTypeByte: Int?,
    val status: DtcStatus,
    val rawBytes: ByteArray,
) {
    /** `P0299` for a plain code, `P0299:1C` when a failure type is present. */
    val displayCode: String
        get() = if (failureTypeByte != null && failureTypeByte != 0) {
            "$code:${failureTypeByte.toString(16).uppercase().padStart(2, '0')}"
        } else {
            code
        }

    val description: String get() = DtcCatalog.describe(code, failureTypeByte)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Dtc) return false
        return code == other.code && failureTypeByte == other.failureTypeByte &&
            status.raw == other.status.raw
    }

    override fun hashCode(): Int =
        31 * (31 * code.hashCode() + (failureTypeByte ?: 0)) + status.raw

    override fun toString(): String = "$displayCode [${status.describe()}] $description"

    companion object {

        /**
         * Decodes the two-byte code portion of a DTC.
         *
         * Layout, most significant bit first: two bits of system, two bits of
         * the second digit (0-3), then three hex nibbles.
         */
        fun decodeCodeString(high: Int, low: Int): Pair<DtcSystem, String> {
            val system = DtcSystem.fromBits(high shr 6)
            val secondDigit = (high shr 4) and 0x03
            val thirdDigit = high and 0x0F
            val fourthDigit = (low shr 4) and 0x0F
            val fifthDigit = low and 0x0F

            val code = buildString {
                append(system.letter)
                append(secondDigit)
                append(thirdDigit.toString(16).uppercase())
                append(fourthDigit.toString(16).uppercase())
                append(fifthDigit.toString(16).uppercase())
            }
            return system to code
        }

        /** Builds a DTC from a UDS 3-byte code plus its status byte. */
        fun fromUds(codeBytes: ByteArray, statusByte: Int): Dtc {
            require(codeBytes.size >= 2) { "A DTC needs at least 2 code bytes" }
            val (system, code) = decodeCodeString(codeBytes.u8(0), codeBytes.u8(1))
            val ftb = if (codeBytes.size >= 3) codeBytes.u8(2) else null
            return Dtc(
                system = system,
                code = code,
                failureTypeByte = ftb,
                status = DtcStatus(statusByte),
                rawBytes = codeBytes.copyOf(),
            )
        }

        /**
         * Parses a UDS service 0x19 sub-function 0x02 response body.
         *
         * Body layout after the sub-function echo is a status availability
         * mask followed by 4-byte records of three code bytes and one status
         * byte. A trailing partial record is ignored rather than throwing: a
         * truncated final record means a dropped CAN frame, and reporting the
         * codes we did read is more useful than reporting nothing.
         */
        fun parseDtcListResponse(body: ByteArray): List<Dtc> {
            if (body.size < 2) return emptyList()
            // body[0] is the sub-function echo, body[1] the availability mask.
            val records = body.copyOfRange(2, body.size)
            return buildList {
                var offset = 0
                while (offset + 4 <= records.size) {
                    val codeBytes = records.copyOfRange(offset, offset + 3)
                    val status = records.u8(offset + 3)
                    // All-zero records are padding at the end of a message.
                    if (!(codeBytes.all { it == 0.toByte() } && status == 0)) {
                        add(fromUds(codeBytes, status))
                    }
                    offset += 4
                }
            }
        }

        /**
         * Parses a legacy OBD-II mode 03 response body: 2-byte codes, no status.
         *
         * Still needed because some older modules on a Ford answer mode 03 but
         * not service 0x19.
         */
        fun parseMode03Response(body: ByteArray): List<Dtc> = buildList {
            var offset = 0
            while (offset + 2 <= body.size) {
                val high = body.u8(offset)
                val low = body.u8(offset + 1)
                if (high != 0 || low != 0) {
                    val (system, code) = decodeCodeString(high, low)
                    add(
                        Dtc(
                            system = system,
                            code = code,
                            failureTypeByte = null,
                            // Mode 03 returns stored codes by definition.
                            status = DtcStatus(0x08),
                            rawBytes = byteArrayOf(high.toByte(), low.toByte()),
                        ),
                    )
                }
                offset += 2
            }
        }
    }
}
