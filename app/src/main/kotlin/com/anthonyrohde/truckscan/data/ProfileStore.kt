package com.anthonyrohde.truckscan.data

import android.content.Context
import com.anthonyrohde.truckscan.core.trace.LearnedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A stored profile, as listed in the import screen. */
data class StoredProfile(
    val file: File,
    val sourceDescription: String,
    val moduleCount: Int,
    val capturedAtEpochMillis: Long,
) {
    val displayDate: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .format(Date(capturedAtEpochMillis))
}

/**
 * Stores learned profiles alongside the As-Built backups.
 *
 * Same reasoning as [SnapshotStore]: plain text files, so a profile learned
 * from one capture can be shared, diffed, hand-corrected, or handed to someone
 * else with the same truck. A profile is generally useful beyond one phone -
 * the identifier map for a 2022 Super Duty BCM is the same on every 2022 Super
 * Duty BCM - so keeping it portable matters.
 */
class ProfileStore(context: Context) {

    private val directory: File = File(context.filesDir, "profiles").apply { mkdirs() }

    val profileDirectory: File get() = directory

    /** The profile the app should apply, if one has been marked active. */
    private val activeMarker = File(directory, "active.txt")

    suspend fun save(profile: LearnedProfile): File = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date(profile.capturedAtEpochMillis))
        val file = File(directory, "profile-$stamp.fsp")
        file.writeText(profile.serialise())
        file
    }

    suspend fun list(): List<StoredProfile> = withContext(Dispatchers.IO) {
        directory.listFiles { f -> f.extension == "fsp" }
            ?.mapNotNull { file ->
                LearnedProfile.deserialise(file.readText())?.let { profile ->
                    StoredProfile(
                        file = file,
                        sourceDescription = profile.sourceDescription,
                        moduleCount = profile.modules.size,
                        capturedAtEpochMillis = profile.capturedAtEpochMillis,
                    )
                }
            }
            ?.sortedByDescending { it.capturedAtEpochMillis }
            ?: emptyList()
    }

    suspend fun load(file: File): LearnedProfile? = withContext(Dispatchers.IO) {
        runCatching { LearnedProfile.deserialise(file.readText()) }.getOrNull()
    }

    suspend fun delete(file: File): Boolean = withContext(Dispatchers.IO) {
        if (activeMarker.exists() && activeMarker.readText().trim() == file.name) {
            activeMarker.delete()
        }
        file.delete()
    }

    suspend fun setActive(file: File?) = withContext(Dispatchers.IO) {
        if (file == null) activeMarker.delete() else activeMarker.writeText(file.name)
        Unit
    }

    suspend fun activeFileName(): String? = withContext(Dispatchers.IO) {
        activeMarker.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
    }

    /** Loads whichever profile was last marked active, so it survives a restart. */
    suspend fun loadActive(): LearnedProfile? = withContext(Dispatchers.IO) {
        val name = activeFileName() ?: return@withContext null
        val file = File(directory, name)
        if (file.exists()) load(file) else null
    }
}
