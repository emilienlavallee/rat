package sh.emilien.RATapp.cv

import android.app.Application
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val TAG = "RATCv"

/** Where a point goes when the BLE writer cannot keep up. */
private const val POINT_CHANNEL_CAPACITY = 8

/** Log one in this many sent points, so a live stream is visible without flooding. */
private const val POINT_LOG_INTERVAL = 20

/** One inference readout, in upright analyzer frame pixels. */
private data class InferencePoint(val x: Float, val y: Float)

private fun Size.area(): Long = width.toLong() * height.toLong()

/**
 * Owns the on-device CV pipeline: the camera binding, the ncnn analyzer, the manual
 * exposure/ISO controls, and the batching of inference results toward BLE.
 *
 * Activity-scoped, because both the calibration screen and the inference screen render
 * the same live analyzer output and must not each bind their own camera.
 */
class CvViewModel(application: Application) : AndroidViewModel(application) {

    private val _frame = MutableStateFlow<FrameResult?>(null)

    /** Latest analyzed frame, with the detection overlay already drawn on. */
    val frame: StateFlow<FrameResult?> = _frame.asStateFlow()

    private val _streaming = MutableStateFlow(false)

    /** True once calibration is done and inference is being sent to the controller. */
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _pointsSent = MutableStateFlow(0)
    val pointsSent: StateFlow<Int> = _pointsSent.asStateFlow()

    private val _pointsDropped = MutableStateFlow(0)
    val pointsDropped: StateFlow<Int> = _pointsDropped.asStateFlow()

    private val _exposureRange = MutableStateFlow<Range<Long>?>(null)
    val exposureRange: StateFlow<Range<Long>?> = _exposureRange.asStateFlow()

    private val _isoRange = MutableStateFlow<Range<Int>?>(null)
    val isoRange: StateFlow<Range<Int>?> = _isoRange.asStateFlow()

    /** False on LEGACY devices, where manual sensor settings are simply ignored. */
    private val _manualSensorSupported = MutableStateFlow(false)
    val manualSensorSupported: StateFlow<Boolean> = _manualSensorSupported.asStateFlow()

    // Sliders are normalized 0..1 and mapped onto the device's real ranges.
    private val _exposurePos = MutableStateFlow(0.35f)
    val exposurePos: StateFlow<Float> = _exposurePos.asStateFlow()

    private val _isoPos = MutableStateFlow(0.35f)
    val isoPos: StateFlow<Float> = _isoPos.asStateFlow()

    private val _autoExposure = MutableStateFlow(false)
    val autoExposure: StateFlow<Boolean> = _autoExposure.asStateFlow()

    /**
     * Run the model through LiteRT's GPU delegate instead of ncnn on CPU.
     *
     * The fp32 TFLite export is a lossless conversion of the same weights
     * (mAP50 0.905 vs 0.903 for the PyTorch reference). Requires the model directory
     * to ship a .tflite; models without one stay on ncnn.
     */
    private val _useLiteRtGpu = MutableStateFlow(false)
    val useLiteRtGpu: StateFlow<Boolean> = _useLiteRtGpu.asStateFlow()

    /** Which backend the analyzer should load, derived from the toggle. */
    private fun selectedBackend(): Backend =
        if (_useLiteRtGpu.value) Backend.LITERT_GPU else Backend.NCNN_CPU

    val models: List<ModelChoice> = ModelChoice.discover(application.assets)

    private val _model = MutableStateFlow(models.firstOrNull())
    val model: StateFlow<ModelChoice?> = _model.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()
    private var camera2Control: Camera2CameraControl? = null
    private var bound = false

