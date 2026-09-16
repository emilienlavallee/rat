package sh.emilien.RATapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import sh.emilien.RATapp.ble.BleManager
import sh.emilien.RATapp.ble.ConnectionState
import sh.emilien.RATapp.ble.DiscoveredDevice
import sh.emilien.RATapp.ble.GameMode
import sh.emilien.RATapp.ble.KIND_BALL_VIRTUAL_POSITION
import sh.emilien.RATapp.ble.KIND_TARGET_VIRTUAL_POSITION
import sh.emilien.RATapp.ble.TEN_MODE_HITS_REQUIRED

/**
 * A point on the virtual wall, in centimetres: x in 0..[VIRTUAL_WALL_WIDTH_CM],
 * y in 0..[VIRTUAL_WALL_HEIGHT_CM], origin top-left.
 */
data class VirtualPoint(val x: Float, val y: Float)

const val VIRTUAL_WALL_WIDTH_CM = 300f
const val VIRTUAL_WALL_HEIGHT_CM = 200f

/**
 * One finished attempt: where the target was, and where the ball ended up. [ball] is
 * null when the target was replaced before any ball position came back.
 */
data class WallSnapshot(val target: VirtualPoint, val ball: VirtualPoint?)

/** How many past attempts the run screen's history dropdown keeps. */
const val MAX_WALL_HISTORY = 50

data class BluetoothUiState(
    val scanning: Boolean = false,
    val devices: List<DiscoveredDevice> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Idle,
)

class BluetoothViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)

    private val scanning: StateFlow<Boolean> = bleManager.scanning
    private val devices: StateFlow<List<DiscoveredDevice>> = bleManager.devices
    private val connectionState: StateFlow<ConnectionState> = bleManager.connectionState

    private val _horizontalAngle = MutableStateFlow(90)
    val horizontalAngle: StateFlow<Int> = _horizontalAngle.asStateFlow()

    private val _verticalAngle = MutableStateFlow(90)
    val verticalAngle: StateFlow<Int> = _verticalAngle.asStateFlow()

    private val _targetVirtualPosition = MutableStateFlow<VirtualPoint?>(null)

    /** Where the target is aiming on the virtual wall, or null before the first push. */
    val targetVirtualPosition: StateFlow<VirtualPoint?> = _targetVirtualPosition.asStateFlow()

    private val _ballVirtualPosition = MutableStateFlow<VirtualPoint?>(null)

    /** Where the ball is on the virtual wall, or null before the first push. */
    val ballVirtualPosition: StateFlow<VirtualPoint?> = _ballVirtualPosition.asStateFlow()

    private val _wallHistory = MutableStateFlow<List<WallSnapshot>>(emptyList())

    /** Completed target/ball pairs from this run, oldest first. */
    val wallHistory: StateFlow<List<WallSnapshot>> = _wallHistory.asStateFlow()

    private val _score = MutableStateFlow(0f)

    /**
     * Score for the current run, as pushed by the controller. In endless mode this is
     * the running average of how close each shot landed, as a fraction of the maximum
     * possible distance; in ten mode it is hits over attempts.
     */
    val score: StateFlow<Float> = _score.asStateFlow()

    private val _gameMode = MutableStateFlow(GameMode.ENDLESS)

    /** Mode the current run is being played in. */
    val gameMode: StateFlow<GameMode> = _gameMode.asStateFlow()

    private val _attempts = MutableStateFlow(0)

    /** Shots taken this run. The controller scores exactly once per shot. */
    val attempts: StateFlow<Int> = _attempts.asStateFlow()

    private val _hits = MutableStateFlow(0)

    /**
     * Hits landed this run, recovered from the hits/attempts ratio the controller
     * sends as the score. Only meaningful in [GameMode.TEN].
     */
    val hits: StateFlow<Int> = _hits.asStateFlow()

    /** True once a ten-mode run has landed all ten hits. */
    val runFinished: StateFlow<Boolean> = combine(_gameMode, _hits) { mode, hits ->
        mode == GameMode.TEN && hits >= TEN_MODE_HITS_REQUIRED
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    /** ATT MTU agreed with the peripheral; an inference result must fit inside it. */
    val negotiatedMtu: StateFlow<Int> = bleManager.negotiatedMtu

    /**
     * Writes one inference result. Handed to [sh.emilien.RATapp.cv.CvViewModel]
     * as its [sh.emilien.RATapp.cv.InferenceSink], so the CV side never has to
     * know about BLE.
     */
    suspend fun sendInferenceResult(x: Float, y: Float): Boolean =
        bleManager.sendInferenceResult(x, y)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            bleManager.virtualPositions.collect { position ->
                val point = VirtualPoint(x = position.x, y = position.y)
                when (position.kind) {
                    KIND_TARGET_VIRTUAL_POSITION -> {
                        _targetVirtualPosition.value?.let { previous ->
                            _wallHistory.value = (
                                _wallHistory.value + WallSnapshot(
                                    target = previous,
                                    ball = _ballVirtualPosition.value,
                                )
                                ).takeLast(MAX_WALL_HISTORY)
                        }
                        _targetVirtualPosition.value = point
                        _ballVirtualPosition.value = null
                    }

                    KIND_BALL_VIRTUAL_POSITION -> _ballVirtualPosition.value = point
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            bleManager.scores.collect { score ->
                _score.value = score
                val attempts = _attempts.value + 1
                _attempts.value = attempts
                _hits.value = (score * attempts).roundToInt()
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            bleManager.connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    bleManager.stopScan()
                } else {
                    resetRun()
                }
            }
        }
    }

    val uiState: StateFlow<BluetoothUiState> = combine(
        scanning,
        devices,
        connectionState,
    ) { scanning, devices, connectionState ->
        BluetoothUiState(
            scanning = scanning,
            devices = devices,
            connectionState = connectionState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BluetoothUiState(),
    )

    val isBluetoothAvailable: Boolean
        get() = bleManager.isBluetoothAvailable

    fun startScan() {
        bleManager.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    fun restartScan() {
        bleManager.restartScan()
    }

    fun connect(device: DiscoveredDevice) {
        bleManager.connect(device)
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    fun sendImAlive() {
        bleManager.sendImAlive()
    }

    fun sendSetupDone() {
        bleManager.sendSetupDone()
    }

    fun setHorizontalAngle(angle: Int) {
        _horizontalAngle.value = angle.coerceIn(0, 180)
        bleManager.sendHorizontalServo(_horizontalAngle.value)
    }

    fun sendCalibrationPoint(index: Int, x: Float, y: Float) {
        bleManager.sendCalibrationPoint(index, x, y)
    }

    fun sendRegisterCalibration() {
        bleManager.sendRegisterCalibration()
    }

    fun sendNextTarget() {
        bleManager.sendNextTarget()
    }

    /** Clears per-run state; called when the run screen is entered. */
    fun resetRun() {
        _score.value = 0f
        _attempts.value = 0
        _hits.value = 0
        _wallHistory.value = emptyList()
        _targetVirtualPosition.value = null
        _ballVirtualPosition.value = null
    }

    /** Starts a fresh run in [mode]. The controller re-aims and zeroes its own tally. */
    fun setGameMode(mode: GameMode) {
        _gameMode.value = mode
        resetRun()
        bleManager.sendGameMode(mode)
    }

    fun setVerticalAngle(angle: Int) {
        _verticalAngle.value = angle.coerceIn(0, 180)
        bleManager.sendVerticalServo(_verticalAngle.value)
    }

    override fun onCleared() {
        bleManager.release()
    }
}