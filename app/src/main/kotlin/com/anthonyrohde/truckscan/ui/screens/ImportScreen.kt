package com.anthonyrohde.truckscan.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyrohde.truckscan.ScanViewModel
import com.anthonyrohde.truckscan.core.util.Hex

/**
 * Imports a bus capture from another tool and learns from it.
 *
 * This screen exists to close the gap between what the app can work out for
 * itself and what only a working tool knows. Three things in the diagnostic
 * stack are otherwise guesses - which identifiers hold configuration blocks,
 * which checksum algorithm the modules use, and which routine identifiers are
 * real. All three are plainly visible in a recording of a tool that already has
 * the answers, so importing one turns guesses into measurements.
 */
@Composable
fun ImportScreen(viewModel: ScanViewModel) {
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val active by viewModel.activeProfile.collectAsStateWithLifecycle()
    val report by viewModel.lastImportReport.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        // Read on the caller's thread only to get the bytes across the content
        // provider boundary; all analysis happens in the view model.
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "capture"
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        if (text.isNullOrBlank()) {
            viewModel.importTrace("", name)
        } else {
            viewModel.importTrace(text, name)
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Learn from a capture") }
        item { BusyBanner(busy) }

        item {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("What this does", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Import a CAN bus log from a tool that already knows your " +
                            "modules - FORScan with tracing enabled, or any candump-style " +
                            "capture. The app reconstructs the diagnostic conversation and " +
                            "learns:\n\n" +
                            "  - which identifiers actually hold As-Built blocks, so reads " +
                            "go straight to them instead of sweeping hundreds of candidates\n" +
                            "  - which checksum algorithm your modules use, confirmed " +
                            "against real blocks\n" +
                            "  - which RoutineControl identifiers are real, learned by " +
                            "observation rather than by probing a live vehicle\n" +
                            "  - any security handshake in the capture\n\n" +
                            "Nothing is sent to the vehicle. This is only reading a file.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    // Logs are text, but pickers are inconsistent about the
                    // type they report for an unusual extension, so accept all.
                    picker.launch(arrayOf("*/*"))
                }) { Text("Choose a log file") }
            }
        }

        item {
            Explanation(
                "In FORScan for Windows: Settings, then enable the debug or trace log, " +
                    "run a module configuration read, and the log lands in FORScan's " +
                    "logs folder. Copy it to the phone and import it here.",
            )
        }

        report?.let { parseReport ->
            item { SectionHeader("Last import") }
            item {
                Column {
                    DataRow("Lines read", parseReport.totalLines.toString())
                    DataRow("Frames recognised", parseReport.framesParsed.toString())
                    DataRow("Lines skipped", parseReport.linesSkipped.toString())
                }
            }
            if (parseReport.isEmpty && parseReport.sampleSkippedLines.isNotEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "No frames recognised",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "The parser handles the common log shapes but not every " +
                                    "one. These are the first lines it could not read - " +
                                    "if they do contain frame data, the format needs " +
                                    "adding to the parser.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(6.dp))
                            parseReport.sampleSkippedLines.forEach {
                                Text(
                                    it,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        item { SectionHeader("Active profile") }
        item {
            val current = active
            if (current == null) {
                Explanation(
                    "None. As-Built reads will sweep candidate identifier ranges, which " +
                        "works but is slow and depends on the 0xDE00 convention being " +
                        "right for your modules.",
                )
            } else {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(current.sourceDescription, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            current.summarise(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.clearActiveProfile() }) {
                            Text("Stop using this profile")
                        }
                    }
                }
            }
        }

        item { SectionHeader("Learned modules") }
        val current = active
        if (current != null) {
            items(current.modules.filter { it.hasAnything }) { module ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Module ${module.addressLabel}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        DataRow(
                            "Config identifiers",
                            module.configurationDids.size.toString(),
                        )
                        DataRow(
                            "Identification",
                            module.identificationDids.size.toString(),
                        )
                        DataRow(
                            "Checksum",
                            module.checksumStrategy?.label ?: "not determined",
                        )
                        if (module.routineIds.isNotEmpty()) {
                            DataRow(
                                "Routines",
                                module.routineIds.sorted()
                                    .joinToString(", ") { Hex.encode(it, 4) },
                            )
                        }
                        if (module.seedKeyObservations.isNotEmpty()) {
                            DataRow(
                                "Handshakes captured",
                                module.seedKeyObservations.size.toString(),
                            )
                            Explanation(
                                "A captured handshake only unlocks the module if it " +
                                    "issues the same seed again. Most current modules " +
                                    "randomise it, so do not count on this.",
                            )
                        }
                    }
                }
            }
        }

        item { SectionHeader("Saved profiles") }
        if (profiles.isEmpty()) {
            item { EmptyState("No profiles yet", "Import a capture above.") }
        }
        items(profiles) { stored ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stored.sourceDescription, fontWeight = FontWeight.Medium)
                    DataRow("Modules", stored.moduleCount.toString())
                    DataRow("Imported", stored.displayDate)
                    DataRow("File", stored.file.name)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.activateProfile(stored) }) {
                            Text("Use this")
                        }
                        OutlinedButton(onClick = { viewModel.deleteProfile(stored) }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