    /**
     * Points are dropped rather than queued when the writer falls behind: a stale
     * trajectory point is worthless, and this is what keeps a stalled GATT stack from
     * back-pressuring into the camera thread.
     */
    private val pointChannel = Channel<InferencePoint>(
        capacity = POINT_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val analyzer = DetectAnalyzer(application.assets) { result ->
        _frame.value = result
    }

    /** Set by the host once the BLE layer is available; see [sh.emilien.RATapp.BluetoothViewModel]. */
    @Volatile
    var inferenceSink: InferenceSink? = null

    init {
        // Single writer coroutine: BLE writes are serialised by construction, which is
        // what keeps WRITE_TYPE_NO_RESPONSE from returning ERROR_GATT_WRITE_REQUEST_BUSY.
        viewModelScope.launch(Dispatchers.IO) {
            for (point in pointChannel) {
                val sink = inferenceSink
                if (sink == null) {
                    _pointsDropped.value += 1
                    Log.w(TAG, "point dropped: no inference sink wired")
                } else if (!sink.send(point.x, point.y)) {
                    _pointsDropped.value += 1
                    Log.w(TAG, "point dropped: BLE write failed")
                } else {
                    _pointsSent.value += 1
                    // One line per frame would flood; log periodically, which is
                    // enough to see the stream is alive.
                    if (_pointsSent.value % POINT_LOG_INTERVAL == 0) {
                        Log.i(
                            TAG,
                            "sent point x=${point.x} y=${point.y} " +
                                "(sent=${_pointsSent.value} dropped=${_pointsDropped.value})",
                        )
                    }
                }
            }
        }
    }

    fun setModel(choice: ModelChoice) {
        _model.value = choice
    }

    fun setUseLiteRtGpu(value: Boolean) {
        _useLiteRtGpu.value = value
    }

    /**
     * Applies the current model/backend selection to the analyzer, skipping
     * combinations the model has no build for rather than failing on the analysis
     * thread.
     */
    fun applyBackend() {
        val model = _model.value ?: return
        val backend = selectedBackend()
        if (!model.supports(backend)) {
            Log.w(TAG, "${model.name} has no build for ${backend.label}; not switching")
            return
        }
        analyzer.pendingLoad = model to backend
        Log.i(TAG, "loading ${model.name} on ${backend.label}")
    }

    fun setAutoExposure(value: Boolean) {
        _autoExposure.value = value
    }

    fun setExposurePos(value: Float) {
        _exposurePos.value = value
    }

    fun setIsoPos(value: Float) {
        _isoPos.value = value
    }

    /**
     * Exposure time spans orders of magnitude, so the slider is mapped on a log scale.
     * Null until the camera reports its range.
     */
    fun exposureNs(): Long? = _exposureRange.value?.let {
        val lo = ln(it.lower.coerceAtLeast(1L).toDouble())
        val hi = ln(it.upper.toDouble())
        exp(lo + (hi - lo) * _exposurePos.value).roundToLong().coerceIn(it.lower, it.upper)
    }

    fun iso(): Int? = _isoRange.value?.let {
        (it.lower + (it.upper - it.lower) * _isoPos.value).roundToInt()
            .coerceIn(it.lower, it.upper)
    }

    /**
     * Starts sending inference to the controller. Called on the calibration ->
     * inference transition, alongside the `setup-done` message that tells the
     * controller to drop any leftover trajectory.
     */
    fun startStreaming() {
        _streaming.value = true
        analyzer.pointSink = { x, y ->
            if (pointChannel.trySend(InferencePoint(x, y)).isFailure) {
                _pointsDropped.value += 1
                Log.w(TAG, "point dropped: writer is not keeping up")
            }
        }
        Log.i(
            TAG,
            "streaming inference: sink=${if (inferenceSink != null) "wired" else "MISSING"}",
        )
    }

    /** Stops sending; the analyzer keeps running so the preview stays live. */
    fun stopStreaming() {
        analyzer.pointSink = null
        _streaming.value = false
        Log.i(
            TAG,
            "stopped streaming (sent=${_pointsSent.value} dropped=${_pointsDropped.value})",
        )
    }

    /**
     * Binds the analysis use case. Idempotent: both screens call it, but only the
     * first binding takes effect, so navigating between them never restarts the camera.
     */
    suspend fun bindCamera(lifecycleOwner: LifecycleOwner) {
        if (bound) return
        val context = getApplication<Application>()
        // getInstance().get() blocks, so keep it off the main thread.
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        val analysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            // The Android analogue of record_infer's CAP_PROP_BUFFERSIZE = 1.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    // The model stretches whatever it gets to 640x640, so the ideal
                    // frame is the smallest one that still covers 640 on both axes:
                    // anything smaller upscales, anything larger is pixels we convert
                    // and then throw away.
                    .setResolutionFilter { supported, _ ->
                        val (covering, rest) = supported.partition {
                            it.width >= MODEL_INPUT_SIZE && it.height >= MODEL_INPUT_SIZE
                        }
                        Log.i(TAG, "supported analysis sizes: $supported")
                        covering.sortedBy { it.area() } + rest.sortedByDescending { it.area() }
                    }
                    .build()
            )
            .build()
        analysis.setAnalyzer(executor, analyzer)

        provider.unbindAll()
        val camera = provider.bindToLifecycle(
            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis,
        )
        Log.i(TAG, "analysis resolution: ${analysis.resolutionInfo?.resolution}")

        val info = Camera2CameraInfo.from(camera.cameraInfo)
        _exposureRange.value = info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )
        _isoRange.value = info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )
        // Manual exposure/ISO is silently ignored without this capability, so the UI
        // greys the sliders out instead of pretending they did something.
        val capabilities = info.getCameraCharacteristic(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        )
        _manualSensorSupported.value = capabilities?.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        ) ?: false
        if (!_manualSensorSupported.value) {
            Log.w(TAG, "device lacks MANUAL_SENSOR; exposure and ISO cannot be set")
        }
        // Camera2Interop.Extender only applies at bind time; this applies live.
        camera2Control = Camera2CameraControl.from(camera.cameraControl)
        applySensorSettings()
        // Only latched once the bind actually succeeded. Latching before would make
        // a failed bind — a SecurityException from binding before the CAMERA grant,
        // say — permanent, since CameraX does not retry after that error either.
        bound = true
    }

    /** Pushes the current exposure/ISO to the camera. */
    fun applySensorSettings() {
        val control = camera2Control ?: return
        val options = CaptureRequestOptions.Builder()
        if (_autoExposure.value || !_manualSensorSupported.value) {
            options.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON
            )
        } else {
            options.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF
            )
            exposureNs()?.let {
                options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
            }
            iso()?.let {
                options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it)
            }
        }
        control.setCaptureRequestOptions(options.build())
    }

    override fun onCleared() {
        super.onCleared()
        stopStreaming()
        pointChannel.close()
        executor.execute { analyzer.release() }
        executor.shutdown()
    }
}

/** How a point reaches the BLE layer, kept as an interface so CV does not depend on it. */
fun interface InferenceSink {
    suspend fun send(x: Float, y: Float): Boolean
}
