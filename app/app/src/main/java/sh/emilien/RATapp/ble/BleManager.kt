package sh.emilien.RATapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val device: DiscoveredDevice) : ConnectionState
    data class Failed(val device: DiscoveredDevice, val message: String) : ConnectionState
}

@SuppressLint("MissingPermission")
class BleManager(context: Context) {

    private val serviceUuid = UUID.fromString("d6de50b6-456a-4e49-a172-a109443a83dc")
    private val receiveCmdCharacteristicUuid =
        UUID.fromString("1696cc02-2f9d-4514-b673-5bd31938622f")
    private val sendNotifCharacteristicUuid =
        UUID.fromString("37f0faa0-b94e-4309-84ba-980d2a9bebc6")

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY, inlined for minSdk 24. */
        const val WRITE_REQUEST_BUSY = 201
        const val WRITE_BUSY_RETRIES = 3
        const val WRITE_BUSY_BACKOFF_MS = 10L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val discovered = LinkedHashMap<String, DiscoveredDevice>()
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectingDevice: DiscoveredDevice? = null
    @Volatile
    private var receiveCmdCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var sendNotifCharacteristic: BluetoothGattCharacteristic? = null
    private val _negotiatedMtu = MutableStateFlow(23)

    /** ATT MTU agreed with the peripheral; the inference batch size is derived from it. */
    val negotiatedMtu: StateFlow<Int> = _negotiatedMtu.asStateFlow()

    private val _virtualPositions = MutableSharedFlow<VirtualPosition>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Target and ball positions on the virtual wall, pushed by the controller. */
    val virtualPositions: SharedFlow<VirtualPosition> = _virtualPositions.asSharedFlow()

