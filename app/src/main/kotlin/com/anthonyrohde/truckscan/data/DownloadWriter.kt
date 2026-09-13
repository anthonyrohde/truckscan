package com.anthonyrohde.truckscan.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Writes a text file into the phone's Downloads folder.
 *
 * Downloads rather than a folder of the app's own, because the point is to get
 * the file to somebody else. A file under Android/data is invisible to most
 * file managers and disappears when the app is uninstalled; Downloads is the
 * one place every phone shows and every share sheet can reach.
 *
 * No storage permission is involved on Android 10 and later - MediaStore grants
 * an app write access to its own Downloads entries. Older versions have no such
 * route without a runtime permission, so they get an error explaining to use
 * Save instead, which opens the system picker.
 */
object DownloadWriter {

    sealed interface Result {
        data class Written(val displayName: String, val location: String) : Result
        data class Failed(val reason: String) : Result
    }

    fun writeText(context: Context, fileName: String, content: String): Result =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            viaMediaStore(context, fileName, content)
        } else {
            viaLegacyPath(fileName, content)
        }

    private fun viaMediaStore(context: Context, fileName: String, content: String): Result {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Marked pending until the bytes are all there, so nothing can read
            // a half-written report and take it for the whole story.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Result.Failed("Android would not create a file in Downloads.")

        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                ?: error("could not open the file for writing")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Result.Written(fileName, "Downloads")
        }.getOrElse { error ->
            // Leave nothing half-written behind.
            runCatching { resolver.delete(uri, null, null) }
            Result.Failed(error.message ?: "could not write the file")
        }
    }

    @Suppress("DEPRECATION")
    private fun viaLegacyPath(fileName: String, content: String): Result = runCatching {
        val directory =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!directory.exists() && !directory.mkdirs()) {
            return Result.Failed("Downloads folder is not reachable on this Android version.")
        }
        File(directory, fileName).writeText(content)
        Result.Written(fileName, directory.absolutePath)
    }.getOrElse {
        Result.Failed(
            "This Android version needs permission to write to Downloads. " +
                "Use Save instead and pick a folder.",
        )
    }
}
