package sh.emilien.RATapp.cv

import android.content.res.AssetManager
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

private const val TAG = "RATCv"

/**
 * Runs the model through LiteRT, optionally on the GPU delegate or on NNAPI (which is
 * how a normal app reaches the Tensor NPU).
 *
 * The tensor contract here is NOT the same as the ncnn export's, and was read off the
 * exported graph rather than assumed:
 *
 *  - input  [1, 3, S, S] NCHW, RGB, 0..1        (ncnn takes the same pixels but packs HWC)
 *  - output [1, N, 6] = x1, y1, x2, y2, conf, class, in input-resolution pixels,
 *    NMS already applied, zero-padded to N and sorted by confidence.
 *
 * Verified against a labelled frame: ground truth xyxy (364.6, 303.4, 377.1, 313.7)
 * came back as (365.4, 305.4, 375.2, 314.3) at conf 0.31.
 */
class LiteRtEngine : InferenceEngine {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnapiDelegate: NnApiDelegate? = null

    private var inputSize = 640
    private var inputIsQuantized = false
    private var inputScale = 1f
    private var inputZeroPoint = 0
    private var inputIsNchw = true

    private var outputIsQuantized = false
    private var outputScale = 1f
    private var outputZeroPoint = 0
    /**
     * The two shapes ultralytics emits, which differ between the fp32 and int8
     * exports of the *same* weights — so this is detected, never assumed:
     *
     *  - [NMS_XYXY]  [1, 300, 6] = x1,y1,x2,y2,conf,class, NMS already applied
     *  - [RAW_CXCYWH] [1, 4+numClasses, 8400] = raw head, class scores per anchor
     */
    private enum class OutputFormat { NMS_XYXY, RAW_CXCYWH }

    private var outputFormat = OutputFormat.NMS_XYXY
    private var dim1 = 0
    private var dim2 = 0

    /**
     * Whether box coordinates come back in 0..1 or in input-resolution pixels. The
     * fp32 export uses pixels and the int8 export uses normalized, so this is
     * measured from the first real output rather than assumed.
     */
    private var normalizedBoxes: Boolean? = null

    /** Detections below this are ignored; adjustable so a lossy int8 export is still testable. */
    @Volatile
    var confThreshold: Float = CONF_THRESHOLD

    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private val resized = Mat()
    private val rgb = Mat()
    private val float32 = Mat()
    private val planes = ArrayList<Mat>(3)
    private var pixels = ByteArray(0)
    private var planar = FloatArray(0)

    /** Wall time of the last preprocess and interpreter run. */
    @Volatile
    var lastPrepareMs: Float = 0f
        private set

    @Volatile
    var lastInvokeMs: Float = 0f
        private set

    /** True when the graph itself is quantized, regardless of the I/O tensor dtype. */
    private var quantizedGraph = false

    private var label = ""

    override val isLoaded: Boolean get() = interpreter != null
    override val activeLabel: String get() = label

