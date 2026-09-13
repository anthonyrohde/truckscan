package com.anthonyrohde.truckscan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyrohde.truckscan.ScanViewModel
import com.anthonyrohde.truckscan.core.probe.ProbeLibrary
import com.anthonyrohde.truckscan.data.DownloadWriter
import com.anthonyrohde.truckscan.data.LogSharing

/**
 * Runs read-only command scripts against the adapter and keeps the transcript.
 *
 * The point is to replace inference with measurement. Several things in this
 * app are reasoned from logs rather than tested against hardware, because the
 * hardware is in a truck and the code is not. This is how a specific question
 * gets a specific answer.
 *
 * Nothing here can change the vehicle: every line is checked against a list of
 * services that only read, and anything else is shown as refused instead of
 * being sent. That check runs again when the script executes, so it does not
 * depend on anybody having read the preview.
 */
@Composable
fun ProbeScreen(viewModel: ScanViewModel) {
    val transcript by viewModel.probeTranscript.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var script by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("Probe") }
    var saved by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        SectionHeader("Probe")
        Explanation(
            "Runs adapter commands and records exactly what comes back. Read-only: " +
                "anything that could reset a module, erase faults or write " +
                "configuration is refused and never sent.",
        )

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(viewModel.investigations) { investigation ->
                InvestigationCard(
                    investigation = investigation,
                    onLoad = {
                        title = investigation.name
                        script = investigation.script
                        saved = null
                    },
                )
            }

            item {
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it; saved = null },
                    label = { Text("Script") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp),
                )
            }

            if (script.isNotBlank()) {
                item {
                    // What will happen, before it happens. Refusals are listed
                    // by name so nothing is a surprise.
                    Text(
                        viewModel.describeScript(script),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.runProbe(title, script) },
                        enabled = script.isNotBlank(),
                    ) { Text("Run") }

                    OutlinedButton(
                        onClick = {
                            val text = transcript ?: return@OutlinedButton
                            val name = "truckscan-probe-" +
                                title.lowercase().replace(Regex("[^a-z0-9]+"), "-") + ".txt"
                            saved = when (val r = DownloadWriter.writeText(context, name, text)) {
                                is DownloadWriter.Result.Written ->
                                    "Written to ${r.location}: ${r.displayName}"
                                is DownloadWriter.Result.Failed -> "Could not write it: ${r.reason}"
                            }
                        },
                        enabled = transcript != null,
                    ) { Text("Save result") }

                    OutlinedButton(
                        onClick = { viewModel.clearProbeTranscript(); saved = null },
                        enabled = transcript != null,
                    ) { Text("Clear") }
                }
            }

            saved?.let { item { Explanation(it) } }

            transcript?.let { text ->
                item {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InvestigationCard(
    investigation: ProbeLibrary.Investigation,
    onLoad: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(investigation.name, style = MaterialTheme.typography.titleSmall)
            Text(
                investigation.question,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            OutlinedButton(onClick = onLoad) { Text("Load") }
        }
    }
}
