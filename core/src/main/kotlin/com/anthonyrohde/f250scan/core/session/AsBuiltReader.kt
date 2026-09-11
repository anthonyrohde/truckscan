package com.anthonyrohde.f250scan.core.session

import com.anthonyrohde.f250scan.core.ford.AsBuiltBlock
import com.anthonyrohde.f250scan.core.ford.AsBuiltDidMap
import com.anthonyrohde.f250scan.core.ford.AsBuiltSnapshot
import com.anthonyrohde.f250scan.core.ford.ChecksumStrategy
import com.anthonyrohde.f250scan.core.isotp.IsoTpChannel
import com.anthonyrohde.f250scan.core.trace.LearnedModule
import com.anthonyrohde.f250scan.core.uds.DiagnosticSession
import com.anthonyrohde.f250scan.core.uds.UdsClient

data class AsBuiltScanProgress(
    val didsProbed: Int,
    val didsTotal: Int,
    val blocksFound: Int,
    val currentDid: Int,
)

/**
 * Reads a module's As-Built configuration.
 *
 * Two things here are worth understanding before trusting the output.
 *
 * First, identifier discovery. Rather than assume the community's 0xDE00
 * convention is right for a 2022 module, this sweeps the candidate ranges in
 * [AsBuiltDidMap.DISCOVERY_RANGES] and records which identifiers the module
 * actually answers. That is slower than a lookup table but it is a measurement
 * rather than a guess, and it will still work on a module whose layout nobody
 * has documented.
 *
 * Second, checksum interpretation. Whether a module includes its checksum byte
 * in the data it returns is not documented either, so [snapshot] tries both
 * readings and keeps whichever produces checksums that a single algorithm can
 * reproduce across every block. If neither does, the snapshot records no
 * strategy and [AsBuiltWriter] will refuse to write - which is the correct
 * outcome, because a block written with a wrong checksum is how a module gets
 * rejected.
 */
