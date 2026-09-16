package sh.emilien.RATapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.emilien.RATapp.cv.CvDebugControls
import sh.emilien.RATapp.cv.CvViewModel
import sh.emilien.RATapp.ble.ConnectionState

@Composable
fun DebugScreen(
    onDisconnected: () -> Unit,
    viewModel: BluetoothViewModel = viewModel(),
    cvViewModel: CvViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.connectionState) {
        if (uiState.connectionState is ConnectionState.Idle ||
            uiState.connectionState is ConnectionState.Failed
        ) {
            onDisconnected()
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Debug",
                style = MaterialTheme.typography.headlineMedium,
            )

            CvDebugControls(viewModel = cvViewModel)
        }
    }
}
