package sh.emilien.RATapp.ble

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** ATT opcode + attribute handle sit in front of every write payload. */
const val ATT_HEADER_BYTES = 3

/**
 * The messages on schemas/app-message.schema.json, hand-written rather than generated.
 *
 * quicktype collapses a flat `anyOf` union into a single all-nullable class, which is
 * worse than useless for building a message; this stays in step with the schema by
 * hand instead. Every kind the app sends or receives lives here.
 */
@Serializable
enum class GameMode {
    @SerialName("endless")
    ENDLESS,

    @SerialName("ten")
    TEN,
}

/** Hits needed to finish a [GameMode.TEN] run. */
const val TEN_MODE_HITS_REQUIRED = 10

@Serializable
sealed interface AppMessage {

    @Serializable
    @SerialName("im-alive")
    data object ImAlive : AppMessage

    @Serializable
    @SerialName("setup-done")
    data object SetupDone : AppMessage

    @Serializable
    @SerialName("mtu")
    data class Mtu(val mtu: Int) : AppMessage

    @Serializable
    @SerialName("set-horizontal-servo")
    data class SetHorizontalServo(val angle: Int) : AppMessage

    @Serializable
    @SerialName("set-vertical-servo")
    data class SetVerticalServo(val angle: Int) : AppMessage

    @Serializable
    @SerialName("set-calibration-point")
    data class SetCalibrationPoint(val index: Int, val x: Float, val y: Float) : AppMessage

    @Serializable
    @SerialName("register-calibration")
    data object RegisterCalibration : AppMessage

    @Serializable
    @SerialName("next-target")
    data object NextTarget : AppMessage

    @Serializable
    @SerialName("set-game-mode")
    data class SetGameMode(val mode: GameMode) : AppMessage

    /**
     * One inference readout, in upright analyzer frame pixels. (-1, -1) means no
     * detection on that frame — the controller counts those to decide when a
     * trajectory has ended.
     */
    @Serializable
    @SerialName("inference-result")
    data class InferenceResult(val x: Double, val y: Double) : AppMessage

    @Serializable
    @SerialName("set-target-virtual-position")
    data class SetTargetVirtualPosition(val x: Float, val y: Float) : AppMessage

    @Serializable
    @SerialName("set-ball-virtual-position")
    data class SetBallVirtualPosition(val x: Float, val y: Float) : AppMessage

    @Serializable
    @SerialName("set-score")
    data class SetScore(val score: Float) : AppMessage
}

/**
 * `kind` rather than kotlinx's default `type`, matching the schema's discriminator.
 * [ignoreUnknownKeys] keeps a message gaining a field from breaking an older app.
 */
val appMessageJson: Json = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
}

/** Serializes [message] to its bare on-the-wire form. */
fun encodeAppMessage(message: AppMessage): String =
    appMessageJson.encodeToString<AppMessage>(message)

/** Parses a notification payload, or null if it is not a message we know. */
fun decodeAppMessage(text: String): AppMessage? =
    runCatching { appMessageJson.decodeFromString<AppMessage>(text) }.getOrNull()

/**
 * One decimal place is all the precision a stretch-resized 640x640 model output has,
 * and the rounding is what keeps a result inside the ATT write budget.
 */
internal fun Float.round1(): Double = (this * 10f).roundToInt() / 10.0
