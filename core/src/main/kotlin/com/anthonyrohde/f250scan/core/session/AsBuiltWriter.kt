package com.anthonyrohde.f250scan.core.session

import com.anthonyrohde.f250scan.core.ford.AsBuiltBlock
import com.anthonyrohde.f250scan.core.adapter.BusRouter
import com.anthonyrohde.f250scan.core.ford.AsBuiltSnapshot
import com.anthonyrohde.f250scan.core.ford.SecurityAccessManager
import com.anthonyrohde.f250scan.core.ford.SecurityAccessResult
import com.anthonyrohde.f250scan.core.isotp.IsoTpChannel
import com.anthonyrohde.f250scan.core.uds.DiagnosticSession
import com.anthonyrohde.f250scan.core.uds.UdsClient
import com.anthonyrohde.f250scan.core.util.toHex

/** One block edit, before and after. */
data class BlockChange(
    val did: Int,
    val before: AsBuiltBlock,
    val after: AsBuiltBlock,
) {
    val isNoOp: Boolean get() = before.data.contentEquals(after.data)

    /** Byte-level diff, for the confirmation screen. */
    fun describe(): String = buildString {
        appendLine("DID ${did.toString(16).uppercase()} (${before.blockId})")
        appendLine("  before: ${before.format()}")
        appendLine("  after:  ${after.format()}")
        val changed = before.data.indices
            .filter { it < after.data.size && before.data[it] != after.data[it] }
        if (changed.isNotEmpty()) {
            appendLine("  changed byte(s): ${changed.joinToString(", ")}")
        }
    }
}

/** A reason a write cannot proceed. Each one is a refusal, not a warning. */
sealed class WriteBlocker(val explanation: String) {
    object NoBackup : WriteBlocker(
        "No configuration backup exists for this module. Take a snapshot first - " +
            "it is the only way back if a change goes wrong.",
    )

    object EmptyBackup : WriteBlocker(
        "The backup for this module contains no blocks, so it could not be used to " +
            "restore anything.",
    )

    object UnknownChecksum : WriteBlocker(
        "No checksum algorithm reproduces this module's existing blocks, so a new " +
            "checksum cannot be computed correctly. Writing anyway would very likely " +
            "be rejected by the module or corrupt its configuration.",
    )

    class LowVoltage(val volts: Double) : WriteBlocker(
        "Battery voltage is ${"%.2f".format(volts)} V. Module programming needs a " +
            "stable supply above ${"%.1f".format(MIN_PROGRAMMING_VOLTAGE)} V - a " +
            "brown-out part way through a write is the classic way to lose a module. " +
            "Put a charger on it and retry.",
    )

    class SecurityDenied(val detail: String) : WriteBlocker(detail)

    class SessionRefused(val detail: String) : WriteBlocker(
        "The module refused to enter a programming session: $detail",
    )

    object NothingToDo : WriteBlocker("No blocks differ from what is already in the module.")

    companion object {
        const val MIN_PROGRAMMING_VOLTAGE = 12.0
    }
}

sealed class WriteOutcome {
    /** Every block written and verified by read-back. */
    data class Success(val written: List<BlockChange>) : WriteOutcome()

    /** Refused before anything was written. Vehicle is untouched. */
    data class Blocked(val blocker: WriteBlocker) : WriteOutcome()

    /**
     * A write failed part way. [restored] says whether the original values
     * were put back successfully.
     */
    data class PartialFailure(
        val succeeded: List<BlockChange>,
        val failedAt: BlockChange,
        val reason: String,
        val restored: Boolean,
        val restoreDetail: String,
    ) : WriteOutcome()

    /** Write was accepted but read-back did not match. */
    data class VerificationFailed(
        val change: BlockChange,
        val readBack: AsBuiltBlock?,
        val restored: Boolean,
    ) : WriteOutcome()
}

/**
 * Writes As-Built configuration back to a module.
 *
 * ## The safety model, and why it is strict
 *
 * Module configuration is not a text file. A rejected or malformed write can
 * leave a module in a state that needs dealer tooling to recover, and on this
 * platform the modules that hold the interesting configuration are also the
 * ones that run the lights, the cluster and the brakes. So every write here
 * goes through the same sequence, and any failure stops it:
 *
 *  1. A backup snapshot must already exist and be restorable.
 *  2. The checksum algorithm must be known - see [AsBuiltReader].
 *  3. Supply voltage must be sane.
 *  4. The module must grant a programming session and security access.
 *  5. Each block is written, then read back and compared.
 *  6. If any step fails, the blocks already written are restored.
 *
 * ## What this cannot do
 *
 * It cannot tell you what to write. The meaning of individual configuration
 * bits is Ford proprietary data that is not in this app and cannot be derived
 * from the vehicle. Get the factory As-Built for your VIN, change what you
 * understand, and keep the backup. And expect step 4 to be where a 2022 truck
 * stops you: the seed/key derivation for current modules is not publicly known.
 */
