package sh.emilien.RATapp.cv

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

private const val TAG = "RATCv"

/** Frames are counted over windows of this length and averaged. */
const val FPS_WINDOW_SECONDS = 10f

/** Out-of-frame detections are logged this often, so a broken model is loud but not deafening. */
private const val OUT_OF_FRAME_LOG_INTERVAL = 30

/**
 * Resolution target for the camera. Each model carries its own input size (see
 * [ModelChoice]); this is the size the analysis stream is chosen to cover, so that
 * switching models never means re-binding the camera.
 */
const val MODEL_INPUT_SIZE = 640

/** What the analysis thread publishes back to the UI each frame. */
data class FrameResult(
    val bitmap: Bitmap,
    val fps: Float,
    val detection: Detection?,
    val backendLabel: String,
)

/**
 * Runs the record_infer pipeline on each camera frame: RGBA -> BGR -> rotate ->
 * ncnn inference with the overlay drawn in place -> RGBA -> Bitmap.
 *
 * The model is (re)loaded lazily on this same background thread, so flipping the
 * CPU/GPU switch never blocks the UI.
 */
class DetectAnalyzer(
    private val assets: AssetManager,
    private val onFrame: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private val ncnn = NcnnEngine()
    private val litert = LiteRtEngine()
    private var engine: InferenceEngine = ncnn

    /**
     * Set to request a (re)load with the given model and backend; consumed by the
     * analysis thread so the load never blocks the UI.
     */
    @Volatile
    var pendingLoad: Pair<ModelChoice, Backend>? = null

    /** Set by the UI to save every frame, clean of the overlay; cleared to stop. */
    @Volatile
    var recorder: FrameRecorder? = null

    /**
     * Set by the UI to save only the frames a ball was found in, with the detector's
     * box drawn on; cleared to stop.
     */
    @Volatile
    var detectionRecorder: FrameRecorder? = null

    /**
     * Receives one point per analyzed frame in capture order, in upright analyzer
     * frame pixels, with (-1, -1) when nothing was detected — the same contract the
     * cv service put on the wire. Null while not streaming.
     *
     * Called on the analysis thread, so it must not block: the implementation hands
     * off to the BLE writer through a channel rather than touching the GATT stack.
     */
    @Volatile
    var pointSink: ((Float, Float) -> Unit)? = null

    /** Detections discarded for landing outside the frame; see [analyze]. */
    @Volatile
    var rejected: Int = 0
        private set

    private var loadedModel: ModelChoice? = null
    private val bgr = Mat()
    private val rgbaOut = Mat()
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var bufferIndex = 0

    /** Average over the last completed window, or the running average before the first closes. */
    private var fps = 0f
    private var windowFrames = 0
    private var windowStartNanos = 0L
    private var haveFullWindow = false

    // Per-stage totals for the current window, so the log can say whether we are
    // bound by the camera, the conversions, or the model.
    private var sumConvertInNanos = 0L
    private var sumInferNanos = 0L
    private var sumConvertOutNanos = 0L
    private var sumWorkNanos = 0L

    private fun resetWindow() {
        fps = 0f
        windowFrames = 0
        windowStartNanos = 0L
        haveFullWindow = false
        sumConvertInNanos = 0L
        sumInferNanos = 0L
        sumConvertOutNanos = 0L
        sumWorkNanos = 0L
    }

    override fun analyze(image: ImageProxy) {
        try {
            pendingLoad?.let { (model, backend) ->
                pendingLoad = null
                // Slow on the accelerated paths: ncnn compiles SPIR-V and LiteRT
                // builds its delegate here, which is why this runs off the UI thread.
                engine = if (backend.needsTflite) litert else ncnn
                if (!engine.load(assets, model, backend)) {
                    Log.e(TAG, "model load failed: ${model.assetDir} on ${backend.label}")
                }
                loadedModel = model
                // Start a fresh window so the load stall and the previous model or
                // backend don't contaminate the first measurement of the new one.
                resetWindow()
            }
            if (!engine.isLoaded) return

            val frameStart = System.nanoTime()
            if (windowStartNanos == 0L) windowStartNanos = frameStart

            val plane = image.planes[0]
            val rowStride = plane.rowStride
            // rowStride can exceed width * 4, so wrap the padded buffer and crop.
            val padded = Mat(image.height, rowStride / 4, CvType.CV_8UC4, plane.buffer)
            val rgba = padded.colRange(0, image.width)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()
            padded.release()

            when (image.imageInfo.rotationDegrees) {
                90 -> Core.rotate(bgr, bgr, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(bgr, bgr, Core.ROTATE_180)
                270 -> Core.rotate(bgr, bgr, Core.ROTATE_90_COUNTERCLOCKWISE)
            }

            // Snapshot before inference, so recorded frames are clean of the overlay.
            // The clone is cheap; the encode and the write happen on the recorder thread.
            recorder?.submit(bgr.clone())

            val tConverted = System.nanoTime()

            val backendLabel = engine.activeLabel
            val detection = engine.process(bgr)
            val tInferred = System.nanoTime()

            // A detection outside the frame is not a detection — it means the model
            // or backend produced a bad box (see "detection outside frame" in the
            // native log). Reporting it as no-detection keeps garbage out of the
            // controller's parabola fit, which would otherwise be steered by
            // coordinates an order of magnitude outside the image.
            val valid = detection != null &&
                detection.x >= 0f && detection.x <= bgr.cols() &&
                detection.y >= 0f && detection.y <= bgr.rows()
            if (detection != null && !valid) {
                rejected++
                if (rejected % OUT_OF_FRAME_LOG_INTERVAL == 1) {
                    Log.w(
                        TAG,
                        "dropping out-of-frame detection x=%.0f y=%.0f (frame %dx%d), %d so far"
                            .format(detection.x, detection.y, bgr.cols(), bgr.rows(), rejected),
                    )
                }
            }
            // One point per analyzed frame, whether or not anything was found: the
            // controller's Detector counts frames between detections to decide when
            // a trajectory has ended.
            pointSink?.invoke(
                if (valid) detection!!.x else -1f,
                if (valid) detection!!.y else -1f,
            )

            // process() drew the box onto bgr in place, so this clone is
            // the annotated frame — the same pixels the preview is about to show.
            if (detection != null) detectionRecorder?.submit(bgr.clone())

            Imgproc.cvtColor(bgr, rgbaOut, Imgproc.COLOR_BGR2RGBA)
            // Double-buffered so the UI never reads the bitmap being written.
            bufferIndex = 1 - bufferIndex
            var bitmap = buffers[bufferIndex]
            if (bitmap == null || bitmap.width != rgbaOut.cols() || bitmap.height != rgbaOut.rows()) {
                bitmap = Bitmap.createBitmap(rgbaOut.cols(), rgbaOut.rows(), Bitmap.Config.ARGB_8888)
                buffers[bufferIndex] = bitmap
            }
            Utils.matToBitmap(rgbaOut, bitmap)
            val tDone = System.nanoTime()

            windowFrames++
            sumConvertInNanos += tConverted - frameStart
            sumInferNanos += tInferred - tConverted
            sumConvertOutNanos += tDone - tInferred
            sumWorkNanos += tDone - frameStart

            val windowSec = (tDone - windowStartNanos) / 1e9f
            if (windowSec >= FPS_WINDOW_SECONDS) {
                // Divide by the measured elapsed time rather than the nominal window,
                // so the overshoot past 10s doesn't inflate the result.
                fps = windowFrames / windowSec
                haveFullWindow = true
                val n = windowFrames
                val meanMs = { total: Long -> total / 1e6f / n }
                // Whatever the window didn't spend working, it spent blocked waiting
                // for the next frame — i.e. bounded by the camera, not by us.
                val waitMs = (windowSec * 1000f - sumWorkNanos / 1e6f) / n
                val busyPct = 100f * (sumWorkNanos / 1e9f) / windowSec
                Log.i(
                    TAG,
                    ("fps: %.1f over %.1fs (%d frames, %s, %s) | wait %.1fms  " +
                        "convert-in %.1fms  infer %.1fms  convert-out %.1fms  busy %.0f%%").format(
                        fps, windowSec, n, loadedModel?.name ?: "?", backendLabel,
                        waitMs, meanMs(sumConvertInNanos), meanMs(sumInferNanos),
                        meanMs(sumConvertOutNanos), busyPct,
                    )
                )
                windowFrames = 0
                windowStartNanos = tDone
                sumConvertInNanos = 0L
                sumInferNanos = 0L
                sumConvertOutNanos = 0L
                sumWorkNanos = 0L
            } else if (!haveFullWindow && windowSec > 0f) {
                // Running average so the readout isn't blank for the first 10 seconds.
                fps = windowFrames / windowSec
            }

            onFrame(FrameResult(bitmap, fps, detection, backendLabel))
        } catch (t: Throwable) {
            Log.e(TAG, "analyze failed", t)
        } finally {
            image.close()
        }
    }

    fun release() {
        ncnn.close()
        litert.close()
        bgr.release()
        rgbaOut.release()
    }
}
