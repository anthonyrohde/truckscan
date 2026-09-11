package com.anthonyrohde.f250scan

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.anthonyrohde.f250scan.ui.screens.AsBuiltScreen
import com.anthonyrohde.f250scan.ui.screens.ConnectScreen
import com.anthonyrohde.f250scan.ui.screens.FaultsScreen
import com.anthonyrohde.f250scan.ui.screens.LiveDataScreen
import com.anthonyrohde.f250scan.ui.screens.LogScreen
import com.anthonyrohde.f250scan.ui.screens.ModulesScreen
import com.anthonyrohde.f250scan.ui.screens.RoutinesScreen
import com.anthonyrohde.f250scan.ui.theme.F250ScanTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            F250ScanTheme {
                AppRoot()
            }
        }
    }
}

private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    CONNECT("connect", "Connect", Icons.Filled.Link),
    MODULES("modules", "Modules", Icons.Filled.Memory),
    FAULTS("faults", "Faults", Icons.Filled.Warning),
    LIVE("live", "Live", Icons.Filled.Speed),
    AS_BUILT("asbuilt", "As-Built", Icons.Filled.Settings),
    ROUTINES("routines", "Service", Icons.Filled.Build),
    LOG("log", "Log", Icons.Filled.Terminal),
}

@Composable
private fun AppRoot() {
    val viewModel: ScanViewModel = viewModel()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val message by viewModel.message.collectAsStateWithLifecycle()

    // Bluetooth permissions must be granted before paired adapters can even be
    // listed, so ask on first launch rather than presenting an empty list.
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
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
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController, startDestination = Destination.CONNECT.route) {
                composable(Destination.CONNECT.route) { ConnectScreen(viewModel) }
                composable(Destination.MODULES.route) { ModulesScreen(viewModel) }
                composable(Destination.FAULTS.route) { FaultsScreen(viewModel) }
                composable(Destination.LIVE.route) { LiveDataScreen(viewModel) }
                composable(Destination.AS_BUILT.route) { AsBuiltScreen(viewModel) }
                composable(Destination.ROUTINES.route) { RoutinesScreen(viewModel) }
                composable(Destination.LOG.route) { LogScreen(viewModel) }
            }
        }
    }
}