    private val _scores = MutableSharedFlow<Float>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Run score, pushed by the controller. */
    val scores: SharedFlow<Float> = _scores.asSharedFlow()

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    init {
        Log.i("RATBle", "BleManager created (build marker: lifecycle-messages-v1)")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val entry = DiscoveredDevice(
                address = device.address,
                name = device.name,
                rssi = result.rssi,
            )
            discovered[device.address] = entry
            _devices.value = discovered.values.sortedByDescending { it.rssi }
            Log.d("RATBle", "Scanned ${device.name ?: "unknown"} ${device.address} rssi=${result.rssi}")
        }
    }

    val isBluetoothAvailable: Boolean
        get() = bluetoothAdapter != null && scanner != null

    fun startScan() {
        if (scanner == null || _scanning.value) return
        discovered.clear()
        _devices.value = emptyList()
        _scanning.value = true
        scanner.startScan(emptyList(), scanSettings, scanCallback)
    }

    fun stopScan() {
        scanner?.stopScan(scanCallback)
        _scanning.value = false
    }

    fun restartScan() {
        scanner?.stopScan(scanCallback)
        scanner?.startScan(emptyList(), scanSettings, scanCallback)
    }

    fun connect(device: DiscoveredDevice) {
        val bluetoothDevice =
            bluetoothAdapter?.getRemoteDevice(device.address) ?: return
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        connectingDevice = device
        bluetoothGatt = bluetoothDevice.connectGatt(
            null,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
        _connectionState.value = ConnectionState.Connecting
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectingDevice = null
        _connectionState.value = ConnectionState.Idle
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Negotiate the largest possible ATT MTU first; service
                        // discovery continues from onMtuChanged.
                        if (!gatt.requestMtu(517)) {
                            Log.w("RATBle", "requestMtu(517) failed to enqueue, continuing with default MTU")
                            gatt.discoverServices()
                        }
                    } else {
                        reportFailure("Connection failed (status $status)")
                    }
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        mainHandler.post {
                            _connectionState.value = ConnectionState.Idle
                        }
                    } else {
                        reportFailure("Connection failed (status $status)")
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            _negotiatedMtu.value = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            Log.i(
                "RATBle",
                "onMtuChanged mtu=$mtu status=$status (using ${_negotiatedMtu.value})",
            )
            gatt.discoverServices()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(
                "RATBle",
                "onDescriptorWrite ${descriptor.uuid} status=$status (SUCCESS=${BluetoothGatt.GATT_SUCCESS})",
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleFrame(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleFrame(value)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("RATBle", "Services discovered: ${gatt.services.size}")
                for (service in gatt.services) {
                    Log.i("RATBle", "  service ${service.uuid}")
                    for (ch in service.characteristics) {
                        Log.i("RATBle", "    characteristic ${ch.uuid} properties=${ch.properties}")
                    }
                }
                val service = gatt.getService(serviceUuid)
                if (service == null) {
                    Log.w("RATBle", "Target service $serviceUuid NOT found")
                }
                receiveCmdCharacteristic =
                    service?.getCharacteristic(receiveCmdCharacteristicUuid)
                if (service != null && receiveCmdCharacteristic == null) {
                    Log.w("RATBle", "Target characteristic $receiveCmdCharacteristicUuid NOT found in service")
                }
                sendNotifCharacteristic =
                    service?.getCharacteristic(sendNotifCharacteristicUuid)
                if (service != null && sendNotifCharacteristic == null) {
                    Log.w("RATBle", "Notification characteristic $sendNotifCharacteristicUuid NOT found in service")
                }
                val device = connectingDevice
                if (device != null) {
                    mainHandler.post {
                        _connectionState.value = ConnectionState.Connected(device)
                    }
                }
                if (sendNotifCharacteristic != null) {
                    if (subscribeNotifications(gatt)) {
                        announceMtu(gatt)
                    }
                }
            } else {
                reportFailure("Service discovery failed (status $status)")
            }
        }
    }

    private fun subscribeNotifications(gatt: BluetoothGatt): Boolean {
        val characteristic = sendNotifCharacteristic ?: return false
        val enabled = gatt.setCharacteristicNotification(characteristic, true)
        if (!enabled) {
            Log.w("RATBle", "setCharacteristicNotification failed")
            return false
        }
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w("RATBle", "CCCD descriptor missing on notification characteristic")
            return false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            Log.i("RATBle", "writeDescriptor(CCCD) status=$status")
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private fun handleFrame(value: ByteArray) {
        val text = String(value, Charsets.UTF_8)
        when (val message = decodeAppMessage(text)) {
            is AppMessage.SetTargetVirtualPosition -> _virtualPositions.tryEmit(
                VirtualPosition(KIND_TARGET_VIRTUAL_POSITION, message.x, message.y),
            )

            is AppMessage.SetBallVirtualPosition -> _virtualPositions.tryEmit(
                VirtualPosition(KIND_BALL_VIRTUAL_POSITION, message.x, message.y),
            )

            is AppMessage.SetScore -> _scores.tryEmit(message.score)

            else -> Log.d("RATBle", "Ignoring unrecognized notification: $text")
        }
    }

    private fun announceMtu(gatt: BluetoothGatt) {
        val characteristic = receiveCmdCharacteristic ?: run {
            Log.w("RATBle", "announceMtu: command characteristic missing, skipping")
            return
        }
        val payload = encodeAppMessage(AppMessage.Mtu(_negotiatedMtu.value))
            .toByteArray(Charsets.UTF_8)
        val ok = writeCharacteristic(gatt, characteristic, payload)
        Log.i("RATBle", "announceMtu: sent mtu=${_negotiatedMtu.value}, write returned $ok")
    }

    fun sendHorizontalServo(angle: Int): Boolean =
        send(AppMessage.SetHorizontalServo(angle))

    fun sendVerticalServo(angle: Int): Boolean =
        send(AppMessage.SetVerticalServo(angle))

    fun sendCalibrationPoint(index: Int, x: Float, y: Float): Boolean =
        send(AppMessage.SetCalibrationPoint(index, x, y))

    fun sendRegisterCalibration(): Boolean = send(AppMessage.RegisterCalibration)

    fun sendNextTarget(): Boolean = send(AppMessage.NextTarget)

    fun sendGameMode(mode: GameMode): Boolean = send(AppMessage.SetGameMode(mode))

    fun sendImAlive(): Boolean = send(AppMessage.ImAlive)

    fun sendSetupDone(): Boolean = send(AppMessage.SetupDone)

    /** Writes one message to the command characteristic. */
    private fun send(message: AppMessage): Boolean {
        val gatt = bluetoothGatt ?: run {
            Log.w("RATBle", "send $message: no GATT connection")
            return false
        }
        val characteristic = receiveCmdCharacteristic ?: run {
            Log.w("RATBle", "send $message: command characteristic not found")
            return false
        }
        val payload = encodeAppMessage(message).toByteArray(Charsets.UTF_8)
        return writeCharacteristic(gatt, characteristic, payload)
    }

    /**
     * Writes one on-device inference readout. Called only from the single BLE writer
     * coroutine, so writes never overlap each other.
     *
     * Coordinates are rounded to one decimal: the model output comes off a
     * stretch-resized 640x640 input, so finer precision is noise, and the rounding is
     * what keeps the message inside the ATT write budget.
     */
    suspend fun sendInferenceResult(x: Float, y: Float): Boolean {
        val gatt = bluetoothGatt ?: run {
            Log.w("RATBle", "inference result dropped: not connected")
            return false
        }
        val characteristic = receiveCmdCharacteristic ?: run {
            Log.w("RATBle", "inference result dropped: command characteristic missing")
            return false
        }
        val payload = encodeAppMessage(AppMessage.InferenceResult(x.round1(), y.round1()))
            .toByteArray(Charsets.UTF_8)
        val budget = _negotiatedMtu.value - ATT_HEADER_BYTES
        if (payload.size > budget) {
            // Would be silently truncated by the ATT layer, so drop it loudly instead.
            // A result is ~45B, so this fires until requestMtu(517) lands.
            Log.w(
                "RATBle",
                "inference result ${payload.size}B exceeds ${budget}B budget, dropping",
            )
            return false
        }
        return writeCharacteristicRetrying(gatt, characteristic, payload)
    }

    /**
     * Writes with a short retry on ERROR_GATT_WRITE_REQUEST_BUSY (201), which the
     * stack returns when a write is still in flight. WRITE_TYPE_NO_RESPONSE does not
     * reliably deliver onCharacteristicWrite, so there is nothing to await — a brief
     * backoff is the only way to let the previous write drain. Observed in practice
     * on this device, so inference batches would otherwise be dropped outright.
     */
    private suspend fun writeCharacteristicRetrying(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        repeat(WRITE_BUSY_RETRIES) { attempt ->
            if (writeCharacteristic(gatt, characteristic, value)) return true
            if (lastWriteStatus != WRITE_REQUEST_BUSY) return false
            Log.d("RATBle", "write busy, retrying (attempt ${attempt + 1})")
            delay(WRITE_BUSY_BACKOFF_MS)
        }
        return false
    }

    /** Status of the most recent write, so the retry can tell "busy" from a real failure. */
    @Volatile
    private var lastWriteStatus: Int = BluetoothStatusCodes.SUCCESS

    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
            lastWriteStatus = status
            Log.d("RATBle", "writeCharacteristic status=$status (SUCCESS=${BluetoothStatusCodes.SUCCESS})")
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            characteristic.setValue(value)
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun reportFailure(message: String) {
        mainHandler.post {
            _connectionState.value = ConnectionState.Failed(
                device = connectingDevice
                    ?: DiscoveredDevice(address = "unknown", name = null, rssi = 0),
                message = message,
            )
        }
    }

    fun release() {
        stopScan()
        disconnect()
    }
}