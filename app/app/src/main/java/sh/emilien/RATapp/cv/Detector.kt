package sh.emilien.RATapp.cv

import android.content.res.AssetManager
import org.opencv.core.Mat

/** Centre-based box in frame pixels, matching record_infer's Detection struct. */
data class Detection(val conf: Float, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Thin wrapper over the ncnn detector in cpp/detector.cpp.
 *
 * The native methods are all instance methods so their JNI symbols resolve against
 * this class directly. Not thread-safe: [load] and [process] must run on a single
 * thread, and [close] only once that thread is done.
 */
class Detector {

    private var handle = 0L

    val isLoaded: Boolean get() = handle != 0L

    /**
     * Loads [model] from assets, replacing any previously loaded one. Blocking, so
     * call this off the main thread. Returns false if loading failed.
     */
    fun load(assets: AssetManager, model: ModelChoice): Boolean {
        close()
        handle = nativeInit(assets, model.assetDir, model.inputSize)
        return handle != 0L
    }

    /**
     * Runs inference on [frame] (BGR, CV_8UC3) and draws the overlay onto it in place.
     * Returns null when nothing cleared the confidence threshold.
     */
    fun process(frame: Mat): Detection? {
        if (handle == 0L) return null
        val r = nativeProcess(handle, frame.nativeObj)
        return if (r.size == 5) Detection(r[0], r[1], r[2], r[3], r[4]) else null
    }

    fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    private external fun nativeInit(
        assets: AssetManager,
        modelDir: String,
        inputSize: Int,
    ): Long
    private external fun nativeProcess(handle: Long, matAddr: Long): FloatArray
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("RAT_cv")
        }
    }
}
