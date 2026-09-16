package sh.emilien.RATapp.cv

import android.content.res.AssetManager
import org.opencv.core.Mat

/**
 * Which runtime and accelerator to run the model on.
 *
 * The point of having more than one is measurement: ncnn is what the production app
 * ships, LiteRT is the candidate that can reach the Tensor NPU, and the only way to
 * know whether that is worth the migration is to run them against the same camera
 * feed on the same device.
 */
enum class Backend(val label: String, val needsTflite: Boolean) {
    /** ncnn on CPU. The reference: this is what the main app ships today. */
    NCNN_CPU("ncnn cpu", false),

    /** LiteRT on CPU, via XNNPACK. */
    LITERT_CPU("litert cpu", true),

    /** LiteRT on the GPU delegate. */
    LITERT_GPU("litert gpu", true),

    /**
     * LiteRT via NNAPI, which is how a normal app reaches the Tensor TPU.
     *
     * NNAPI is deprecated as of Android 15 and generally requires a fully int8
     * quantized model to land on the NPU rather than silently falling back to CPU —
     * check the reported fps against LITERT_CPU rather than trusting the label.
     */
    LITERT_NNAPI("litert nnapi", true),
}

/**
 * A loaded model that can be run on frames.
 *
 * Implementations own their own resources and are not thread-safe: [load], [process]
 * and [close] must all run on the single analysis thread.
 */
interface InferenceEngine {
    val isLoaded: Boolean

    /** What actually ended up running, which may differ from what was requested. */
    val activeLabel: String

    /** Replaces any previously loaded model. Blocking; call off the main thread. */
    fun load(assets: AssetManager, model: ModelChoice, backend: Backend): Boolean

    /**
     * Runs inference on [frame] (BGR, CV_8UC3) and draws the overlay onto it in
     * place. Returns null when nothing cleared the confidence threshold.
     */
    fun process(frame: Mat): Detection?

    fun close()
}

/** Confidence a box must clear to count, matching detector.cpp's kConfThreshold. */
const val CONF_THRESHOLD = 0.3f
