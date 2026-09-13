package com.anthonyrohde.truckscan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyrohde.truckscan.ScanViewModel
import com.anthonyrohde.truckscan.data.LogSharing
import com.anthonyrohde.truckscan.data.DownloadWriter

/**
 * Raw adapter traffic.
 *
 * Worth a whole screen: when a module refuses to answer, the exchange itself is
 * the only evidence of why, and it is what anyone helping will ask to see.
 */
@Composable
fun LogScreen(viewModel: ScanViewModel) {
    val entries by viewModel.log.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var diagnosticResult by remember { mutableStateOf<String?>(null) }

    // The system file picker rather than a fixed directory: it needs no
    // storage permission at any API level, and it puts the file where the
    // user will actually look for it instead of where the app guessed.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(
                        LogSharing.exportText(context, viewModel.log).toByteArray(),
                    )
                }
            }
        }
    }
    val listState = rememberLazyListState()

    // Follow the tail as new traffic arrives.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        SectionHeader("Adapter log")
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { LogSharing.share(context, viewModel.log) },
                enabled = entries.isNotEmpty(),
            ) { Text("Share") }
            OutlinedButton(
                onClick = { saveLauncher.launch(LogSharing.suggestedFileName()) },
                enabled = entries.isNotEmpty(),
            ) { Text("Save") }
            Button(
                onClick = {
                    val (version, device, android) = LogSharing.deviceFacts(context)
                    val report = viewModel.buildDiagnosticReport(version, device, android)
                    diagnosticResult = when (val r = LogSharing.writeDiagnostic(context, report)) {
                        is DownloadWriter.Result.Written ->
                            "Written to ${r.location}: ${r.displayName}"
                        is DownloadWriter.Result.Failed ->
                            "Could not write it: ${r.reason}"
                    }
                },
            ) { Text("Diagnostic") }
            OutlinedButton(
                onClick = { viewModel.log.clear() },
                enabled = entries.isNotEmpty(),
            ) { Text("Clear") }
        }
        diagnosticResult?.let { Explanation(it) }

        Explanation(
            "Diagnostic writes a report to Downloads: which modules answered, " +
                "which parameters report and how often, sweep timing, and the " +
                "end of this log. That is the file to send when something is " +
                "not working.",
        )
        Explanation(
            "${entries.size} entries. Newest at the bottom. Share sends the whole " +
                "log to another app; Save writes it to a folder you choose. Either " +
                "beats a screenshot, which shows a few lines of it and rarely the " +
                "ones that explain a failure.",
        )

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
            items(entries) { entry ->
                Text(
                    "${entry.time}  ${entry.message}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
