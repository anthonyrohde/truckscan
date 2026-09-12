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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.anthonyrohde.truckscan.core.util.Hex

/**
 * As-Built configuration, read-only.
 *
 * The app reads a module's configuration, shows it in Ford's own notation and
 * saves it as a portable backup. It does not write.
 *
 * That is a deliberate limit, not an unfinished one. Writing to a module is
 * gated behind UDS security access, whose key derivation is Ford proprietary;
 * on a 2022 vehicle no locally computed key will be accepted. A write button
 * that always fails at the same wall is worse than no button, because it
 * implies the capability exists and invites someone to go looking for a way
 * round it. Use FORScan for changes - and take a backup here first.
 */
@Composable
fun AsBuiltScreen(viewModel: ScanViewModel) {
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val active by viewModel.activeSnapshot.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("As-Built") }
        item { BusyBanner(busy) }

        item {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Read and back up", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This reads a module's configuration bytes and saves them as a " +
                            "Ford-format text file you can copy off the phone. It does " +
                            "not write.\n\n" +
                            "Writing is gated behind the module's security access, and " +
                            "the key derivation for a 2022 vehicle is Ford's - no locally " +
                            "computed key will be accepted. Make changes in FORScan, and " +
                            "take a backup here before you do. A backup is only worth " +
                            "having if it exists before the change, not after.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item { SectionHeader("Back up a module") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    enabled = modules.isNotEmpty(),
                ) {
                    Text(
                        if (modules.isEmpty()) {
                            "Scan for modules first"
                        } else {
                            "Choose a module to read"
                        },
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    modules.forEach { module ->
                        DropdownMenuItem(
                            text = { Text("${module.module.code} - ${module.module.name}") },
                            onClick = {
                                menuOpen = false
                                viewModel.backupAsBuilt(module)
                            },
                        )
                    }
                }
            }
        }

        item { SectionHeader("Saved backups") }
        if (snapshots.isEmpty()) {
            item {
                EmptyState(
                    "No backups yet",
                    "Read a module above. Backups are plain text files - share them to " +
                        "email or cloud storage so they survive this phone.",
                )
            }
        }
        items(snapshots) { stored ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${stored.moduleCode} - ${stored.blockCount} block(s)",
                        fontWeight = FontWeight.SemiBold,
                    )
                    DataRow("Captured", stored.displayDate)
                    stored.vin?.let { DataRow("VIN", it) }
                    DataRow("File", stored.file.name)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.loadSnapshot(stored) }) {
                            Text("Open")
                        }
                        OutlinedButton(onClick = { viewModel.deleteSnapshot(stored) }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }

        val snapshot = active
        if (snapshot != null) {
            item { SectionHeader("${snapshot.moduleCode} configuration") }
            item {
                Column {
                    snapshot.partNumber?.let { DataRow("Part number", it) }
                    snapshot.calibrationLevel?.let { DataRow("Calibration", it) }
                    DataRow(
                        "Checksum",
                        snapshot.checksumStrategy?.label ?: "could not be determined",
                    )
                    if (snapshot.checksumStrategy == null) {
                        Explanation(
                            "No candidate algorithm reproduces this module's existing " +
                                "checksums. The backup is still a faithful copy of what " +
                                "the module returned - the algorithm only matters when " +
                                "writing, which this app does not do.",
                        )
                    }
                }
            }

            items(snapshot.blocks.indices.toList()) { index ->
                val block = snapshot.blocks[index]
                val did = snapshot.sourceDids.getOrNull(index) ?: -1
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            block.format(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (did >= 0) "DID ${Hex.encode(did, 4)}" else "identifier unknown",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
