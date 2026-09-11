package com.anthonyrohde.f250scan.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyrohde.f250scan.ScanViewModel
import com.anthonyrohde.f250scan.core.ford.AsBuiltBlock
import com.anthonyrohde.f250scan.core.session.BlockChange
import com.anthonyrohde.f250scan.core.util.Hex
import kotlinx.coroutines.launch

@Composable
fun AsBuiltScreen(viewModel: ScanViewModel) {
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val active by viewModel.activeSnapshot.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Int, AsBuiltBlock>?>(null) }

    editing?.let { (did, block) ->
        EditBlockDialog(
            did = did,
            block = block,
            viewModel = viewModel,
            onDismiss = { editing = null },
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Module configuration (As-Built)") }
        item { BusyBanner(busy) }

        item {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Read this first", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This app can read your modules' configuration bytes, back them " +
                            "up, and write them back. It cannot tell you what the bytes " +
                            "mean - that is Ford's proprietary data and it is not in " +
                            "here. Get the factory As-Built for your VIN and work from " +
                            "that.\n\n" +
                            "Writes are gated behind a module's security access. On a " +
                            "2022 truck the key derivation is not publicly known, so " +
                            "expect writes to be refused with an explanation. Reading " +
                            "and backing up work regardless, and are worth doing now " +
                            "rather than after something goes wrong.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item { SectionHeader("Back up a module") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text("Choose a module to read")
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
                    "Read a module above. Backups are saved as Ford-format text files " +
                        "you can share off the phone - which is the point of having them.",
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
                                "checksums, so a new one cannot be computed safely and " +
                                "writes will be refused. The backup itself is still good.",
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
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (did >= 0) {
                                    "DID ${Hex.encode(did, 4)}"
                                } else {
                                    "identifier unknown"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (did >= 0 && snapshot.checksumStrategy != null) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { editing = did to block }) {
                                Text("Edit and write")
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * Edits one block's bytes and writes them.
 *
 * The dialog validates before offering to write, so a refusal (no backup, low
 * voltage, unknown checksum, security access) is explained before the user
 * commits rather than after.
 */
@Composable
private fun EditBlockDialog(
    did: Int,
    block: AsBuiltBlock,
    viewModel: ScanViewModel,
    onDismiss: () -> Unit,
) {
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var hex by remember { mutableStateOf(Hex.encode(block.data, " ")) }
    var blocker by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }

    val parsed = Hex.decodeOrNull(hex)
    val module = modules.firstOrNull { it.module.requestId == block.moduleAddress }

    val change = parsed
        ?.takeIf { it.size == block.data.size }
        ?.let { BlockChange(did, block, block.copy(data = it)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit block ${block.blockId}") },
        text = {
            Column {
                OutlinedTextField(
                    value = hex,
                    onValueChange = {
                        hex = it
                        checked = false
                        blocker = null
                    },
                    label = { Text("Configuration bytes (hex)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                when {
                    parsed == null ->
                        Text(
                            "Not valid hex.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    parsed.size != block.data.size ->
                        Text(
                            "This block is ${block.data.size} bytes; you have entered " +
                                "${parsed.size}. The length must match exactly.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    change != null && change.isNoOp ->
                        Text(
                            "Unchanged.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    change != null ->
                        Text(
                            change.describe(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                }

                blocker?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "The checksum is recomputed automatically. The module is read back " +
                        "after writing and the original restored if it does not match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (!checked) {
                TextButton(
                    enabled = change != null && !change.isNoOp && module != null,
                    onClick = {
                        scope.launch {
                            val target = module ?: return@launch
                            val reason = viewModel.validateWrite(target, listOf(change!!))
                            blocker = reason
                            checked = reason == null
                        }
                    },
                ) { Text("Check") }
            } else {
                TextButton(onClick = {
                    module?.let { viewModel.writeAsBuilt(it, listOf(change!!)) }
                    onDismiss()
                }) { Text("Write to module") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
