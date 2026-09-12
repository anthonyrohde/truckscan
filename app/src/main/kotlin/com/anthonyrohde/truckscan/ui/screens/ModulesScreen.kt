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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyrohde.truckscan.ScanViewModel
import com.anthonyrohde.truckscan.core.ford.ModuleCategory
import com.anthonyrohde.truckscan.core.session.DiscoveredModule

@Composable
fun ModulesScreen(viewModel: ScanViewModel) {
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Modules") }
        item { BusyBanner(busy) }

        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { viewModel.scanModules(full = false) }) { Text("Quick scan") }
                OutlinedButton(onClick = { viewModel.scanModules(full = true) }) {
                    Text("Full sweep")
                }
            }
        }

        item {
            Explanation(
                "A quick scan probes the addresses known for a 2022 Super Duty and " +
                    "takes a few seconds. A full sweep tries every diagnostic address " +
                    "on every reachable bus - slower, but it will find a module this " +
                    "app does not have a name for.",
            )
        }

        if (modules.isEmpty()) {
            item {
                EmptyState(
                    "No modules found yet",
                    "Connect an adapter, turn the ignition on, then run a scan.",
                )
            }
        }

        // Flattened into headers and rows so the list reads like the vehicle
        // rather than like an address table, while keeping a single items() call.
        items(rows(modules)) { row ->
            when (row) {
                is ModuleRow.Header -> SectionHeader(row.label)
                is ModuleRow.Entry -> ModuleCard(row.module, viewModel)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** One line of the module list: either a category header or a module. */
private sealed interface ModuleRow {
    data class Header(val label: String) : ModuleRow
    data class Entry(val module: DiscoveredModule) : ModuleRow
}

/** Groups modules by category, in the declared category order. */
private fun rows(modules: List<DiscoveredModule>): List<ModuleRow> {
    val grouped = modules.groupBy { it.module.category }
    return buildList {
        for (category in ModuleCategory.entries) {
            val inCategory = grouped[category].orEmpty()
            if (inCategory.isEmpty()) continue
            add(ModuleRow.Header(category.label))
            inCategory.forEach { add(ModuleRow.Entry(it)) }
        }
    }
}

@Composable
private fun ModuleCard(module: DiscoveredModule, viewModel: ScanViewModel) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(module.module.code, fontWeight = FontWeight.SemiBold)
                    Text(
                        module.module.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    module.module.addressLabel,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = { Text(module.bus.displayName) })
                if (module.module.safetyCritical) {
                    AssistChip(onClick = {}, label = { Text("Safety critical") })
                }
                if (!module.isKnown) {
                    AssistChip(onClick = {}, label = { Text("Unrecognised") })
                }
            }

            module.identification?.let { id ->
                Spacer(Modifier.height(6.dp))
                id.partNumber?.let { DataRow("Part number", it) }
                id.calibrationLevel?.let { DataRow("Calibration", it) }
            }

            if (module.module.description.isNotBlank()) {
                Explanation(module.module.description)
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { viewModel.backupAsBuilt(module) }) {
                Text("Back up configuration")
            }
        }
    }
}
