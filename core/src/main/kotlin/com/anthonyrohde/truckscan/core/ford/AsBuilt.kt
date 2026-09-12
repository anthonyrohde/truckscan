package com.anthonyrohde.truckscan.core.ford

import com.anthonyrohde.truckscan.core.util.Hex
import com.anthonyrohde.truckscan.core.util.toHex

/**
 * Ford As-Built configuration data.
 *
 * ## What As-Built is
 *
 * Every Ford module holds a block of configuration bytes describing how that
 * particular truck was ordered: which lights it has, whether a trailer brake
 * controller is fitted, what the cluster should display, which SYNC features
 * are licensed. Ford publishes the factory values per VIN, and changing them is
 * how features get enabled or suppressed after the fact.
 *
 * ## What is verified in this file and what is not
 *
 * Verified and safe to rely on:
 *  - The text format. `726-01-01 0F14 0004 0000 0A` is module address, block
 *    identifier, data words, trailing checksum byte. [AsBuiltBlock.parse] and
 *    [AsBuiltBlock.format] round-trip it, and that is covered by tests.
 *  - The transport. Blocks are read with UDS ReadDataByIdentifier (0x22) and
 *    written with WriteDataByIdentifier (0x2E), both of which are standard.
 *
 * Hypothesis, resolved at runtime rather than asserted here:
 *  - Which data identifier corresponds to which As-Built block. The mapping in
 *    [AsBuiltDidMap] is the community-held convention and is the starting
 *    point for [com.anthonyrohde.truckscan.core.session.AsBuiltReader]'s
 *    discovery sweep, which simply asks the module which identifiers exist.
 *  - The checksum algorithm. [ChecksumStrategy] carries the candidates and
 *    [ChecksumStrategy.detect] picks whichever one actually validates the
 *    blocks your truck returned.
 *
 * Not present, and not obtainable from code:
 *  - What the individual bits mean. That is Ford's proprietary data. This app
 *    can show you the bytes, back them up, and write them back; it cannot tell
 *    you that bit 3 of byte 2 of block 01-01 is the daytime running lamps. Use
 *    Ford's published As-Built for your VIN alongside it.
 */
