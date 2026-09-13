package com.anthonyrohde.truckscan.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyrohde.truckscan.ScanViewModel
import com.anthonyrohde.truckscan.core.dtc.DtcCatalog
import com.anthonyrohde.truckscan.core.session.ModuleDtcResult

@Composable
fun FaultsScreen(viewModel: ScanViewModel) {
    val scan by viewModel.faults.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var pendingClear by remember { mutableStateOf<ModuleDtcResult?>(null) }

    pendingClear?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text("Clear faults in ${target.module.module.code}?") },
            text = {
                Text(
                    "This erases the stored codes and the freeze-frame data recorded " +
                        "when they set. That data is what explains an intermittent " +
                        "fault, so record it first if you have not already. Readiness " +
                        "monitors will also reset, which an emissions test will notice.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearFaults(target.module)
                    pendingClear = null
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Fault codes") }
        item { BusyBanner(busy) }

        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { viewModel.scanFaults() }) { Text("Scan all modules") }
            }
        }

        val current = scan
        if (current == null) {
            item {
                EmptyState(
                    "No scan yet",
                    "Run a fault scan to read stored codes from every module that answered.",
                )
            }
        } else {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        DataRow("Modules read", current.results.size.toString())
                        DataRow("Faults found", current.totalFaults.toString())
                        // A module answers a status-mask read with its whole
                        // DTC table, most of it monitors that have not run.
                        // This PCM returns 344 records on a healthy truck, so
                        // the number that is not a fault has to be named or the
                        // one that is looks like a rounding error.
                        DataRow("Monitors not yet run", current.totalNotRun.toString())
                        DataRow(
                            "Unreadable modules",
                            current.unreadableModules.size.toString(),
                        )
                    }
                }
            }

            if (current.modulesWithFaults.isEmpty()) {
                item {
                    // "Clean" is only true of the modules that were actually
                    // read. Saying it while some could not be read is how a
                    // diagnostic tool tells someone their truck is fine when it
                    // has not looked.
                    if (current.unreadableModules.isEmpty()) {
                        EmptyState(
                            "No stored faults",
                            "Every module that answered reported a clean fault memory.",
                        )
                    } else {
                        EmptyState(
                            "No faults in the modules that were read",
                            "${current.unreadableModules.size} module(s) could not be " +
                                "read, so this is not a clean bill of health. Their " +
                                "reasons are listed below.",
                        )
                    }
                }
            }

            if (current.unreadableModules.isNotEmpty()) {
                items(current.unreadableModules) { result ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${result.module.module.code} - not read",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                result.error ?: "No reason recorded",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(current.modulesWithFaults) { result ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${result.module.module.code} - ${result.faults.size} fault(s)",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                result.module.bus.displayName,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        result.faults.forEach { dtc ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        dtc.displayCode,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (dtc.status.confirmed) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    if (dtc.status.confirmed) {
                                        AssistChip(onClick = {}, label = { Text("Confirmed") })
                                    } else if (dtc.status.pending) {
                                        AssistChip(onClick = {}, label = { Text("Pending") })
                                    }
                                }
                                Text(
                                    dtc.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    dtc.status.describe(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!DtcCatalog.isKnown(dtc.code)) {
                                    Explanation(
                                        "Not in the generic code catalog - most likely " +
                                            "Ford-specific. Look it up against a Ford " +
                                            "service source.",
                                        Modifier.padding(horizontal = 0.dp),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { pendingClear = result }) {
                            Text("Clear this module")
                        }
                    }
                }
            }

            if (current.unreadableModules.isNotEmpty()) {
                item { SectionHeader("Could not be read") }
                items(current.unreadableModules) { result ->
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(result.module.module.code, fontWeight = FontWeight.Medium)
                            Text(
                                result.error ?: "Unknown reason",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
