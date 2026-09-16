package sh.emilien.RATapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.emilien.RATapp.ble.GameMode
import sh.emilien.RATapp.ble.TEN_MODE_HITS_REQUIRED
import sh.emilien.RATapp.cv.CvViewModel
import sh.emilien.RATapp.cv.findActivity

/**
 * Live run view. Entered once calibration is registered: sends `setup-done`,
 * which tells the controller to drop any leftover trajectory, then streams a batch of
 * inference points every few frames.
 *
 * The camera itself is not shown here — during a run what matters is the wall, so this
 * draws the virtual 300x200 surface and the target/ball positions the controller pushes
 * back. The camera keeps running headless behind it to feed the detector.
 */
@Composable
fun RunScreen(
    onBack: () -> Unit,
    viewModel: BluetoothViewModel,
    cvViewModel: CvViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val frame by cvViewModel.frame.collectAsStateWithLifecycle()
    val streaming by cvViewModel.streaming.collectAsStateWithLifecycle()
    val sent by cvViewModel.pointsSent.collectAsStateWithLifecycle()
    val dropped by cvViewModel.pointsDropped.collectAsStateWithLifecycle()
    val target by viewModel.targetVirtualPosition.collectAsStateWithLifecycle()
    val ball by viewModel.ballVirtualPosition.collectAsStateWithLifecycle()
    val score by viewModel.score.collectAsStateWithLifecycle()
    val history by viewModel.wallHistory.collectAsStateWithLifecycle()
    val gameMode by viewModel.gameMode.collectAsStateWithLifecycle()
    val hits by viewModel.hits.collectAsStateWithLifecycle()
    val attempts by viewModel.attempts.collectAsStateWithLifecycle()
    val runFinished by viewModel.runFinished.collectAsStateWithLifecycle()

    var viewedAttempt by remember { mutableStateOf<Int?>(null) }
    var historyExpanded by remember { mutableStateOf(false) }

    val viewed = viewedAttempt?.let { history.getOrNull(it) }
    LaunchedEffect(history.size) {
        viewedAttempt?.let { if (it >= history.size) viewedAttempt = null }
    }

    // This screen shows the virtual wall rather than the camera, but the detector
    // still needs a bound camera to stream from. Home binds it first in practice;
    // binding here too (bindCamera is idempotent) keeps the run screen from
    // depending on which screen came before it.
    val activity = context.findActivity()
    LaunchedEffect(activity) { activity?.let { cvViewModel.bindCamera(it) } }

    DisposableEffect(Unit) {
        // Entering the run: tell the controller to start a fresh trajectory, then
        // start feeding it frames.
        viewModel.resetRun()
        viewModel.sendSetupDone()
        cvViewModel.startStreaming()
        onDispose { cvViewModel.stopStreaming() }
    }

    // The camera dies with the screen, so don't let it time out mid-run.
    LaunchedEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (context as? android.app.Activity)?.window
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                FilterChip(
                    selected = gameMode == GameMode.ENDLESS,
                    onClick = { viewModel.setGameMode(GameMode.ENDLESS) },
                    label = { Text("Endless") },
                )
                FilterChip(
                    selected = gameMode == GameMode.TEN,
                    onClick = { viewModel.setGameMode(GameMode.TEN) },
                    label = { Text("Ten") },
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (gameMode == GameMode.TEN) "ACCURACY" else "SCORE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatScore(score),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (gameMode == GameMode.TEN) {
                    Text(
                        text = if (runFinished) {
                            "Done — $TEN_MODE_HITS_REQUIRED hits in $attempts shots"
                        } else {
                            "$hits of $TEN_MODE_HITS_REQUIRED hits — $attempts shots"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (runFinished) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    LinearProgressIndicator(
                        progress = { hits.toFloat() / TEN_MODE_HITS_REQUIRED },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                } else {
                    Text(
                        text = "$attempts shots",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            VirtualWall(
                target = viewed?.target ?: target,
                ball = if (viewed != null) viewed.ball else ball,
                modifier = Modifier.fillMaxWidth(),
            )

            if (history.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { historyExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            viewedAttempt?.let { "Attempt ${it + 1} of ${history.size}" }
                                ?: "Live (${history.size} past attempts)"
                        )
                    }
                    DropdownMenu(
                        expanded = historyExpanded,
                        onDismissRequest = { historyExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Live") },
                            onClick = {
                                viewedAttempt = null
                                historyExpanded = false
                            },
                        )
                        history.forEachIndexed { index, snapshot ->
                            DropdownMenuItem(
                                text = { Text(attemptLabel(index, snapshot)) },
                                onClick = {
                                    viewedAttempt = index
                                    historyExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            val detection = frame?.detection
            Text(
                text = if (detection == null) {
                    "No detection"
                } else {
                    "Ball conf=%.2f at x=%.0f y=%.0f".format(
                        detection.conf, detection.x, detection.y,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "fps %.1f | batches sent %d, dropped %d | %s".format(
                    frame?.fps ?: 0f, sent, dropped,
                    if (streaming) "streaming" else "paused",
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                onClick = {
                    if (runFinished) viewModel.setGameMode(gameMode) else viewModel.sendNextTarget()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (runFinished) "Play again" else "Next")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (streaming) cvViewModel.stopStreaming() else cvViewModel.startStreaming()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (streaming) "Pause streaming" else "Resume streaming")
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Back")
                }
            }
        }
    }
}

private fun attemptLabel(index: Int, snapshot: WallSnapshot): String {
    val ball = snapshot.ball
    val outcome = if (ball == null) {
        "no ball"
    } else {
        "ball %.0f,%.0f".format(ball.x, ball.y)
    }
    return "Attempt ${index + 1} — target %.0f,%.0f — %s".format(
        snapshot.target.x, snapshot.target.y, outcome,
    )
}

/** Both modes report a 0..1 fraction, which reads better as a percentage. */
private fun formatScore(score: Float): String = "%.0f%%".format(score * 100f)
