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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import com.anthonyrohde.f250scan.ScanViewModel
import com.anthonyrohde.f250scan.core.pid.Pid

@Composable
fun LiveDataScreen(viewModel: ScanViewModel) {
    val sample by viewModel.liveSample.collectAsStateWithLifecycle()
    val available by viewModel.availablePids.collectAsStateWithLifecycle()
    val selected by viewModel.selectedPids.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Live data") }

        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { viewModel.startLiveData() }) { Text("Start") }
                OutlinedButton(onClick = { viewModel.stopLiveData() }) { Text("Stop") }
            }
        }

        item {
            Explanation(
                "Each parameter is a separate request, so the update rate falls as you " +
                    "add more. Six parameters refresh several times a second; twenty " +
                    "refresh about twice a second. Watch fewer things to watch them " +
                    "faster.",
            )
        }

        // Current values first: this is what someone is actually looking at.
        val current = sample
        if (current != null) {
            item { SectionHeader("Values") }
            items(available.filter { it.id in selected }) { pid ->
                val value = current.values[pid.id]
                GaugeRow(pid, value?.formatted, value?.let { pid.normalise(it.value) })
            }
            if (current.failedPids.isNotEmpty()) {
                item {
                    Explanation(
                        "${current.failedPids.size} parameter(s) did not answer this " +
                            "sweep. The PCM may not support them on this engine.",
                    )
                }
            }
        }

        item { SectionHeader("Parameters to watch") }
        item {
            Explanation(
                "This list is filtered to what the vehicle reported as supported. " +
                    "Ford-specific diesel values such as individual EGT sensors, DPF " +
                    "soot load and injector balance rates are not standard OBD-II " +
                    "parameters and do not appear here.",
            )
        }

        items(available) { pid ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                FilterChip(
                    selected = pid.id in selected,
                    onClick = { viewModel.togglePid(pid.id) },
                    label = { Text("${pid.name} (${pid.hexId})") },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun GaugeRow(pid: Pid, formatted: String?, normalised: Double?) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(pid.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatted ?: "--",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (normalised != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { normalised.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