data class AsBuiltBlock(
    /** Module diagnostic request address, e.g. 0x726 for the BCM. */
    val moduleAddress: Int,
    /** Block identifier as it appears in the Ford format, e.g. "01-01". */
    val blockId: String,
    /** Configuration bytes, excluding the trailing checksum. */
    val data: ByteArray,
    /** Checksum byte as read from the module, if the block carries one. */
    val checksum: Int? = null,
) {
    val moduleAddressLabel: String get() = Hex.encode(moduleAddress, 3)

    /**
     * Renders in Ford's As-Built notation: address, block, then data grouped
     * into 16-bit words with the checksum as a trailing byte.
     */
    fun format(): String {
        val words = buildList {
            var i = 0
            while (i + 1 < data.size) {
                add(data.copyOfRange(i, i + 2).toHex())
                i += 2
            }
            // Odd trailing byte is rendered on its own, as Ford does.
            if (i < data.size) add(data.copyOfRange(i, data.size).toHex())
        }
        val checksumPart = checksum?.let { " " + Hex.encode(it, 2) } ?: ""
        return "$moduleAddressLabel-$blockId ${words.joinToString(" ")}$checksumPart"
    }

    /** Verifies the stored checksum against [strategy]. */
    fun isChecksumValid(strategy: ChecksumStrategy): Boolean {
        val expected = checksum ?: return true
        return strategy.compute(data) == expected
    }

    /** A copy with the checksum recomputed - always do this after editing data. */
    fun withRecomputedChecksum(strategy: ChecksumStrategy): AsBuiltBlock =
        copy(checksum = strategy.compute(data))

    /** The bytes to send in a WriteDataByIdentifier request. */
    fun payloadForWrite(includeChecksum: Boolean): ByteArray =
        if (includeChecksum && checksum != null) data + byteArrayOf(checksum.toByte()) else data

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AsBuiltBlock) return false
        return moduleAddress == other.moduleAddress && blockId == other.blockId &&
            data.contentEquals(other.data) && checksum == other.checksum
    }

    override fun hashCode(): Int {
        var result = moduleAddress
        result = 31 * result + blockId.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (checksum ?: 0)
        return result
    }

    override fun toString(): String = format()

    companion object {
        /**
         * Parses one line of Ford As-Built text.
         *
         * Accepts the published format, with or without a checksum byte, and
         * tolerates the spacing variations that appear between Ford's own
         * exports and community-pasted data.
         *
         * The trailing single byte is treated as a checksum only when the
         * preceding groups are full 16-bit words, which is how the published
         * format is always shaped. A line of all-single-byte groups is read as
         * pure data instead, since assuming a checksum there would silently
         * drop a configuration byte.
         */
        fun parse(line: String): AsBuiltBlock? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                return null
            }

            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) return null

            // Header is address-block, e.g. "726-01-01".
            val header = parts[0].split('-')
            if (header.size < 2) return null
            val address = header[0].toIntOrNull(16) ?: return null
            val blockId = header.drop(1).joinToString("-")

            val groups = parts.drop(1).filter { Hex.isHex(it) }
            if (groups.isEmpty()) return null

            val lastIsSingleByte = groups.last().length == 2
            val othersAreWords = groups.dropLast(1).all { it.length == 4 }
            val hasChecksum = groups.size > 1 && lastIsSingleByte && othersAreWords

            val dataGroups = if (hasChecksum) groups.dropLast(1) else groups
            val data = Hex.decodeOrNull(dataGroups.joinToString("")) ?: return null
            val checksum = if (hasChecksum) groups.last().toIntOrNull(16) else null

            return AsBuiltBlock(address, blockId, data, checksum)
        }

        /** Parses a whole As-Built file or pasted block. */
        fun parseAll(text: String): List<AsBuiltBlock> =
            text.lineSequence().mapNotNull { parse(it) }.toList()
    }
}

/**
 * Candidate As-Built checksum algorithms.
 *
 * Ford's checksum is not published. These are the plausible byte-sum variants;
 * [detect] identifies which one your modules actually use by testing them
 * against blocks already read from the truck. That turns an unknown into a
 * measurement, which is the only defensible way to handle it - writing a block
 * with a wrong checksum is how modules get rejected or bricked.
 */
enum class ChecksumStrategy(val label: String) {

    /** Two's complement: data bytes plus checksum sum to zero, modulo 256. */
    TWOS_COMPLEMENT("Two's complement (sum + checksum = 0x00)") {
        override fun compute(data: ByteArray): Int {
            val sum = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
            return (0x100 - (sum and 0xFF)) and 0xFF
        }
    },

    /** One's complement: data bytes plus checksum sum to 0xFF. */
    ONES_COMPLEMENT("One's complement (sum + checksum = 0xFF)") {
        override fun compute(data: ByteArray): Int {
            val sum = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
            return (0xFF - (sum and 0xFF)) and 0xFF
        }
    },

    /** Plain truncated sum. */
    SUM("Plain sum, low byte") {
        override fun compute(data: ByteArray): Int =
            data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) } and 0xFF
    },

    /** Bytewise exclusive or. */
    XOR("Bytewise XOR") {
        override fun compute(data: ByteArray): Int =
            data.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) } and 0xFF
    },
    ;

    abstract fun compute(data: ByteArray): Int

    companion object {
        /**
         * Picks the strategy that validates the most of [blocks].
         *
         * Returns null when no candidate explains the data, which is a signal
         * to refuse writes rather than guess: if we cannot reproduce the
         * checksum the module already accepted, we have no business computing a
         * new one.
         */
        fun detect(blocks: List<AsBuiltBlock>): ChecksumStrategy? {
            val withChecksums = blocks.filter { it.checksum != null && it.data.isNotEmpty() }
            if (withChecksums.isEmpty()) return null

            val scored = entries.map { strategy ->
                strategy to withChecksums.count { strategy.compute(it.data) == it.checksum }
            }
            val (best, matches) = scored.maxByOrNull { it.second } ?: return null

            // Demand unanimity. A strategy that explains only some blocks is
            // coincidence, not the algorithm.
            return if (matches == withChecksums.size) best else null
        }
    }
}