class AsBuiltReader(
    private val channel: IsoTpChannel,
    private val logger: ((String) -> Unit)? = null,
) {
    /**
     * Finds which data identifiers this module answers.
     *
     * @param ranges identifier ranges to sweep.
     * @param timeoutMillis per-identifier wait; tight, since an unsupported
     *   identifier is refused or ignored quickly.
     */
    suspend fun discoverDids(
        module: DiscoveredModule,
        ranges: List<IntRange> = AsBuiltDidMap.DISCOVERY_RANGES,
        timeoutMillis: Long = 300,
        onProgress: ((AsBuiltScanProgress) -> Unit)? = null,
    ): Map<Int, ByteArray> {
        val client = clientFor(module)
        // An extended session is needed before many modules will discuss
        // configuration data at all. A refusal here is not fatal: some modules
        // answer configuration reads in the default session.
        runCatching { client.startSession(DiagnosticSession.EXTENDED) }
            .onFailure { logger?.invoke("${module.module.code}: extended session refused, continuing") }

        val all = ranges.flatMap { it.toList() }
        val found = linkedMapOf<Int, ByteArray>()

        all.forEachIndexed { index, did ->
            client.tryReadDataByIdentifier(did, timeoutMillis)?.let { data ->
                if (data.isNotEmpty()) {
                    found[did] = data
                    logger?.invoke(
                        "${module.module.code}: DID ${did.toString(16).uppercase()} " +
                            "returned ${data.size} byte(s)",
                    )
                }
            }
            onProgress?.invoke(AsBuiltScanProgress(index + 1, all.size, found.size, did))
        }
        return found
    }

    /**
     * Takes a full configuration snapshot.
     *
     * This is what the app stores before any write, and what a restore replays.
     */
    suspend fun snapshot(
        module: DiscoveredModule,
        ranges: List<IntRange> = AsBuiltDidMap.DISCOVERY_RANGES,
        onProgress: ((AsBuiltScanProgress) -> Unit)? = null,
    ): AsBuiltSnapshot {
        val raw = discoverDids(module, ranges, onProgress = onProgress)
        val identification = runCatching {
            ModuleIdentification.read(clientFor(module))
        }.getOrNull()

        val (blocks, strategy) = interpretBlocks(module.module.requestId, raw)

        if (strategy == null && blocks.isNotEmpty()) {
            logger?.invoke(
                "${module.module.code}: no checksum algorithm reproduces this module's " +
                    "blocks. The snapshot is still a valid backup, but writes will be " +
                    "refused until the algorithm is known.",
            )
        }

        return AsBuiltSnapshot(
            moduleCode = module.module.code,
            moduleAddress = module.module.requestId,
            vin = identification?.vin,
            capturedAtEpochMillis = System.currentTimeMillis(),
            blocks = blocks,
            checksumStrategy = strategy,
            sourceDids = raw.keys.toList(),
            partNumber = identification?.partNumber,
            calibrationLevel = identification?.calibrationLevel,
        )
    }

    /**
     * Takes a snapshot using identifiers learned from a bus capture.
     *
     * This is the fast, exact path. Instead of sweeping several hundred
     * candidate identifiers and hoping the 0xDE00 convention holds, it reads
     * the identifiers a working tool was actually observed reading on this
     * module - typically a handful, so the read takes a second rather than a
     * minute, and finds blocks a blind sweep of the wrong range would miss
     * entirely.
     *
     * The checksum algorithm from the capture is preferred over re-deriving it,
     * since a capture usually offers more blocks to test against than one live
     * read does. It still falls back to live detection if the capture could not
     * determine one.
     */
    suspend fun snapshotFromProfile(
        module: DiscoveredModule,
        learned: LearnedModule,
        onProgress: ((AsBuiltScanProgress) -> Unit)? = null,
    ): AsBuiltSnapshot {
        val client = clientFor(module)
        runCatching { client.startSession(DiagnosticSession.EXTENDED) }

        val order = learned.readOrder()
        val raw = linkedMapOf<Int, ByteArray>()

        order.forEachIndexed { index, did ->
            client.tryReadDataByIdentifier(did, 1_500)?.let { data ->
                if (data.isNotEmpty()) raw[did] = data
            }
            onProgress?.invoke(AsBuiltScanProgress(index + 1, order.size, raw.size, did))
        }

        val identification = runCatching { ModuleIdentification.read(clientFor(module)) }
            .getOrNull()
        val (blocks, derived) = interpretBlocks(module.module.requestId, raw)

        return AsBuiltSnapshot(
            moduleCode = module.module.code,
            moduleAddress = module.module.requestId,
            vin = identification?.vin,
            capturedAtEpochMillis = System.currentTimeMillis(),
            blocks = blocks,
            checksumStrategy = learned.checksumStrategy ?: derived,
            sourceDids = raw.keys.toList(),
            partNumber = identification?.partNumber,
            calibrationLevel = identification?.calibrationLevel,
        )
    }

    /** Reads one identifier and presents it as a block. */
    suspend fun readBlock(module: DiscoveredModule, did: Int): AsBuiltBlock? {
        val data = clientFor(module).tryReadDataByIdentifier(did, 1_000) ?: return null
        return AsBuiltBlock(
            moduleAddress = module.module.requestId,
            blockId = AsBuiltDidMap.blockIdForDid(did),
            data = data,
        )
    }

    /**
     * Decides whether returned data includes a trailing checksum byte.
     *
     * Tries both readings and keeps the one where a single algorithm explains
     * every block. Unanimity is required deliberately - a strategy that fits
     * some blocks and not others is coincidence, and acting on coincidence here
     * writes bad data to a module.
     */
    internal fun interpretBlocks(
        moduleAddress: Int,
        raw: Map<Int, ByteArray>,
    ): Pair<List<AsBuiltBlock>, ChecksumStrategy?> {
        if (raw.isEmpty()) return emptyList<AsBuiltBlock>() to null

        // Identification identifiers hold plain text, not checksummed
        // configuration. Including them would poison checksum detection - an
        // ASCII part number never satisfies a byte-sum algorithm - and make us
        // wrongly conclude the checksum is unknown, blocking every write.
        // They are surfaced separately through ModuleIdentification.
        val configuration = raw.filterKeys { AsBuiltDidMap.isConfigurationCandidate(it) }
        if (configuration.isEmpty()) return emptyList<AsBuiltBlock>() to null

        // Reading A: the final byte of each response is a checksum.
        val withChecksum = configuration
            .filter { it.value.size >= 2 }
            .map { (did, data) ->
                AsBuiltBlock(
                    moduleAddress = moduleAddress,
                    blockId = AsBuiltDidMap.blockIdForDid(did),
                    data = data.copyOfRange(0, data.size - 1),
                    checksum = data[data.size - 1].toInt() and 0xFF,
                )
            }

        val detected = ChecksumStrategy.detect(withChecksum)
        if (detected != null) {
            logger?.invoke("Checksum algorithm identified: ${detected.label}")
            return withChecksum to detected
        }

        // Reading B: it is all configuration data and carries no checksum.
        val plain = configuration.map { (did, data) ->
            AsBuiltBlock(
                moduleAddress = moduleAddress,
                blockId = AsBuiltDidMap.blockIdForDid(did),
                data = data,
            )
        }
        return plain to null
    }

    private fun clientFor(module: DiscoveredModule) = UdsClient(
        channel,
        module.module.requestId,
        module.module.responseId,
        module.module.code,
        logger,
    )
}