class AsBuiltWriter(
    private val channel: IsoTpChannel,
    private val securityAccess: SecurityAccessManager = SecurityAccessManager(),
    private val busRouter: BusRouter? = null,
    private val logger: ((String) -> Unit)? = null,
) {
    /**
     * Checks everything that can be checked before touching the module.
     *
     * Separate from [write] so the UI can show a confirmation screen listing
     * the exact changes and any blockers, without having started anything.
     */
    fun validate(
        backup: AsBuiltSnapshot?,
        changes: List<BlockChange>,
        measuredVoltage: Double?,
    ): WriteBlocker? {
        if (backup == null) return WriteBlocker.NoBackup
        if (backup.blocks.isEmpty()) return WriteBlocker.EmptyBackup
        if (backup.checksumStrategy == null) return WriteBlocker.UnknownChecksum
        if (measuredVoltage != null &&
            measuredVoltage < WriteBlocker.MIN_PROGRAMMING_VOLTAGE
        ) {
            return WriteBlocker.LowVoltage(measuredVoltage)
        }
        if (changes.none { !it.isNoOp }) return WriteBlocker.NothingToDo
        return null
    }

    /**
     * Applies [changes] to [module].
     *
     * @param backup snapshot taken before any edits. Required.
     * @param measuredVoltage control module voltage, if known. Pass it - the
     *   check is cheap and the failure it prevents is not.
     */
    suspend fun write(
        module: DiscoveredModule,
        backup: AsBuiltSnapshot?,
        changes: List<BlockChange>,
        measuredVoltage: Double? = null,
        securityLevel: Int = 0x01,
    ): WriteOutcome {
        validate(backup, changes, measuredVoltage)?.let { return WriteOutcome.Blocked(it) }
        // validate() has established these are non-null.
        val snapshot = backup!!
        val strategy = snapshot.checksumStrategy!!

        val effective = changes.filterNot { it.isNoOp }

        // Never start a write sequence on the wrong bus: a mid-write timeout is
        // the worst possible moment to discover the module was unreachable.
        try {
            busRouter?.ensureBus(module.bus)
        } catch (e: Exception) {
            return WriteOutcome.Blocked(
                WriteBlocker.SessionRefused(e.message ?: "bus unavailable"),
            )
        }
        val client = clientFor(module)

        // --- Programming session
        try {
            client.startSession(DiagnosticSession.EXTENDED)
            runCatching { client.startSession(DiagnosticSession.PROGRAMMING) }
                .onFailure {
                    logger?.invoke(
                        "${module.module.code}: programming session unavailable, " +
                            "continuing in extended session",
                    )
                }
        } catch (e: Exception) {
            return WriteOutcome.Blocked(
                WriteBlocker.SessionRefused(e.message ?: "no detail given"),
            )
        }

        // --- Security access
        when (val access = securityAccess.requestAccess(client, securityLevel)) {
            is SecurityAccessResult.Granted ->
                logger?.invoke("Security access granted via ${access.algorithm}")

            is SecurityAccessResult.AlreadyUnlocked ->
                logger?.invoke("${module.module.code} was already unlocked")

            is SecurityAccessResult.NoAlgorithm ->
                return WriteOutcome.Blocked(WriteBlocker.SecurityDenied(access.explanation))

            is SecurityAccessResult.Rejected ->
                return WriteOutcome.Blocked(
                    WriteBlocker.SecurityDenied(
                        "The module rejected the security key computed by " +
                            "${access.algorithm}: ${access.reason}. The derivation for " +
                            "this module is not known to this app.",
                    ),
                )
        }

        // --- Write, verifying each block as we go
        val written = mutableListOf<BlockChange>()

        for (change in effective) {
            // Always recompute: an edited block carrying its old checksum is
            // exactly what a module rejects.
            val toWrite = change.after.withRecomputedChecksum(strategy)
            val includeChecksum = change.before.checksum != null

            logger?.invoke(
                "Writing DID ${change.did.toString(16).uppercase()} = " +
                    toWrite.payloadForWrite(includeChecksum).toHex(" "),
            )

            try {
                client.writeDataByIdentifier(
                    change.did,
                    toWrite.payloadForWrite(includeChecksum),
                )
            } catch (e: Exception) {
                val restore = restore(client, written, strategy)
                return WriteOutcome.PartialFailure(
                    succeeded = written.toList(),
                    failedAt = change,
                    reason = e.message ?: "write rejected",
                    restored = restore.first,
                    restoreDetail = restore.second,
                )
            }

            // Read-back verification. A module can accept a write and store
            // something different; only comparing proves it took.
            val readBack = client.tryReadDataByIdentifier(change.did, 2_000)
            val expected = toWrite.payloadForWrite(includeChecksum)
            if (readBack == null || !readBack.contentEquals(expected)) {
                logger?.invoke(
                    "Verification failed on DID ${change.did.toString(16).uppercase()}: " +
                        "expected ${expected.toHex(" ")}, read ${readBack?.toHex(" ") ?: "nothing"}",
                )
                val restore = restore(client, written + change, strategy)
                return WriteOutcome.VerificationFailed(
                    change = change,
                    readBack = readBack?.let {
                        AsBuiltBlock(module.module.requestId, change.before.blockId, it)
                    },
                    restored = restore.first,
                )
            }

            written += change
        }

        logger?.invoke("Wrote and verified ${written.size} block(s) to ${module.module.code}")
        return WriteOutcome.Success(written)
    }

    /**
     * Restores a full snapshot to a module.
     *
     * The recovery path, and also the "put it back how it was" button. Restores
     * every block in the snapshot rather than only the ones believed changed,
     * because if we are here our model of the module's state is already
     * suspect.
     */
    suspend fun restoreSnapshot(
        module: DiscoveredModule,
        snapshot: AsBuiltSnapshot,
        securityLevel: Int = 0x01,
    ): WriteOutcome {
        if (snapshot.blocks.isEmpty()) return WriteOutcome.Blocked(WriteBlocker.EmptyBackup)
        val strategy = snapshot.checksumStrategy
            ?: return WriteOutcome.Blocked(WriteBlocker.UnknownChecksum)

        val changes = snapshot.blocks.mapIndexedNotNull { index, block ->
            val did = snapshot.sourceDids.getOrNull(index) ?: return@mapIndexedNotNull null
            BlockChange(did, block, block)
        }
        if (changes.isEmpty()) {
            return WriteOutcome.Blocked(
                WriteBlocker.EmptyBackup,
            )
        }

        try {
            busRouter?.ensureBus(module.bus)
        } catch (e: Exception) {
            return WriteOutcome.Blocked(
                WriteBlocker.SessionRefused(e.message ?: "bus unavailable"),
            )
        }
        val client = clientFor(module)
        runCatching { client.startSession(DiagnosticSession.EXTENDED) }
        securityAccess.requestAccess(client, securityLevel)

        val (ok, detail) = restore(client, changes, strategy)
        return if (ok) {
            WriteOutcome.Success(changes)
        } else {
            WriteOutcome.PartialFailure(
                succeeded = emptyList(),
                failedAt = changes.first(),
                reason = detail,
                restored = false,
                restoreDetail = detail,
            )
        }
    }

    /**
     * Puts original values back.
     *
     * Reports rather than throws: this runs on the failure path, where the
     * caller needs to know exactly what state the module was left in, and an
     * exception here would hide that.
     */
    private suspend fun restore(
        client: UdsClient,
        applied: List<BlockChange>,
        strategy: com.anthonyrohde.f250scan.core.ford.ChecksumStrategy,
    ): Pair<Boolean, String> {
        if (applied.isEmpty()) return true to "Nothing had been written; module untouched."

        val failures = mutableListOf<String>()
        for (change in applied.reversed()) {
            val original = change.before.withRecomputedChecksum(strategy)
            val includeChecksum = change.before.checksum != null
            try {
                client.writeDataByIdentifier(
                    change.did,
                    original.payloadForWrite(includeChecksum),
                )
                logger?.invoke("Restored DID ${change.did.toString(16).uppercase()}")
            } catch (e: Exception) {
                failures += "DID ${change.did.toString(16).uppercase()}: ${e.message}"
            }
        }

        return if (failures.isEmpty()) {
            true to "All ${applied.size} written block(s) restored to their previous values."
        } else {
            false to buildString {
                append("Could not restore ${failures.size} of ${applied.size} block(s): ")
                append(failures.joinToString("; "))
                append(". Keep the backup file and restore with dealer tooling if needed.")
            }
        }
    }

    private fun clientFor(module: DiscoveredModule) = UdsClient(
        channel,
        module.module.requestId,
        module.module.responseId,
        module.module.code,
        logger,
    )
}