/**
 * Maps As-Built block numbers onto UDS data identifiers.
 *
 * The 0xDE00 base is the community-held convention for Ford module
 * configuration blocks and is where discovery starts looking. It is a starting
 * hypothesis, not a claim: the scan in
 * [com.anthonyrohde.truckscan.core.session.AsBuiltReader] sweeps the ranges
 * below and reports which identifiers the module actually answers, so a wrong
 * base costs a slower first scan rather than a wrong result.
 */
object AsBuiltDidMap {

    /** Primary hypothesis for the configuration block range. */
    const val CONFIG_BASE_DID = 0xDE00

    /** Ranges a discovery sweep should try, in priority order. */
    val DISCOVERY_RANGES: List<IntRange> = listOf(
        0xDE00..0xDE3F, // configuration blocks - primary hypothesis
        0xF100..0xF1FF, // standard + manufacturer identification DIDs
        0xD000..0xD03F, // seen carrying configuration on some modules
        0x8000..0x803F, // ditto
    )

    /**
     * Standardised identification identifiers (ISO 14229-1 Annex C).
     *
     * These carry plain text - VIN, part numbers, calibration levels - and are
     * emphatically not checksummed configuration blocks. They must be excluded
     * from checksum detection: feeding an ASCII part number into it would make
     * the unanimity test fail and wrongly conclude the algorithm is unknown.
     */
    val IDENTIFICATION_RANGE: IntRange = 0xF100..0xF1FF

    /** True when [did] could plausibly hold checksummed configuration data. */
    fun isConfigurationCandidate(did: Int): Boolean = did !in IDENTIFICATION_RANGE

    fun didForBlock(blockNumber: Int): Int = CONFIG_BASE_DID + blockNumber

    /** Renders a DID back into Ford's block notation, best effort. */
    fun blockIdForDid(did: Int): String = when (did) {
        in CONFIG_BASE_DID..(CONFIG_BASE_DID + 0xFF) ->
            Hex.encode(did - CONFIG_BASE_DID, 2) + "-01"
        else -> Hex.encode(did, 4)
    }
}

/**
 * A complete configuration snapshot of one module.
 *
 * Take one before changing anything in FORScan. This app does not write, so a
 * snapshot is not a precondition for anything it does - it is the record that
 * lets a change made elsewhere be undone, which is why it carries enough
 * context to be usable on a different day from a different phone.
 */
data class AsBuiltSnapshot(
    val moduleCode: String,
    val moduleAddress: Int,
    val vin: String?,
    val capturedAtEpochMillis: Long,
    val blocks: List<AsBuiltBlock>,
    val checksumStrategy: ChecksumStrategy?,
    /** Identifiers that answered, for reproducing the read exactly. */
    val sourceDids: List<Int> = emptyList(),
    val partNumber: String? = null,
    val calibrationLevel: String? = null,
) {
    /** Renders the snapshot as a Ford-style As-Built text file. */
    fun toFordFormat(): String = buildString {
        appendLine("# As-Built snapshot - $moduleCode (${Hex.encode(moduleAddress, 3)})")
        vin?.let { appendLine("# VIN: $it") }
        partNumber?.let { appendLine("# Part number: $it") }
        calibrationLevel?.let { appendLine("# Calibration: $it") }
        appendLine("# Captured: $capturedAtEpochMillis")
        appendLine("# Checksum strategy: ${checksumStrategy?.label ?: "undetermined"}")
        appendLine("#")
        appendLine("# Restore this file with the As-Built restore function. Keep it.")
        blocks.forEach { appendLine(it.format()) }
    }

    val isRestorable: Boolean get() = blocks.isNotEmpty() && checksumStrategy != null
}
