package sh.emilien.RATapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.emilien.RATapp.ble.ConnectionState
import sh.emilien.RATapp.cv.CameraPreview
import sh.emilien.RATapp.cv.CvViewModel

private data class HomePoint(val offset: Offset, val confirmed: Boolean)

private val HOME_COLOR_ACTIVE = Color(0xFFFFC107)
private val HOME_COLOR_CONFIRMED = Color(0xFF4CAF50)

@Composable
private fun ServoControls(
    horizontalAngle: Int,
    verticalAngle: Int,
    onHorizontalChange: (Int) -> Unit,
    onVerticalChange: (Int) -> Unit,
) {
    var hSlider by remember { mutableFloatStateOf(horizontalAngle.toFloat()) }
    var vSlider by remember { mutableFloatStateOf(verticalAngle.toFloat()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Horizontal", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Text("${hSlider.toInt()}°", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = hSlider,
            onValueChange = {
                hSlider = it
                val angle = it.toInt()
                if (angle % 5 == 0) onHorizontalChange(angle)
            },
            onValueChangeFinished = { onHorizontalChange(hSlider.toInt()) },
            valueRange = 0f..180f,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vertical", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Text("${vSlider.toInt()}°", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = vSlider,
            onValueChange = {
                vSlider = it
                val angle = it.toInt()
                if (angle % 5 == 0) onVerticalChange(angle)
            },
            onValueChangeFinished = { onVerticalChange(vSlider.toInt()) },
            valueRange = 0f..180f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Maps a point in the outer box's screen space onto upright analyzer frame pixels.
 *
 * Two transforms have to be undone: the pinch-zoom/pan applied by the graphicsLayer,
 * and the letterbox that ContentScale.Fit adds when the frame's aspect ratio differs
 * from the box's. Skipping the second is what previously put calibration points and
 * inference points in different coordinate spaces, making the fitted homography
 * invalid on live inference input.
 */
internal fun screenToFramePixels(
    screenOffset: Offset,
    panOffset: Offset,
    zoom: Float,
    boxSize: IntSize,
    frameWidth: Int,
    frameHeight: Int,
): Offset? {
    if (boxSize == IntSize.Zero || frameWidth <= 0 || frameHeight <= 0) return null
    val inBox = (screenOffset - panOffset) / zoom
    val scale = minOf(
        boxSize.width.toFloat() / frameWidth,
        boxSize.height.toFloat() / frameHeight,
    )
    if (scale <= 0f) return null
    val offX = (boxSize.width - frameWidth * scale) / 2f
    val offY = (boxSize.height - frameHeight * scale) / 2f
    return Offset((inBox.x - offX) / scale, (inBox.y - offY) / scale)
}

@Composable
fun HomeScreen(
    onOpenDebug: () -> Unit,
    onOpenRun: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: BluetoothViewModel = viewModel(),
    cvViewModel: CvViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val frame by cvViewModel.frame.collectAsState()
    val horizontalAngle by viewModel.horizontalAngle.collectAsState()
    val verticalAngle by viewModel.verticalAngle.collectAsState()

    var points by remember { mutableStateOf(listOf<HomePoint>()) }
    var activeIndex by remember { mutableStateOf<Int?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var registered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.sendImAlive()
    }

    LaunchedEffect(uiState.connectionState) {
        if (uiState.connectionState is ConnectionState.Idle ||
            uiState.connectionState is ConnectionState.Failed
        ) {
            onDisconnected()
        }
    }

    val confirmedCount = points.count { it.confirmed }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("RAT", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onOpenDebug,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bug),
                        contentDescription = "Debug",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Outer box captures touch in screen space; inner box applies zoom/pan
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { boxSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            val newZoom = (zoom * zoomChange).coerceIn(1f, 5f)
                            val actualChange = newZoom / zoom
                            panOffset = Offset(
                                x = centroid.x * (1f - actualChange) + panOffset.x * actualChange + pan.x,
                                y = centroid.y * (1f - actualChange) + panOffset.y * actualChange + pan.y,
                            )
                            zoom = newZoom
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { screenOffset ->
                            // Convert screen → image space
                            val imageOffset = (screenOffset - panOffset) / zoom
                            val mutablePoints = points.toMutableList()
                            val ai = activeIndex
                            if (ai != null) {
                                mutablePoints[ai] = mutablePoints[ai].copy(offset = imageOffset)
                                points = mutablePoints
                            } else {
                                val tapRadius = 60f / zoom
                                val nearest = mutablePoints.indexOfFirst { p ->
                                    p.confirmed && (p.offset - imageOffset).getDistance() <= tapRadius
                                }
                                if (nearest != -1) {
                                    mutablePoints[nearest] = mutablePoints[nearest].copy(
                                        offset = imageOffset,
                                        confirmed = false,
                                    )
                                    points = mutablePoints
                                    activeIndex = nearest
                                } else {
                                    mutablePoints.add(HomePoint(offset = imageOffset, confirmed = false))
                                    points = mutablePoints
                                    activeIndex = mutablePoints.size - 1
                                }
                            }
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoom,
                            scaleY = zoom,
                            translationX = panOffset.x,
                            translationY = panOffset.y,
                            transformOrigin = TransformOrigin(0f, 0f),
                        ),
                ) {
                    // The phone's own camera, with the detector's overlay already on
                    // it: the operator calibrates against exactly the frame the
                    // detector sees, and live detections confirm the model is picking
                    // the ball up before the calibration is committed.
                    CameraPreview(
                        viewModel = cvViewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.medium),
                    )

                    // Points overlay drawn in image space (graphicsLayer handles zoom/pan)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val textPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 36f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = true
                        }

                        points.forEachIndexed { i, p ->
                            val color = if (p.confirmed) HOME_COLOR_CONFIRMED else HOME_COLOR_ACTIVE
                            drawCircle(
                                color = color,
                                radius = 7f,
                                center = p.offset,
                                style = Stroke(width = 1.5f),
                            )
                            drawCircle(color = Color.White, radius = 2f, center = p.offset)
                            drawContext.canvas.nativeCanvas.drawText(
                                "${i + 1}",
                                p.offset.x,
                                p.offset.y + 13f,
                                textPaint,
                            )
                        }
                    }
                }
            }

            ServoControls(
                horizontalAngle = horizontalAngle,
                verticalAngle = verticalAngle,
                onHorizontalChange = viewModel::setHorizontalAngle,
                onVerticalChange = viewModel::setVerticalAngle,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        points = listOf()
                        activeIndex = null
                        zoom = 1f
                        panOffset = Offset.Zero
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Reset")
                }

                Button(
                    onClick = {
                        val ai = activeIndex
                        if (ai != null) {
                            val updated = points.toMutableList().also { list ->
                                list[ai] = list[ai].copy(confirmed = true)
                            }
                            points = updated
                            activeIndex = null

                            // Points are stored in box space so the overlay lines up
                            // under the graphicsLayer, but the wire carries analyzer
                            // frame pixels — the same space inference points use.
                            val bitmap = frame?.bitmap
                            val framePoint = bitmap?.let {
                                screenToFramePixels(
                                    screenOffset = updated[ai].offset,
                                    panOffset = Offset.Zero,
                                    zoom = 1f,
                                    boxSize = boxSize,
                                    frameWidth = it.width,
                                    frameHeight = it.height,
                                )
                            }
                            if (framePoint != null) {
                                viewModel.sendCalibrationPoint(
                                    index = ai,
                                    x = framePoint.x,
                                    y = framePoint.y,
                                )
                            }
                        }
                    },
                    enabled = activeIndex != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Confirm")
                }

                Button(
                    onClick = {
                        viewModel.sendRegisterCalibration()
                        registered = true
                    },
                    enabled = activeIndex == null && confirmedCount >= 4,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Register")
                }
            }

            Button(
                onClick = onOpenRun,
                enabled = registered,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (registered) "Start run" else "Register calibration first")
            }
        }
    }
}
