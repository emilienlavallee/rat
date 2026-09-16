package sh.emilien.RATapp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import sh.emilien.RATapp.ble.ConnectionState
import sh.emilien.RATapp.ble.DiscoveredDevice

private const val RAT_DEVICE_NAME = "RAT"

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun permissionsGranted(context: Context): Boolean =
    requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    viewModel: BluetoothViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    val context = LocalContext.current
    var granted by remember { mutableStateOf(permissionsGranted(context)) }
    var connectingTo by remember { mutableStateOf<DiscoveredDevice?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
    }

    val bluetoothEnabled = remember(granted) {
        if (!granted) {
            false
        } else {
            runCatching {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                    .adapter
                    ?.isEnabled
            }.getOrNull() ?: false
        }
    }

    LaunchedEffect(granted, bluetoothEnabled) {
        if (granted && bluetoothEnabled && uiState.connectionState is ConnectionState.Idle) {
            viewModel.startScan()
        }
    }

    LaunchedEffect(uiState.scanning, uiState.connectionState) {
        if (uiState.scanning && uiState.connectionState !is ConnectionState.Connected) {
            while (true) {
                delay(15_000)
                viewModel.restartScan()
            }
        }
    }

    LaunchedEffect(uiState.connectionState) {
        when (uiState.connectionState) {
            is ConnectionState.Connected -> onConnected()
            is ConnectionState.Failed -> connectingTo = null
            else -> Unit
        }
    }

    LaunchedEffect(uiState.devices, uiState.connectionState, connectingTo, uiState.scanning) {
        if (granted && bluetoothEnabled && uiState.scanning &&
            uiState.connectionState is ConnectionState.Idle && connectingTo == null
        ) {
            val target = uiState.devices.firstOrNull { it.name == RAT_DEVICE_NAME }
            if (target != null) {
                connectingTo = target
                viewModel.connect(target)
            }
        }
    }

    if (uiState.connectionState is ConnectionState.Connecting) {
        ConnectingContent(deviceName = connectingTo?.name)
        return
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            Text(
                text = "RAT",
                style = MaterialTheme.typography.headlineMedium,
            )

            when {
                !granted -> {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Bluetooth permissions are required to discover devices.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { permissionLauncher.launch(requiredPermissions()) }) {
                        Text("Grant permissions")
                    }
                }

                !bluetoothEnabled -> {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Bluetooth is turned off. Enable it in settings to scan for devices.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                else -> DeviceListContent(
                    uiState = uiState,
                    onToggleScan = {
                        if (uiState.scanning) viewModel.stopScan() else viewModel.startScan()
                    },
                    onConnect = { device ->
                        connectingTo = device
                        viewModel.connect(device)
                    },
                )
            }

            ConnectionStatusBar(connectionState = uiState.connectionState)
        }
    }
}

@Composable
private fun ConnectingContent(deviceName: String?) {
    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(24.dp))
                Text(
                    text = if (deviceName != null) {
                        "Connecting to $deviceName..."
                    } else {
                        "Connecting..."
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.DeviceListContent(
    uiState: BluetoothUiState,
    onToggleScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Button(onClick = onToggleScan) {
            Text(if (uiState.scanning) "Stop scan" else "Scan")
        }
        if (uiState.scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scanning...", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    if (uiState.devices.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (uiState.scanning) "Looking for devices..." else "No devices found.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.devices, key = { it.address }) { device ->
                DeviceRow(
                    device = device,
                    enabled = uiState.connectionState is ConnectionState.Idle,
                    onClick = { onConnect(device) },
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DiscoveredDevice,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "Unknown device",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${device.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConnectionStatusBar(connectionState: ConnectionState) {
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    when (connectionState) {
        ConnectionState.Idle -> Text(
            text = "Not connected",
            style = MaterialTheme.typography.bodyMedium,
        )

        ConnectionState.Connecting -> Unit

        is ConnectionState.Connected -> Unit

        is ConnectionState.Failed -> Text(
            text = "Connection failed: ${connectionState.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
