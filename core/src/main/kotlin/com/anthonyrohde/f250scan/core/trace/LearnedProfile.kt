package com.anthonyrohde.f250scan.core.trace

import com.anthonyrohde.f250scan.core.ford.AsBuiltDidMap
import com.anthonyrohde.f250scan.core.ford.ChecksumStrategy
import com.anthonyrohde.f250scan.core.util.Hex
import com.anthonyrohde.f250scan.core.util.toHex

/** A seed and the key a working tool answered it with. */
data class SeedKeyObservation(
    val securityLevel: Int,
    val seed: ByteArray,
    val key: ByteArray,
    /** True when the module accepted the key (positive response seen). */
    val accepted: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeedKeyObservation) return false
        return securityLevel == other.securityLevel &&
            seed.contentEquals(other.seed) && key.contentEquals(other.key)
    }

    override fun hashCode(): Int =
        31 * (31 * securityLevel + seed.contentHashCode()) + key.contentHashCode()

    override fun toString(): String =
        "level ${Hex.encode(securityLevel, 2)}: ${seed.toHex()} -> ${key.toHex()}" +
            if (accepted) " (accepted)" else " (not confirmed)"
}

/** What a capture taught us about one module. */
data class LearnedModule(
    val requestId: Int,
    val responseId: Int,
    /** Identifier to the data the module returned. The real As-Built DID map. */
    val configurationDids: Map<Int, ByteArray> = emptyMap(),
    /** Identification identifiers (0xF1xx), kept separate from configuration. */
    val identificationDids: Map<Int, ByteArray> = emptyMap(),
    /** Identifiers a working tool actually wrote. */
    val writtenDids: Set<Int> = emptySet(),
    /** RoutineControl identifiers observed in use - verified, not guessed. */
    val routineIds: Set<Int> = emptySet(),
    val seedKeyObservations: List<SeedKeyObservation> = emptyList(),
    /** Detected from the observed blocks, or null if nothing explains them. */
    val checksumStrategy: ChecksumStrategy? = null,
) {
    val addressLabel: String get() = Hex.encode(requestId, 3)

    /** Identifiers worth reading, in order. Replaces a blind range sweep. */
    fun readOrder(): List<Int> = (configurationDids.keys + identificationDids.keys).sorted()

    val hasAnything: Boolean
        get() = configurationDids.isNotEmpty() || identificationDids.isNotEmpty() ||
            writtenDids.isNotEmpty() || routineIds.isNotEmpty() ||
            seedKeyObservations.isNotEmpty()
}

/**
 * Everything a bus capture taught us.
 *
 * This is the point of the trace importer. Three things in this codebase were
 * unavoidably hypotheses - which identifiers hold As-Built blocks, which
 * checksum algorithm the modules use, and which RoutineControl identifiers are
 * real. None of them can be derived from first principles, but all three are
 * plainly visible in a recording of a working tool talking to the truck.
 *
 * A profile turns those guesses into measurements. Once imported:
 *
 *  - As-Built reads go straight to the identifiers that actually exist instead
 *    of sweeping several hundred candidates.
 *  - The checksum algorithm is confirmed against real blocks.
 *  - Routine identifiers become known rather than absent, without this app ever
 *    having probed the identifier space on a live vehicle.
 *  - Any security handshake in the capture is recorded. That does not yield the
 *    derivation algorithm, but see [com.anthonyrohde.f250scan.core.ford.SeedKeyAlgorithm.Replay].
 */
