package sh.emilien.RATapp.cv

import android.content.res.AssetManager
import android.util.Log

private const val MODELS_ROOT = "models"
private const val LOG_TAG = "RATCv"

/**
 * One ncnn export under assets/models/<name>/, holding model.ncnn.{param,bin} and
 * optionally the metadata.yaml that ultralytics emits alongside them.
 *
 * Adding a model is a matter of dropping another directory in there — nothing here
 * or in the UI needs changing.
 */
data class ModelChoice(
    val name: String,
    val assetDir: String,
    val inputSize: Int,
    /** ncnn can run this one (model.ncnn.param + .bin are present). */
    val hasNcnn: Boolean = true,
    /** Filename of the .tflite to hand LiteRT, or null when the directory has none. */
    val tfliteAsset: String? = null,
) {
    /** Whether this model has a build for [backend]. */
    fun supports(backend: Backend): Boolean =
        if (backend.needsTflite) tfliteAsset != null else hasNcnn

    companion object {
        private const val DEFAULT_INPUT_SIZE = 640

        /** Every model directory found in assets, sorted by name. */
        fun discover(assets: AssetManager): List<ModelChoice> {
            val names = assets.list(MODELS_ROOT).orEmpty()
            return names.mapNotNull { name ->
                val dir = "$MODELS_ROOT/$name"
                val files = assets.list(dir).orEmpty()
                val hasNcnn = "model.ncnn.param" in files && "model.ncnn.bin" in files
                // One entry per directory, carrying whichever builds it has: the
                // backend is chosen by a toggle, not by picking a different model.
                val tflite = files.firstOrNull { it.endsWith(".tflite") }
                if (!hasNcnn && tflite == null) {
                    Log.w(LOG_TAG, "skipping $dir: no model.ncnn.{param,bin} and no .tflite")
                    return@mapNotNull null
                }
                ModelChoice(name, dir, readInputSize(assets, dir, files), hasNcnn, tflite)
            }.sortedBy { it.name }
        }

        /**
         * ultralytics writes `imgsz:` as a two-element YAML list. The graph bakes the
         * input size into its reshape constants, so feeding the wrong one breaks the
         * model rather than merely degrading it — hence reading it rather than assuming.
         */
        private fun readInputSize(
            assets: AssetManager,
            dir: String,
            files: Array<out String>,
        ): Int {
            if ("metadata.yaml" !in files) return DEFAULT_INPUT_SIZE
            return runCatching {
                val lines = assets.open("$dir/metadata.yaml").bufferedReader().readLines()
                val start = lines.indexOfFirst { it.trimEnd() == "imgsz:" }
                if (start < 0) return@runCatching DEFAULT_INPUT_SIZE
                lines.drop(start + 1)
                    .firstOrNull { it.trimStart().startsWith("- ") }
                    ?.trim()?.removePrefix("- ")?.trim()?.toIntOrNull()
                    ?: DEFAULT_INPUT_SIZE
            }.getOrElse {
                Log.w(LOG_TAG, "could not read imgsz from $dir/metadata.yaml", it)
                DEFAULT_INPUT_SIZE
            }
        }
    }
}
