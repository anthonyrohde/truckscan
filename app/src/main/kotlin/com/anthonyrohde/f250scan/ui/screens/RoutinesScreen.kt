package com.anthonyrohde.f250scan.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyrohde.f250scan.ScanViewModel
import com.anthonyrohde.f250scan.core.session.DiscoveredModule
import com.anthonyrohde.f250scan.core.session.ServiceRoutine

@Composable
fun RoutinesScreen(viewModel: ScanViewModel) {
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var target by remember { mutableStateOf<DiscoveredModule?>(null) }
    var pending by remember { mutableStateOf<ServiceRoutine?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val selectedModule = target ?: modules.firstOrNull()

    pending?.let { routine ->
        val module = selectedModule
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(routine.name) },
            text = {
                Column {
                    Text(routine.description)
                    if (routine.preconditions.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Before continuing:", fontWeight = FontWeight.SemiBold)
                        routine.preconditions.forEach { Text("  - $it") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Target: ${module?.module?.code ?: "no module selected"}",
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = module != null,
                    onClick = {
                        module?.let { viewModel.runRoutine(routine, it) }
                        pending = null
                    },
                ) { Text("Run") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Service functions") }
        item { BusyBanner(busy) }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text("Target: ${selectedModule?.module?.code ?: "select a module"}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    modules.forEach { module ->
                        DropdownMenuItem(
                            text = { Text("${module.module.code} - ${module.module.name}") },
                            onClick = {
                                target = module
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }

        item { SectionHeader("Standard operations") }
        item {
            Explanation(
                "These four are defined by the UDS standard rather than by Ford, so " +
                    "they work on any module that answers.",
            )
        }

        items(viewModel.standardRoutines) { routine ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(routine.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        routine.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(routine.risk.label) },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = selectedModule != null,
                        onClick = { pending = routine },
                    ) { Text("Run") }
                }
            }
        }

        item { SectionHeader("Ford-specific routines") }
        item {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Not included, deliberately", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Injector cutout tests, forced DPF regeneration, KAM reset, " +
                            "brake bleed and steering calibration are invoked through " +
                            "identifiers Ford does not publish.\n\n" +
                            "This app will not guess at them. Probing identifiers to " +
                            "see what responds means commanding unknown functions on a " +
                            "live vehicle - the same identifier space energises " +
                            "injectors, cycles ABS valves and can start a regeneration " +
                            "that puts the exhaust over 600 degrees.\n\n" +
                            "The plumbing to run them is implemented and tested. Supply " +
                            "an identifier from a source you trust and it will run with " +
                            "the same session handling and error reporting as everything " +
                            "else.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
