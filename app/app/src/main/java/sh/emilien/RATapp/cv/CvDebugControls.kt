package sh.emilien.RATapp.cv

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private const val TAG = "RATCv"

/**
 * A start/stop button owning one [FrameRecorder], handing it to the analyzer through
 * [onRecorderChange] while it runs and null once it stops. Frames land in
 * `Pictures/<folderPrefix>_<date>/`.
 */
@Composable
private fun RecorderControl(
    startLabel: String,
    stopLabel: String,
    folderPrefix: String,
    onRecorderChange: (FrameRecorder?) -> Unit,
) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<FrameRecorder?>(null) }
    var status by remember { mutableStateOf("") }

    /** Detach first so no further frames queue, then drain and close off the UI thread. */
    fun stop(active: FrameRecorder) {
        onRecorderChange(null)
        recorder = null
        Thread { active.stop() }.start()
    }

    DisposableEffect(Unit) {
        // Leaving the screen mid-recording still closes the folder cleanly.
        onDispose { recorder?.let { stop(it) } }
    }

    // Poll the writer's counters rather than pushing from it, so a fast frame rate
    // doesn't turn into a fast recomposition rate.
    LaunchedEffect(recorder) {
        val active = recorder ?: return@LaunchedEffect
        while (true) {
            status = "${active.folder}: ${active.written} frames" +
                if (active.dropped > 0) " (${active.dropped} dropped)" else ""
            delay(250)
        }
    }

    Button(
        onClick = {
            val active = recorder
            if (active == null) {
                val started = FrameRecorder(context, FrameRecorder.newFolderName(folderPrefix))
                recorder = started
                onRecorderChange(started)
                Log.i(TAG, "recording to Pictures/${started.folder}")
            } else {
                status = "saved ${active.written} frames to ${active.folder}"
                stop(active)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = if (recorder != null) {
            ButtonDefaults.buttonColors(containerColor = Color.Red)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(if (recorder != null) stopLabel else startLabel)
    }

    if (status.isNotEmpty()) {
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The on-device CV tuning surface: model choice, backend, sensor settings and the
 * frame recorders. Lives on the debug screen so the operator-facing screens stay
 * simple, but the field-tuning value of A/B-ing models on the real wall is high.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CvDebugControls(
    viewModel: CvViewModel,
    modifier: Modifier = Modifier,
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val useLiteRtGpu by viewModel.useLiteRtGpu.collectAsStateWithLifecycle()
    val autoExposure by viewModel.autoExposure.collectAsStateWithLifecycle()
    val exposurePos by viewModel.exposurePos.collectAsStateWithLifecycle()
    val isoPos by viewModel.isoPos.collectAsStateWithLifecycle()
    val exposureRange by viewModel.exposureRange.collectAsStateWithLifecycle()
    val isoRange by viewModel.isoRange.collectAsStateWithLifecycle()
    val manualSupported by viewModel.manualSensorSupported.collectAsStateWithLifecycle()
    val sent by viewModel.pointsSent.collectAsStateWithLifecycle()
    val dropped by viewModel.pointsDropped.collectAsStateWithLifecycle()
    val streaming by viewModel.streaming.collectAsStateWithLifecycle()
    var modelMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("On-device CV", style = MaterialTheme.typography.titleMedium)

        Text(
            "fps %.1f | batches sent %d, dropped %d".format(frame?.fps ?: 0f, sent, dropped),
            style = MaterialTheme.typography.bodySmall,
        )

        // Lets inference streaming be exercised without walking the whole
        // calibrate-then-register flow first.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Stream inference", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = streaming,
                onCheckedChange = {
                    if (it) viewModel.startStreaming() else viewModel.stopStreaming()
                },
            )
        }

        ExposedDropdownMenuBox(
            expanded = modelMenuOpen,
            onExpandedChange = { modelMenuOpen = it },
        ) {
            TextField(
                value = model?.let { "${it.name} @${it.inputSize}" } ?: "no models in assets",
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenuOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = modelMenuOpen,
                onDismissRequest = { modelMenuOpen = false },
            ) {
                viewModel.models.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text("${choice.name} @${choice.inputSize}") },
                        onClick = {
                            viewModel.setModel(choice)
                            modelMenuOpen = false
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Enable LiteRT GPU", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = useLiteRtGpu,
                onCheckedChange = viewModel::setUseLiteRtGpu,
                // Only offerable when the selected model ships a .tflite.
                enabled = model?.tfliteAsset != null,
            )
        }

        if (model?.tfliteAsset == null) {
            Text(
                "${model?.name ?: "This model"} has no .tflite build, so LiteRT " +
                    "cannot run it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!manualSupported) {
            Text(
                "This camera has no MANUAL_SENSOR support, so exposure and ISO " +
                    "cannot be set directly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Auto exposure", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = autoExposure,
                onCheckedChange = viewModel::setAutoExposure,
                enabled = manualSupported,
            )
        }

        Text(
            "Exposure: ${viewModel.exposureNs()?.let { "${it / 1000} µs" } ?: "—"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = exposurePos,
            onValueChange = viewModel::setExposurePos,
            enabled = manualSupported && !autoExposure && exposureRange != null,
        )

        Text(
            "ISO: ${viewModel.iso() ?: "—"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = isoPos,
            onValueChange = viewModel::setIsoPos,
            enabled = manualSupported && !autoExposure && isoRange != null,
        )

        RecorderControl(
            startLabel = "Record all frames",
            stopLabel = "Stop recording",
            folderPrefix = "frames",
            onRecorderChange = { viewModel.analyzer.recorder = it },
        )

        RecorderControl(
            startLabel = "Save detections only",
            stopLabel = "Stop saving detections",
            folderPrefix = "detections",
            onRecorderChange = { viewModel.analyzer.detectionRecorder = it },
        )
    }
}
