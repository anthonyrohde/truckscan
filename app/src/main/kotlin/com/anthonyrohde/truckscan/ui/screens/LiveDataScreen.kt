package com.anthonyrohde.truckscan.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyrohde.truckscan.ScanViewModel
import com.anthonyrohde.truckscan.core.pid.Pid
import com.anthonyrohde.truckscan.core.pid.PidCatalog
import com.anthonyrohde.truckscan.core.pid.PidValue
import com.anthonyrohde.truckscan.core.pid.ZoneSeverity
import com.anthonyrohde.truckscan.ui.gauges.Gauge
import com.anthonyrohde.truckscan.ui.gauges.StatusPalette

/**
 * The dash screen.
 *
 * Built for a phone mounted by the centre console rather than held in the
 * hand: large numerals, a fixed two-column grid so gauges stay where the eye
 * learned to find them, and warnings promoted to the top of the screen instead
 * of being something you have to go looking for.
 */
@Composable
fun LiveDataScreen(viewModel: ScanViewModel) {
    val sample by viewModel.liveSample.collectAsStateWithLifecycle()
    val available by viewModel.availablePids.collectAsStateWithLifecycle()
    val selected by viewModel.selectedPids.collectAsStateWithLifecycle()
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()

    var showPicker by remember { mutableStateOf(false) }
    val streaming = sample != null

    KeepScreenOn(enabled = streaming)

    val watched = available.filter { it.key in selected }

    if (showPicker) {
        ParameterPicker(
            available = available,
            selected = selected,
            sweepMillis = sample?.sweepMillis,
            onToggle = viewModel::togglePid,
            onDefaults = { viewModel.selectOnly(PidCatalog.DEFAULT_SELECTION) },
            onSelectAll = { viewModel.selectOnly(available.map { it.key }) },
            onDismiss = { showPicker = false },
        )
    }

    LazyVerticalGrid(
        // Adaptive rather than a fixed column count: this screen is used on a
        // phone in portrait, on a phone mounted sideways by the console, and on
        // a tablet. A fixed two columns would blow the gauges up to absurd size
        // on the tablet and squeeze them on the phone. Sizing by minimum width
        // lets the same layout give two columns on a phone and five or six on a
        // tablet without a second layout to maintain.
        columns = GridCells.Adaptive(minSize = 165.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { viewModel.startLiveData() }) { Text("Start") }
                OutlinedButton(onClick = { viewModel.stopLiveData() }) { Text("Stop") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showPicker = true }) {
                    Text("Choose (${watched.size})")
                }
            }
        }

        // Warnings first. On a dash-mounted screen the top of the display is
        // the only part reliably seen at a glance.
        item(span = { GridItemSpan(maxLineSpan) }) {
            WarningPanel(alerts = alerts, streaming = streaming)
        }

        if (watched.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    "Nothing selected",
                    "Tap Choose and pick the parameters you want on the dash.",
                )
            }
        }

        items(watched, key = { it.key }, span = { pid ->
            // Counters read as rows, not dials, so they take the full width.
            if (pid.style == com.anthonyrohde.truckscan.core.pid.GaugeStyle.BAR) {
                GridItemSpan(maxLineSpan)
            } else {
                GridItemSpan(1)
            }
        }) { pid ->
            GaugeCard(pid = pid, value = sample?.values?.get(pid.key))
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * The warning lights.
 *
 * Shows every reading currently outside its normal band, worst first, each as
 * colour *plus* an icon *plus* the words for the state and what is wrong. A
 * driver glancing across should not have to decode a hue, and a colour-blind
 * one should lose nothing at all.
 */
@Composable
private fun WarningPanel(alerts: List<PidValue>, streaming: Boolean) {
    if (!streaming) {
        return
    }

    if (alerts.isEmpty()) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = StatusPalette.good,
                )
                Text(
                    "All monitored values normal",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        alerts.forEach { reading ->
            val severity = reading.severity
            val tint = StatusPalette.color(severity)

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = tint.copy(alpha = 0.16f),
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        StatusPalette.icon(severity),
                        contentDescription = StatusPalette.word(severity),
                        tint = tint,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${StatusPalette.word(severity)} - ${reading.pid.name}",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        reading.zone?.let {
                            Text(
                                it.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        reading.formatted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun GaugeCard(pid: Pid, value: PidValue?) {
    val severity = value?.severity ?: ZoneSeverity.NORMAL
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (severity == ZoneSeverity.NORMAL) {
                MaterialTheme.colorScheme.surface
            } else {
                StatusPalette.color(severity).copy(alpha = 0.12f)
            },
        ),
    ) {
        Box(Modifier.padding(8.dp)) {
            Gauge(pid = pid, value = value, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Holds the screen awake while data is streaming.
 *
 * A dash gauge that blanks after thirty seconds is not a gauge. The flag is
 * released as soon as polling stops, so the app does not sit burning the
 * battery once it is back in a pocket.
 */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled, context) {
        val window = (context as? Activity)?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}


/**
 * Parameter selection, as a dialog rather than a section under the gauges.
 *
 * Forty-five parameters appended below the dashboard would mean scrolling past
 * every gauge to reach the list, and scrolling back to see the result. A dialog
 * keeps the dashboard where it is and scrolls its own list, which also means
 * the gauge grid never has to compete with a second scrolling region.
 */
@Composable
private fun ParameterPicker(
    available: List<Pid>,
    selected: Set<String>,
    sweepMillis: Long?,
    onToggle: (String) -> Unit,
    onDefaults: () -> Unit,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parameters (${selected.size} selected)") },
        text = {
            Column {
                Text(
                    "Each PID is one request, so fewer parameters refresh faster. " +
                        "Parameters carrying several sensors - the exhaust gas " +
                        "temperatures - cost a single request between them." +
                        (sweepMillis?.let { "\nLast sweep: $it ms." } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDefaults) { Text("Default set") }
                    TextButton(onClick = onSelectAll) { Text("All") }
                }
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(available, key = { it.key }) { pid ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(pid.key) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(
                                checked = pid.key in selected,
                                onCheckedChange = { onToggle(pid.key) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(pid.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "PID ${pid.hexId}" +
                                        if (pid.unit.isNotEmpty()) "  ${pid.unit}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
