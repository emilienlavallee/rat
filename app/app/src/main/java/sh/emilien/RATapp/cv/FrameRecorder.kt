package sh.emilien.RATapp.cv

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "RATCv"

/** Frames queued ahead of the writer before we start dropping instead of stalling analysis. */
private const val QUEUE_CAPACITY = 8

private const val JPEG_QUALITY = 95

/**
 * Writes camera frames to `Pictures/frames_<date>/` on shared storage, one JPEG each.
 *
 * [submit] takes ownership of the Mat it is given and returns immediately; the encode
 * and the MediaStore insert happen on a private writer thread, so recording never slows
 * the analysis loop. If the writer falls behind, frames are dropped rather than queued
 * without bound — [dropped] counts them.
 */
class FrameRecorder(private val context: Context, val folder: String) {

    private val queue = ArrayBlockingQueue<Mat>(QUEUE_CAPACITY)
    private val writtenCount = AtomicInteger(0)
    private val droppedCount = AtomicInteger(0)

    /** Frames successfully written so far. */
    val written: Int get() = writtenCount.get()

    /** Frames discarded because the writer could not keep up. */
    val dropped: Int get() = droppedCount.get()

    @Volatile
    private var running = true

    private val jpegParams = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)

    private val thread = Thread({ writeLoop() }, "frame-recorder").apply {
        priority = Thread.MIN_PRIORITY
        start()
    }

    /**
     * Hands [frame] (BGR, CV_8UC3) over to the writer thread, which releases it when done.
     * The caller must not touch the Mat afterwards. Returns false if the frame was dropped.
     */
    fun submit(frame: Mat): Boolean {
        if (!running || !queue.offer(frame)) {
            frame.release()
            droppedCount.incrementAndGet()
            return false
        }
        return true
    }

    /** Stops accepting frames and waits for the queued ones to finish writing. */
    fun stop() {
        running = false
        thread.join()
        jpegParams.release()
        Log.i(TAG, "recording stopped: $folder — ${written} frames written, ${dropped} dropped")
    }

    private fun writeLoop() {
        while (true) {
            // Poll rather than take, so a stop with an empty queue doesn't block forever.
            val frame = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (frame == null) {
                if (!running) return
                continue
            }
            try {
                write(frame, writtenCount.get())
                writtenCount.incrementAndGet()
            } catch (t: Throwable) {
                droppedCount.incrementAndGet()
                Log.e(TAG, "frame write failed", t)
            } finally {
                frame.release()
            }
        }
    }

    private fun write(frame: Mat, index: Int) {
        val encoded = MatOfByte()
        try {
            if (!Imgcodecs.imencode(".jpg", frame, encoded, jpegParams)) {
                error("imencode failed")
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "frame_%05d.jpg".format(index))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folder")
                // Hides the file from the gallery until the bytes are actually there.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media
                .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: error("MediaStore insert refused")
            resolver.openOutputStream(uri)!!.use { it.write(encoded.toArray()) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } finally {
            encoded.release()
        }
    }

    companion object {
        /** `<prefix>_20260831_142530` — the folder created under `Pictures/`. */
        fun newFolderName(prefix: String): String =
            prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
}
