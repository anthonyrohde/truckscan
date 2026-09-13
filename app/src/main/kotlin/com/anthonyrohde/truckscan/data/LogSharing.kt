package com.anthonyrohde.truckscan.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the adapter log to a file and hands it to the Android share sheet.
 *
 * A screenshot of a log is close to useless for diagnosis: it shows perhaps
 * twenty of two thousand lines, cropped, and with no way to search it. The
 * whole point of the log is the exchange it captures, so it has to leave the
 * phone intact.
 */
object LogSharing {

    /** Must match the authority declared for the provider in the manifest. */
    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    /**
     * Writes [log] to a shareable file and returns it.
     *
     * The file goes in the cache: it exists to be sent somewhere, and leaving
     * copies behind in a directory the user has to clean up themselves is not
     * the app's business.
     */
    fun writeToCache(context: Context, log: SessionLog): File {
        val directory = File(context.cacheDir, "logs").apply { mkdirs() }

        // Previous exports are dead weight once shared, and a stale one picked
        // by mistake from the share sheet is worse than dead weight.
        directory.listFiles()?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "truckscan-log-$stamp.txt")
        file.writeText(header(context) + "\n" + log.export() + "\n")
        return file
    }

    /**
     * Provenance for the log.
     *
     * Without it the first three questions about any log are which build it
     * came from, which phone, and which Android version - and the answers are
     * no longer to hand by the time anyone asks.
     */
    private fun header(context: Context): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} (build $code)"
        }.getOrDefault("unknown")

        return buildString {
            appendLine("Truck Scan adapter log")
            appendLine("App:     $version")
            appendLine("Device:  ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Written: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
            appendLine("-".repeat(60))
        }
    }

    /** Writes the log out and opens the share sheet. */
    fun share(context: Context, log: SessionLog) {
        val file = writeToCache(context, log)
        val uri = FileProvider.getUriForFile(context, authority(context), file)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // NEW_TASK keeps this working when the caller is not an Activity
        // context, which is not guaranteed for a Compose LocalContext.
        val chooser = Intent.createChooser(send, "Share adapter log")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
