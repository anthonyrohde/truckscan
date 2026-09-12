package com.anthonyrohde.truckscan

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.anthonyrohde.truckscan.ui.screens.AsBuiltScreen
import com.anthonyrohde.truckscan.ui.screens.ConnectScreen
import com.anthonyrohde.truckscan.ui.screens.Explanation
import com.anthonyrohde.truckscan.ui.screens.FaultsScreen
import com.anthonyrohde.truckscan.ui.screens.ImportScreen
import com.anthonyrohde.truckscan.ui.screens.LiveDataScreen
import com.anthonyrohde.truckscan.ui.screens.LogScreen
import com.anthonyrohde.truckscan.ui.screens.ModulesScreen
import com.anthonyrohde.truckscan.ui.screens.RoutinesScreen
import com.anthonyrohde.truckscan.ui.screens.SectionHeader
import com.anthonyrohde.truckscan.ui.theme.TruckScanTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TruckScanTheme {
                AppRoot()
            }
        }
    }
}

private const val ROUTE_MORE = "more"

/**
 * Screens in the bottom bar.
 *
 * Held to five. Material's navigation bar crowds badly beyond that, and these
 * five are the ones used in a normal session; the rest live behind [ROUTE_MORE].
 */
private enum class Primary(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    CONNECT("connect", "Connect", Icons.Filled.Link),
    MODULES("modules", "Modules", Icons.Filled.Memory),
    FAULTS("faults", "Faults", Icons.Filled.Warning),
    LIVE("live", "Live", Icons.Filled.Speed),
    MORE(ROUTE_MORE, "More", Icons.Filled.MoreHoriz),
}

/** Screens reached from the More list. */
private enum class Secondary(
    val route: String,
    val label: String,
    val detail: String,
    val icon: ImageVector,
) {
    AS_BUILT(
        "asbuilt", "Module configuration",
        "Read, back up and write As-Built data",
        Icons.Filled.Settings,
    ),
    ROUTINES(
        "routines", "Service functions",
        "Module reset, fault logging, clearing codes",
        Icons.Filled.Build,
    ),
    IMPORT(
        "import", "Learn from a capture",
        "Import a bus log to learn real identifiers and checksums",
        Icons.Filled.FileOpen,
    ),
    LOG(
        "log", "Adapter log",
        "Raw traffic to and from the adapter",
        Icons.Filled.Terminal,
    ),
}

@Composable
private fun AppRoot() {
    val viewModel: ScanViewModel = viewModel()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val message by viewModel.message.collectAsStateWithLifecycle()

    // Paired adapters cannot even be listed without Bluetooth permission, so
    // ask on first launch rather than showing an empty list.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshAdapters() }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !viewModel.adapterCatalog.hasBluetoothPermission()
        ) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ),
            )
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Primary.entries.forEach { destination ->
                    // Keep "More" highlighted while on one of its children, so
                    // the bar does not look unselected on those screens.
                    val selected = currentRoute == destination.route ||
                        (
                            destination == Primary.MORE &&
                                Secondary.entries.any { it.route == currentRoute }
                            )

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != destination.route) {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        // NavHost fills the scaffold body directly rather than sitting inside a
        // Column: nested in one it can be measured to wrap its content, which
        // leaves a lazy grid inside it with no bounded height and therefore
        // nothing to scroll.
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController, startDestination = Primary.CONNECT.route) {
                composable(Primary.CONNECT.route) { ConnectScreen(viewModel) }
                composable(Primary.MODULES.route) { ModulesScreen(viewModel) }
                composable(Primary.FAULTS.route) { FaultsScreen(viewModel) }
                composable(Primary.LIVE.route) { LiveDataScreen(viewModel) }
                composable(ROUTE_MORE) { MoreScreen(navController) }

                composable(Secondary.AS_BUILT.route) { AsBuiltScreen(viewModel) }
                composable(Secondary.ROUTINES.route) { RoutinesScreen(viewModel) }
                composable(Secondary.IMPORT.route) { ImportScreen(viewModel) }
                composable(Secondary.LOG.route) { LogScreen(viewModel) }
            }
        }
    }
}

@Composable
private fun MoreScreen(navController: NavHostController) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("More") }
        items(Secondary.entries) { destination ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable {
                        navController.navigate(destination.route) { launchSingleTop = true }
                    },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(destination.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        destination.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Explanation(
                "Module configuration and service functions need a connected adapter " +
                    "and a completed module scan.",
            )
        }
    }
}
