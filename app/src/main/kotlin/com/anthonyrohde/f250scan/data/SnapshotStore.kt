package com.anthonyrohde.f250scan.data

import android.content.Context
import com.anthonyrohde.f250scan.core.ford.AsBuiltBlock
import com.anthonyrohde.f250scan.core.ford.AsBuiltSnapshot
import com.anthonyrohde.f250scan.core.ford.ChecksumStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A stored backup, as listed in the UI. */
data class StoredSnapshot(
    val file: File,
    val moduleCode: String,
    val moduleAddress: Int,
    val capturedAtEpochMillis: Long,
    val blockCount: Int,
    val vin: String?,
) {
    val displayDate: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .format(Date(capturedAtEpochMillis))
}

/**
 * Stores As-Built backups as Ford-format text files.
 *
 * Plain files rather than a database, deliberately. A configuration backup is
 * only worth having if it survives the app: the user can share it to email or
 * cloud storage, read it on a laptop, paste it into a forum thread, or hand it
 * to whoever ends up recovering the module. A row in an app-private SQLite file
 * fails at every one of those.
 */
class SnapshotStore(context: Context) {

    private val directory: File = File(context.filesDir, "asbuilt").apply { mkdirs() }

    /** Exposed so the UI can offer a share intent over a backup. */
    val backupDirectory: File get() = directory

    suspend fun save(snapshot: AsBuiltSnapshot): File = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date(snapshot.capturedAtEpochMillis))
        val file = File(directory, "${snapshot.moduleCode}-$stamp.abt")
        file.writeText(snapshot.toFordFormat())
        file
    }

    suspend fun list(): List<StoredSnapshot> = withContext(Dispatchers.IO) {
        directory.listFiles { f -> f.extension == "abt" }
            ?.mapNotNull { describe(it) }
            ?.sortedByDescending { it.capturedAtEpochMillis }
            ?: emptyList()
    }

    /** Most recent backup for a module, which is what the writer requires. */
    suspend fun latestFor(moduleCode: String): AsBuiltSnapshot? = withContext(Dispatchers.IO) {
        list().firstOrNull { it.moduleCode == moduleCode }?.let { load(it.file) }
    }

    suspend fun load(file: File): AsBuiltSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val text = file.readText()
            val blocks = AsBuiltBlock.parseAll(text)
            if (blocks.isEmpty()) return@runCatching null

            AsBuiltSnapshot(
                moduleCode = header(text, "As-Built snapshot -")
                    ?.substringBefore(" (")?.trim() ?: "UNKNOWN",
                moduleAddress = blocks.first().moduleAddress,
                vin = header(text, "VIN:"),
                capturedAtEpochMillis = header(text, "Captured:")?.toLongOrNull()
                    ?: file.lastModified(),
                blocks = blocks,
                // Re-derive rather than trusting the comment line: if the file
                // was hand-edited the recorded strategy may no longer hold, and
                // a wrong strategy is how a restore writes bad checksums.
                checksumStrategy = ChecksumStrategy.detect(blocks),
                sourceDids = blocks.map { didForBlockId(it.blockId) },
                partNumber = header(text, "Part number:"),
                calibrationLevel = header(text, "Calibration:"),
            )
        }.getOrNull()
    }

    suspend fun delete(file: File): Boolean = withContext(Dispatchers.IO) { file.delete() }

    private fun describe(file: File): StoredSnapshot? = runCatching {
        val text = file.readText()
        val blocks = AsBuiltBlock.parseAll(text)
        if (blocks.isEmpty()) return@runCatching null

        StoredSnapshot(
            file = file,
            moduleCode = header(text, "As-Built snapshot -")
                ?.substringBefore(" (")?.trim() ?: file.nameWithoutExtension,
            moduleAddress = blocks.first().moduleAddress,
            capturedAtEpochMillis = header(text, "Captured:")?.toLongOrNull()
                ?: file.lastModified(),
            blockCount = blocks.size,
            vin = header(text, "VIN:"),
        )
    }.getOrNull()

    private fun header(text: String, prefix: String): String? = text.lineSequence()
        .firstOrNull { it.startsWith("#") && it.contains(prefix) }
        ?.substringAfter(prefix)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /**
     * Recovers the identifier a block came from.
     *
     * The exported format records Ford-style block ids rather than raw
     * identifiers, so this inverts the mapping used when reading. A block id we
     * cannot invert yields -1, and the writer skips it rather than guessing an
     * address to write to.
     */
    private fun didForBlockId(blockId: String): Int {
        val leading = blockId.substringBefore('-')
        val asConfigBlock = leading.toIntOrNull(16)
        if (asConfigBlock != null && blockId.contains('-')) {
            return com.anthonyrohde.f250scan.core.ford.AsBuiltDidMap.CONFIG_BASE_DID +
                asConfigBlock
        }
        return blockId.toIntOrNull(16) ?: -1
    }
}
