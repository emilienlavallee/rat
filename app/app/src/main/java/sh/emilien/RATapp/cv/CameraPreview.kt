package sh.emilien.RATapp.cv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Walks out to the hosting activity.
 *
 * Deliberately not LocalLifecycleOwner: inside a NavHost that resolves to the
 * NavBackStackEntry, whose lifecycle stops as soon as you navigate to another
 * screen — which would tear the camera down on every Home <-> Camera transition.
 * The camera should live as long as the activity does.
 */
internal fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Binds the camera (once, process-wide) and draws the latest annotated frame.
 *
 * Both the calibration screen and the inference screen use this, so they render the
 * exact same pixels the detector sees.
 *
 * The CAMERA permission gate lives here rather than in the calling screens: this is
 * the only place the camera is bound, and binding before the grant fails with a
 * SecurityException that CameraX does not retry.
 */
@Composable
fun CameraPreview(
    viewModel: CvViewModel,
    modifier: Modifier = Modifier,
) {
    CameraGate(modifier = modifier) {
        CameraPreviewContent(viewModel = viewModel, modifier = modifier)
    }
}

@Composable
private fun CameraPreviewContent(
    viewModel: CvViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current.findActivity()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val useLiteRtGpu by viewModel.useLiteRtGpu.collectAsStateWithLifecycle()
    val autoExposure by viewModel.autoExposure.collectAsStateWithLifecycle()
    val exposurePos by viewModel.exposurePos.collectAsStateWithLifecycle()
    val isoPos by viewModel.isoPos.collectAsStateWithLifecycle()

    LaunchedEffect(activity) { activity?.let { viewModel.bindCamera(it) } }

    LaunchedEffect(model, useLiteRtGpu) {
        viewModel.applyBackend()
    }

    LaunchedEffect(autoExposure, exposurePos, isoPos) {
        viewModel.applySensorSettings()
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val current = frame
        if (current == null) {
            Text("Loading model…", color = Color.White)
        } else {
            Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = "detections",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
