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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyrohde.truckscan.ScanViewModel
import com.anthonyrohde.truckscan.core.session.ConnectionState
import com.anthonyrohde.truckscan.core.vehicle.BatteryVoltage
import com.anthonyrohde.truckscan.transport.AdapterCatalog

@Composable
fun ConnectScreen(viewModel: ScanViewModel) {
    val adapters by viewModel.adapters.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Connection") }
        item { BusyBanner(busy) }

        item {
            when (val state = connection) {
                is ConnectionState.Connected -> ConnectedCard(state, viewModel)
                is ConnectionState.Failed -> Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Not connected", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(state.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> Explanation(
                    "Plug the adapter into the OBD port under the dash, turn the " +
                        "ignition on, then pick it below.",
                )
            }
        }

        item { SectionHeader("Available adapters") }

        items(adapters) { choice ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(choice.label, fontWeight = FontWeight.Medium)
                        Text(
                            choice.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { viewModel.connect(choice) },
                        enabled = connection !is ConnectionState.Connected,
                    ) { Text("Connect") }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { viewModel.refreshAdapters() },
                modifier = Modifier.padding(16.dp),
            ) { Text("Refresh list") }
        }

        item { SectionHeader("Which adapter you need") }
        item { Explanation(AdapterCatalog.HARDWARE_ADVICE) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ConnectedCard(state: ConnectionState.Connected, viewModel: ScanViewModel) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Connected", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            DataRow("Adapter", state.identity.model)
            DataRow("Active bus", state.activeBus.displayName)
            DataRow(
                "Multi-bus capable",
                if (state.identity.supportsMultiBus) "yes" else "no - HS-CAN1 only",
            )
            DataRow("Modules answering", if (state.busTrafficSeen) "yes" else "none")

            state.batteryVolts?.let { volts ->
                DataRow("Battery", "%.1f V".format(volts))
            }

            if (!state.busTrafficSeen) {
                Explanation(
                    "Nothing answered a request on this bus. With the key off that is " +
                        "normal. With the key on it means the modules are not there, " +
                        "or not awake.",
                )
            }

            // The warning this app owes the user. With the engine off the
            // battery is only being drawn down - by this app, and by anything
            // else left switched on, which on the occasion that prompted this
            // was the headlights. The app watched a 6.7 diesel reach 10.0 V
            // with its modules still answering and said nothing, and it did not
            // need to know the cause to have been useful.
            state.batteryVolts?.let { volts ->
                if (BatteryVoltage.classify(volts) != BatteryVoltage.State.CHARGING &&
                    BatteryVoltage.classify(volts) != BatteryVoltage.State.HEALTHY
                ) {
                    Explanation(BatteryVoltage.describe(volts))
                }
            }
            if (!state.identity.supportsMultiBus) {
                Explanation(
                    "This adapter can only reach the powertrain bus, so the body, " +
                        "cluster and SYNC modules will not be found.",
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.scanModules(full = false) }) {
                    Text("Scan modules")
                }
                OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
            }
        }
    }
}
