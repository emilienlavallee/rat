package sh.emilien.RATapp.cv

import android.content.res.AssetManager
import org.opencv.core.Mat

/**
 * Adapter over the existing ncnn [Detector], so it can be swapped against LiteRT
 * behind [InferenceEngine].
 *
 * Deliberately thin: the native path stays exactly as it was, so it remains a valid
 * baseline to measure the LiteRT backends against.
 */
class NcnnEngine : InferenceEngine {

    private val detector = Detector()

    override val isLoaded: Boolean get() = detector.isLoaded

    override val activeLabel: String get() = Backend.NCNN_CPU.label

    override fun load(assets: AssetManager, model: ModelChoice, backend: Backend): Boolean {
        if (backend.needsTflite) return false
        return detector.load(assets, model)
    }

    override fun process(frame: Mat): Detection? = detector.process(frame)

    override fun close() = detector.close()
}
