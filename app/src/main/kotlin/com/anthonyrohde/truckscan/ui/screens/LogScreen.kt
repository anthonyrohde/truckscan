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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyrohde.truckscan.ScanViewModel
import com.anthonyrohde.truckscan.data.LogSharing

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
                onClick = { viewModel.log.clear() },
                enabled = entries.isNotEmpty(),
            ) { Text("Clear") }
        }
        Explanation(
            "${entries.size} entries. Newest at the bottom. Share sends the " +
                "whole log as a text file - a screenshot only shows a few lines " +
                "of it, and the lines that explain a failure are rarely the ones " +
                "on screen.",
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
