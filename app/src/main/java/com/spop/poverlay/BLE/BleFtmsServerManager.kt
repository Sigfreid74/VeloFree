package com.spop.poverlay.BLE

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class BleFtmsServerManager(private val context: Context) {

    data class ControlPointData(
        var grade: Double = 0.0,
        var rollingResistance: Double = 0.0,
        var windSpeed: Double = 0.0,
        var cw: Double = 0.0
    )

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private val registeredDevices = mutableSetOf<BluetoothDevice>()
    private var statusCharacteristicRef: BluetoothGattCharacteristic? = null

    private var lastPower = 0
    private var lastCadence = 0f
    private var lastSpeed = 0f // km/h

    // FTMS UUIDs
    private val FTMS_SERVICE_UUID = UUID.fromString("00001826-0000-1000-8000-00805F9B34FB")
    private val INDOOR_BIKE_DATA_UUID = UUID.fromString("00002AD2-0000-1000-8000-00805F9B34FB")
    private val FTMS_FEATURE_UUID = UUID.fromString("00002ACC-0000-1000-8000-00805F9B34FB")
    private val FTMS_CONTROL_POINT_UUID = UUID.fromString("00002AD9-0000-1000-8000-00805F9B34FB")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    private val FTMS_STATUS_UUID = UUID.fromString("00002ADA-0000-1000-8000-00805F9B34FB")
    private val FTMS_RESISTANCE_RANGE_UUID = UUID.fromString("00002AD6-0000-1000-8000-00805F9B34FB")
    private val FTMS_POWER_RANGE_UUID = UUID.fromString("00002AD8-0000-1000-8000-00805F9B34FB")

    var onConnectionStateChanged: ((Int) -> Unit)? = null
    var onResistanceChanged: ((Int) -> Unit)? = null
    var onControlPointChanged: ((ControlPointData) -> Unit)? = null

    init {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        BluetoothAdapter.getDefaultAdapter()?.name = "VeloFree"
    }

    suspend fun observePower(power: Flow<Float>) {
        power.collect { value ->
            lastPower = value.toInt()
            updateBikeData()
        }
    }

    suspend fun observeCadence(cadence: Flow<Float>) {
        cadence.collect { value ->
            lastCadence = value
            updateBikeData()
        }
    }

    suspend fun observeSpeed(speed: Flow<Float>) {
        speed.collect { value ->
            lastSpeed = value
            updateBikeData()
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateBikeData() {
        if (registeredDevices.isEmpty()) return

        val characteristic = bluetoothGattServer?.getService(FTMS_SERVICE_UUID)
            ?.getCharacteristic(INDOOR_BIKE_DATA_UUID) ?: return

        var flags = 0x0000
        flags = flags or (1 shl 2)  // Instant Cadence
        flags = flags or (1 shl 6)  // Instant Power

        val data = mutableListOf<Byte>()
        data.add((flags and 0xFF).toByte())
        data.add(((flags shr 8) and 0xFF).toByte())

        // Speed (0.01 km/h)
        val speedVal = (lastSpeed * 100).toInt()
        data.add((speedVal and 0xFF).toByte())
        data.add(((speedVal shr 8) and 0xFF).toByte())

        // Cadence (0.5 rpm)
        val cadenceVal = (lastCadence * 2).toInt()
        data.add((cadenceVal and 0xFF).toByte())
        data.add(((cadenceVal shr 8) and 0xFF).toByte())

        // Power (sint16)
        data.add((lastPower and 0xFF).toByte())
        data.add(((lastPower shr 8) and 0xFF).toByte())

        characteristic.value = data.toByteArray()

        for (device in registeredDevices) {
            bluetoothGattServer?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return

        setupGattServer()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(FTMS_SERVICE_UUID))
            .build()

        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        bluetoothGattServer?.close()
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        val service = BluetoothGattService(FTMS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val bikeData = BluetoothGattCharacteristic(
            INDOOR_BIKE_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }

        val feature = BluetoothGattCharacteristic(
            FTMS_FEATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = byteArrayOf(0x33, 0x00, 0x0C, 0x00) // Speed, Cadence, Power, Resistance + Simulation
        }

        val controlPoint = BluetoothGattCharacteristic(
            FTMS_CONTROL_POINT_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            addDescriptor(BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }

        val statusCharacteristic = BluetoothGattCharacteristic(
            FTMS_STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        statusCharacteristicRef = statusCharacteristic

        val resistanceRange = BluetoothGattCharacteristic(
            FTMS_RESISTANCE_RANGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { value = byteArrayOf(0x00, 0x00, 0x64, 0x00, 0x01, 0x00) }

        val powerRange = BluetoothGattCharacteristic(
            FTMS_POWER_RANGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { value = byteArrayOf(0x00, 0x00, 0xC4.toByte(), 0x09, 0x01, 0x00) }

        service.addCharacteristic(bikeData)
        service.addCharacteristic(feature)
        service.addCharacteristic(controlPoint)
        service.addCharacteristic(statusCharacteristic)
        service.addCharacteristic(resistanceRange)
        service.addCharacteristic(powerRange)

        bluetoothGattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        bluetoothGattServer?.addService(service)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("BleFtms", "Advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e("BleFtms", "Advertising failed: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                registeredDevices.add(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                registeredDevices.remove(device)
            }
            onConnectionStateChanged?.invoke(registeredDevices.size)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid == FTMS_CONTROL_POINT_UUID && value.isNotEmpty()) {
                val opCode = value[0]
                when (opCode) {
                    0x00.toByte(), 0x01.toByte() -> sendControlPointResponse(device, requestId, opCode, 0x01)
                    0x04.toByte() -> { // Resistance
                        if (value.size >= 3) {
                            val resistance = ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
                            onResistanceChanged?.invoke(resistance)
                            sendControlPointResponse(device, requestId, opCode, 0x01)
                        } else sendControlPointResponse(device, requestId, opCode, 0x03)
                    }
                    0x05.toByte() -> sendControlPointResponse(device, requestId, opCode, 0x01) // ERG
                    0x07.toByte(), 0x08.toByte() -> {
                        sendControlPointResponse(device, requestId, opCode, 0x01)
                        sendFitnessMachineStatus(if (opCode == 0x07.toByte()) 0x04 else 0x02)
                    }
                    0x11.toByte() -> { // Simulation (Gradient)
                        if (value.size >= 7) {
                            val grade = ByteBuffer.wrap(value, 3, 2).order(ByteOrder.LITTLE_ENDIAN).short * 0.01
                            val crr = (value[5].toInt() and 0xFF) * 0.0001
                            val windSpeed = ByteBuffer.wrap(value, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short * 0.001
                            val cw = (value[6].toInt() and 0xFF) * 0.01

                            onControlPointChanged?.invoke(ControlPointData(grade.toDouble(), crr.toDouble(), windSpeed.toDouble(), cw.toDouble()))
                            sendControlPointResponse(device, requestId, opCode, 0x01)
                        } else sendControlPointResponse(device, requestId, opCode, 0x03)
                    }
                    else -> sendControlPointResponse(device, requestId, opCode, 0x02)
                }
                return
            }

            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        private fun sendFitnessMachineStatus(status: Byte) {
            statusCharacteristicRef?.let { char ->
                char.value = byteArrayOf(status)
                registeredDevices.forEach { device ->
                    bluetoothGattServer?.notifyCharacteristicChanged(device, char, false)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun sendControlPointResponse(device: BluetoothDevice, requestId: Int, opCode: Byte, result: Byte) {
            val char = bluetoothGattServer?.getService(FTMS_SERVICE_UUID)
                ?.getCharacteristic(FTMS_CONTROL_POINT_UUID) ?: return
            char.value = byteArrayOf(0x80.toByte(), opCode, result)
            bluetoothGattServer?.notifyCharacteristicChanged(device, char, true)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (CLIENT_CHARACTERISTIC_CONFIG_UUID == descriptor.uuid) {
                val enableNotification = byteArrayOf(0x01, 0x00)
                val enableIndication = byteArrayOf(0x02, 0x00)
                if (value.contentEquals(enableNotification) || value.contentEquals(enableIndication)) {
                    registeredDevices.add(device)
                    if (descriptor.characteristic.uuid == FTMS_STATUS_UUID) {
                        sendFitnessMachineStatus(0x04)
                    }
                }
            }
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
        }
    }
}