    override fun load(assets: AssetManager, model: ModelChoice, backend: Backend): Boolean {
        close()
        val file = model.tfliteAsset ?: run {
            Log.e(TAG, "${model.name} has no .tflite; cannot run ${backend.label}")
            return false
        }
        return try {
            val options = Interpreter.Options()
            when (backend) {
                Backend.LITERT_GPU -> {
                    val compat = CompatibilityList()
                    if (!compat.isDelegateSupportedOnThisDevice) {
                        Log.w(TAG, "GPU delegate unsupported on this device")
                        return false
                    }
                    gpuDelegate = GpuDelegate(compat.bestOptionsForThisDevice)
                    options.addDelegate(gpuDelegate)
                }
                Backend.LITERT_NNAPI -> {
                    nnapiDelegate = NnApiDelegate()
                    options.addDelegate(nnapiDelegate)
                }
                else -> options.setNumThreads(4) // XNNPACK is on by default
            }

            val buffer = assets.openFd("${model.assetDir}/$file").use { fd ->
                fd.createInputStream().use { stream ->
                    stream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        fd.startOffset,
                        fd.declaredLength,
                    )
                }
            }
            val interp = Interpreter(buffer, options)

            val inTensor = interp.getInputTensor(0)
            val outTensor = interp.getOutputTensor(0)
            val inShape = inTensor.shape()
            val outShape = outTensor.shape()

            // [1,3,S,S] is NCHW; [1,S,S,3] is NHWC. Distinguished by which axis is 3.
            inputIsNchw = inShape.size == 4 && inShape[1] == 3
            inputSize = if (inputIsNchw) inShape[2] else inShape[1]

            val inType = inTensor.dataType().name
            inputIsQuantized = inType == "UINT8" || inType == "INT8"
            inTensor.quantizationParams().let {
                inputScale = if (it.scale != 0f) it.scale else 1f
                inputZeroPoint = it.zeroPoint
            }
            val outType = outTensor.dataType().name
            outputIsQuantized = outType == "UINT8" || outType == "INT8"
            outTensor.quantizationParams().let {
                outputScale = if (it.scale != 0f) it.scale else 1f
                outputZeroPoint = it.zeroPoint
            }

            dim1 = outShape[1]
            dim2 = outShape[2]
            // [1,300,6]: many detections, few columns. [1,5,8400]: few rows, many
            // anchors. The axis with more entries tells them apart.
            outputFormat = if (dim1 > dim2) OutputFormat.NMS_XYXY else OutputFormat.RAW_CXCYWH
            normalizedBoxes = null

            Log.i(
                TAG,
                "litert loaded ${model.name}/$file in=${inShape.joinToString("x")} " +
                    "($inType, nchw=$inputIsNchw, size=$inputSize) " +
                    "out=${outShape.joinToString("x")} ($outType) format=$outputFormat",
            )

            inputBuffer = ByteBuffer.allocateDirect(inTensor.numBytes())
                .order(ByteOrder.nativeOrder())
            outputBuffer = ByteBuffer.allocateDirect(outTensor.numBytes())
                .order(ByteOrder.nativeOrder())
            pixels = ByteArray(inputSize * inputSize * 3)
            planar = FloatArray(inputSize * inputSize * 3)

            // A fully quantized export still presents float32 I/O — Quantize and
            // Dequantize wrap an int8 interior — so the I/O dtype says nothing about
            // whether the graph is quantized. The Java API exposes no way to inspect
            // interior tensors, so fall back to the exported filename, which is what
            // actually distinguishes the two builds we ship.
            quantizedGraph = inputIsQuantized || file.contains("int8")

            interpreter = interp
            label = "${backend.label} ${if (quantizedGraph) "int8" else "fp32"}"
            true
        } catch (t: Throwable) {
            Log.e(TAG, "litert load failed", t)
            close()
            false
        }
    }

    override fun process(frame: Mat): Detection? {
        val interp = interpreter ?: return null
        val input = inputBuffer ?: return null
        val output = outputBuffer ?: return null

        // Same stretch-resize (no letterbox) and BGR->RGB as detector.cpp, so a
        // backend comparison measures the runtime and not the preprocessing.
        val t0 = System.nanoTime()
        Imgproc.resize(frame, resized, Size(inputSize.toDouble(), inputSize.toDouble()))
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)

        input.rewind()
        writeInput(input)
        input.rewind()
        output.rewind()

        val t1 = System.nanoTime()
        interp.run(input, output)
        val t2 = System.nanoTime()
        output.rewind()

        lastPrepareMs = (t1 - t0) / 1e6f
        lastInvokeMs = (t2 - t1) / 1e6f

        val det = postprocess(frame.cols(), frame.rows())
        drawOverlay(frame, det)
        return det
    }

    /**
     * Fills the input tensor.
     *
     * The float path deliberately avoids a per-element loop: at 640x640x3 that is
     * 1.2M ByteBuffer writes per frame, which cost more than the inference itself and
     * made the LiteRT backends look slower than they are. Instead OpenCV does the
     * scale and the channel split natively, and each plane lands in one bulk copy.
     */
    private fun writeInput(input: ByteBuffer) {
        if (inputIsQuantized) {
            // Quantized inputs are byte-for-byte, so the packed pixels go straight in.
            rgb.get(0, 0, pixels)
            val n = inputSize * inputSize
            if (inputIsNchw) {
                for (c in 0 until 3) for (p in 0 until n) input.put(pixels[p * 3 + c])
            } else {
                input.put(pixels)
            }
            return
        }

        rgb.convertTo(float32, CvType.CV_32FC3, 1.0 / 255.0)
        val floats = input.asFloatBuffer()
        if (inputIsNchw) {
            planes.forEach { it.release() }
            planes.clear()
            Core.split(float32, planes)
            val n = inputSize * inputSize
            for (c in 0 until 3) {
                planes[c].get(0, 0, planar)
                floats.put(planar, 0, n)
            }
        } else {
            float32.get(0, 0, planar)
            floats.put(planar)
        }
    }

    /** Element at [a] along the first output axis, [b] along the second. */
    private fun value(a: Int, b: Int): Float {
        val buf = outputBuffer!!
        val index = a * dim2 + b
        return if (outputIsQuantized) {
            (buf.get(index).toInt() - outputZeroPoint) * outputScale
        } else {
            buf.getFloat(index * 4)
        }
    }

    /**
     * Picks the single highest-confidence detection with no NMS of our own, matching
     * detector.cpp so the backends stay comparable.
     */
    private fun postprocess(frameW: Int, frameH: Int): Detection? =
        when (outputFormat) {
            OutputFormat.NMS_XYXY -> bestFromNms(frameW, frameH)
            OutputFormat.RAW_CXCYWH -> bestFromRaw(frameW, frameH)
        }

    /** [1, N, 6] = x1, y1, x2, y2, conf, class. Rows arrive sorted, but don't rely on it. */
    private fun bestFromNms(frameW: Int, frameH: Int): Detection? {
        var bestConf = confThreshold
        var best: Detection? = null
        for (i in 0 until dim1) {
            val conf = value(i, 4)
            if (!conf.isFinite() || conf <= bestConf) continue
            val x1 = value(i, 0)
            val y1 = value(i, 1)
            val x2 = value(i, 2)
            val y2 = value(i, 3)
            if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) continue
            if (normalizedBoxes == null) detectBoxScale(maxOf(x1, y1, x2, y2))
            val (sx, sy) = boxScale(frameW, frameH)
            bestConf = conf
            // Corner-based on the wire; Detection is centre-based like record_infer's.
            best = Detection(
                conf,
                (x1 + x2) / 2f * sx, (y1 + y2) / 2f * sy,
                (x2 - x1) * sx, (y2 - y1) * sy,
            )
        }
        return best
    }

    /** [1, 4+numClasses, A] = cx, cy, w, h then per-class scores, one column per anchor. */
    private fun bestFromRaw(frameW: Int, frameH: Int): Detection? {
        var bestConf = confThreshold
        var best: Detection? = null
        for (i in 0 until dim2) {
            var conf = 0f
            for (c in 4 until dim1) {
                val v = value(c, i)
                if (v > conf) conf = v
            }
            if (!conf.isFinite() || conf <= bestConf) continue
            val cx = value(0, i)
            val cy = value(1, i)
            val bw = value(2, i)
            val bh = value(3, i)
            if (!cx.isFinite() || !cy.isFinite() || !bw.isFinite() || !bh.isFinite()) continue
            if (normalizedBoxes == null) detectBoxScale(maxOf(cx, cy))
            val (sx, sy) = boxScale(frameW, frameH)
            bestConf = conf
            best = Detection(conf, cx * sx, cy * sy, bw * sx, bh * sy)
        }
        return best
    }

    /**
     * The fp32 export emits input-resolution pixels and the int8 export emits 0..1,
     * from the same weights — so decide from the data. A normalized centre cannot
     * plausibly exceed ~1.5, while a pixel one runs to the input size.
     */
    private fun detectBoxScale(sample: Float) {
        val normalized = sample <= 1.5f
        normalizedBoxes = normalized
        Log.i(TAG, "box coords: sample=$sample -> normalized=$normalized")
    }

    private fun boxScale(frameW: Int, frameH: Int): Pair<Float, Float> =
        if (normalizedBoxes == true) {
            frameW.toFloat() to frameH.toFloat()
        } else {
            frameW.toFloat() / inputSize to frameH.toFloat() / inputSize
        }

    private fun drawOverlay(frame: Mat, det: Detection?) {
        if (det != null) {
            Imgproc.rectangle(
                frame,
                Rect(
                    (det.x - det.w / 2).toInt(),
                    (det.y - det.h / 2).toInt(),
                    det.w.toInt(),
                    det.h.toInt(),
                ),
                Scalar(0.0, 255.0, 0.0),
                2,
            )
            Imgproc.putText(
                frame,
                "conf=%.2f".format(det.conf),
                Point((det.x - det.w / 2).toDouble(), (det.y - det.h / 2 - 8).toDouble()),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, Scalar(0.0, 255.0, 0.0), 2,
            )
        } else {
            Imgproc.putText(
                frame, "no detection", Point(10.0, 60.0),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, Scalar(0.0, 0.0, 255.0), 2,
            )
        }
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        nnapiDelegate?.close()
        nnapiDelegate = null
        inputBuffer = null
        outputBuffer = null
        resized.release()
        rgb.release()
        float32.release()
        planes.forEach { it.release() }
        planes.clear()
        label = ""
    }
}