data class LearnedProfile(
    val modules: List<LearnedModule>,
    val sourceDescription: String,
    val framesParsed: Int,
    val messagesReconstructed: Int,
    val capturedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    fun module(requestId: Int): LearnedModule? =
        modules.firstOrNull { it.requestId == requestId }

    val isEmpty: Boolean get() = modules.none { it.hasAnything }

    /** Plain-language summary for the import screen. */
    fun summarise(): String = buildString {
        if (isEmpty) {
            appendLine("Nothing usable was recovered from this capture.")
            appendLine(
                "Reconstructed $messagesReconstructed message(s) from $framesParsed " +
                    "frame(s), but none were diagnostic reads or writes.",
            )
            return@buildString
        }

        appendLine(
            "Learned from $framesParsed frame(s), $messagesReconstructed reconstructed " +
                "message(s).",
        )
        appendLine()
        for (module in modules.filter { it.hasAnything }) {
            appendLine("Module ${module.addressLabel}:")
            if (module.configurationDids.isNotEmpty()) {
                appendLine("  ${module.configurationDids.size} configuration identifier(s)")
            }
            if (module.identificationDids.isNotEmpty()) {
                appendLine("  ${module.identificationDids.size} identification identifier(s)")
            }
            module.checksumStrategy?.let { appendLine("  checksum: ${it.label}") }
            if (module.writtenDids.isNotEmpty()) {
                appendLine(
                    "  ${module.writtenDids.size} identifier(s) written by the captured tool",
                )
            }
            if (module.routineIds.isNotEmpty()) {
                appendLine(
                    "  routines: " +
                        module.routineIds.sorted().joinToString(", ") { Hex.encode(it, 4) },
                )
            }
            if (module.seedKeyObservations.isNotEmpty()) {
                appendLine("  ${module.seedKeyObservations.size} security handshake(s) captured")
            }
        }
    }

    // ------------------------------------------------------------ serialisation

    /**
     * Renders as line-based text.
     *
     * Text rather than JSON so a profile can be read, diffed, edited and shared
     * the same way an As-Built backup can, and so :core needs no serialisation
     * dependency.
     */
    fun serialise(): String = buildString {
        appendLine("# f250scan learned profile v$FORMAT_VERSION")
        appendLine("# source: $sourceDescription")
        appendLine("# captured: $capturedAtEpochMillis")
        appendLine("# frames: $framesParsed messages: $messagesReconstructed")
        appendLine("#")
        for (module in modules.filter { it.hasAnything }) {
            appendLine("module ${Hex.encode(module.requestId, 3)} ${Hex.encode(module.responseId, 3)}")
            module.checksumStrategy?.let { appendLine("  checksum ${it.name}") }
            module.configurationDids.toSortedMap().forEach { (did, data) ->
                appendLine("  config ${Hex.encode(did, 4)} ${data.toHex()}")
            }
            module.identificationDids.toSortedMap().forEach { (did, data) ->
                appendLine("  ident ${Hex.encode(did, 4)} ${data.toHex()}")
            }
            module.writtenDids.sorted().forEach { appendLine("  written ${Hex.encode(it, 4)}") }
            module.routineIds.sorted().forEach { appendLine("  routine ${Hex.encode(it, 4)}") }
            module.seedKeyObservations.forEach {
                appendLine(
                    "  seedkey ${Hex.encode(it.securityLevel, 2)} ${it.seed.toHex()} " +
                        "${it.key.toHex()} ${if (it.accepted) "accepted" else "unconfirmed"}",
                )
            }
        }
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun deserialise(text: String): LearnedProfile? {
            val modules = mutableListOf<LearnedModule>()
            var source = "imported profile"
            var captured = System.currentTimeMillis()
            var frames = 0
            var messages = 0

            var requestId: Int? = null
            var responseId = 0
            var checksum: ChecksumStrategy? = null
            val config = linkedMapOf<Int, ByteArray>()
            val ident = linkedMapOf<Int, ByteArray>()
            val written = linkedSetOf<Int>()
            val routines = linkedSetOf<Int>()
            val seedKeys = mutableListOf<SeedKeyObservation>()

            fun flush() {
                val id = requestId ?: return
                modules += LearnedModule(
                    requestId = id,
                    responseId = responseId,
                    configurationDids = LinkedHashMap(config),
                    identificationDids = LinkedHashMap(ident),
                    writtenDids = LinkedHashSet(written),
                    routineIds = LinkedHashSet(routines),
                    seedKeyObservations = seedKeys.toList(),
                    checksumStrategy = checksum,
                )
                config.clear(); ident.clear(); written.clear()
                routines.clear(); seedKeys.clear()
                checksum = null
            }

            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue

                if (line.startsWith("#")) {
                    val comment = line.removePrefix("#").trim()
                    when {
                        comment.startsWith("source:") ->
                            source = comment.removePrefix("source:").trim()
                        comment.startsWith("captured:") ->
                            captured = comment.removePrefix("captured:").trim().toLongOrNull()
                                ?: captured
                        comment.startsWith("frames:") -> {
                            val parts = comment.split(Regex("\\s+"))
                            frames = parts.getOrNull(1)?.toIntOrNull() ?: 0
                            messages = parts.getOrNull(3)?.toIntOrNull() ?: 0
                        }
                    }
                    continue
                }

                val parts = line.split(Regex("\\s+"))
                when (parts[0]) {
                    "module" -> {
                        flush()
                        requestId = parts.getOrNull(1)?.toIntOrNull(16)
                        responseId = parts.getOrNull(2)?.toIntOrNull(16)
                            ?: ((requestId ?: 0) + 8)
                    }
                    "checksum" -> checksum = parts.getOrNull(1)
                        ?.let { name -> ChecksumStrategy.entries.firstOrNull { it.name == name } }
                    "config" -> {
                        val did = parts.getOrNull(1)?.toIntOrNull(16)
                        val data = parts.getOrNull(2)?.let { Hex.decodeOrNull(it) }
                        if (did != null && data != null) config[did] = data
                    }
                    "ident" -> {
                        val did = parts.getOrNull(1)?.toIntOrNull(16)
                        val data = parts.getOrNull(2)?.let { Hex.decodeOrNull(it) }
                        if (did != null && data != null) ident[did] = data
                    }
                    "written" -> parts.getOrNull(1)?.toIntOrNull(16)?.let { written += it }
                    "routine" -> parts.getOrNull(1)?.toIntOrNull(16)?.let { routines += it }
                    "seedkey" -> {
                        val level = parts.getOrNull(1)?.toIntOrNull(16)
                        val seed = parts.getOrNull(2)?.let { Hex.decodeOrNull(it) }
                        val key = parts.getOrNull(3)?.let { Hex.decodeOrNull(it) }
                        if (level != null && seed != null && key != null) {
                            seedKeys += SeedKeyObservation(
                                level, seed, key, parts.getOrNull(4) == "accepted",
                            )
                        }
                    }
                }
            }
            flush()

            if (modules.isEmpty()) return null
            return LearnedProfile(modules, source, frames, messages, captured)
        }

        /** True when [did] should be treated as configuration rather than identification. */
        fun isConfiguration(did: Int): Boolean = AsBuiltDidMap.isConfigurationCandidate(did)
    }
}
