package chromahub.rhythm.app.infrastructure.audio.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import chromahub.rhythm.app.infrastructure.audio.siphon.SiphonIsochronousEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 1 USB handshake state machine for DirectBit.
 */
class UsbConnectionOrchestrator(
    private val context: Context,
    private val usbManager: UsbManager
) {
    companion object {
        const val ACTION_USB_PERMISSION = "chromahub.rhythm.app.USB_PERMISSION"
        private const val TAG = "UsbOrchestrator"
    }

    // Only Standard vs Direct Bit
    enum class EngineMode { STANDARD, USB_HOST_DIRECT }

    data class EnumeratedDevice(
        val usbDevice: UsbDevice,
        val nativeSampleRates: List<Int>,
        val nativeBitDepths: List<Int>,
        val maxChannels: Int,
        val supportsHardwareVolume: Boolean,
        val dacVolMin: Int,
        val dacVolMax: Int,
        val featureUnitId: Int,
        val isochronousEndpointAddr: Int,
        val wMaxPacketSize: Int,
        val bInterval: Int,
        val supportsDoP: Boolean
    )

    data class ConnectionState(
        val connected: Boolean = false,
        val permissionGranted: Boolean = false,
        val isDeviceFullyEnumerated: Boolean = false,
        val activeMode: EngineMode = EngineMode.STANDARD,
        val enumeratedDevice: EnumeratedDevice? = null,
        val message: String = "Disconnected"
    )

    private var currentConnection: UsbDeviceConnection? = null
    private val claimedInterfaces = mutableListOf<UsbInterface>()
    
    // FIX: Track if we're already connected to prevent re-enumeration conflicts
    @Volatile
    private var currentlyConnectedDeviceId: Int? = null

    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val capsCache = mutableMapOf<String, EnumeratedDevice>()

    fun getCachedCaps(device: UsbDevice): EnumeratedDevice? = capsCache[device.deviceName]

    var onEnumerationComplete: ((UsbDevice, EnumeratedDevice) -> Unit)? = null

    fun onAttached(device: UsbDevice, usbExclusiveEnabled: Boolean) {
        // FIX: Prevent duplicate attach handling if we're already connected to this device
        // This stops the "connect → tear down → reconnect" cycle causing stuttering
        if (currentlyConnectedDeviceId == device.deviceId && _state.value.connected) {
            Log.d(TAG, "Device ${device.deviceId} already connected — ignoring duplicate attach event")
            return
        }
        
        if (!isUsbAudioStreamingDevice(device)) {
            _state.value = ConnectionState(message = "Not a USB audio streaming device")
            return
        }

        _state.value = _state.value.copy(connected = true, message = "USB audio device attached")

        if (!usbExclusiveEnabled) {
            Log.d(TAG, "USB DAC attached but Exclusive Mode is OFF — staying on AudioFlinger")
            _state.value = _state.value.copy(
                activeMode = EngineMode.STANDARD,
                message = "Exclusive mode OFF — not requesting USB permission"
            )
            return
        }

        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(context.packageName)
                    putExtra(UsbManager.EXTRA_DEVICE, device)
                },
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
            return
        }

        onPermissionResult(device, granted = true, usbExclusiveEnabled = usbExclusiveEnabled)
    }

    fun onPermissionResult(device: UsbDevice, granted: Boolean, usbExclusiveEnabled: Boolean) {
        if (!granted) {
            _state.value = _state.value.copy(
                permissionGranted = false,
                activeMode = EngineMode.STANDARD,
                isDeviceFullyEnumerated = false,
                message = "Permission denied. Standard path active"
            )
            return
        }

        _state.value = _state.value.copy(permissionGranted = true)
        if (!usbExclusiveEnabled) {
            _state.value = _state.value.copy(
                activeMode = EngineMode.STANDARD,
                message = "Enable USB Exclusive Mode to activate DirectBit"
            )
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            _state.value = _state.value.copy(
                activeMode = EngineMode.STANDARD,
                message = "Failed to open USB device"
            )
            return
        }
        currentConnection = connection

        val enumerated = enumerate(connection, device)
        if (enumerated == null) {
            _state.value = _state.value.copy(
                activeMode = EngineMode.STANDARD,
                message = "Descriptor enumeration failed"
            )
            return
        }

        _state.value = _state.value.copy(
            connected = true,
            permissionGranted = true,
            isDeviceFullyEnumerated = true,
            activeMode = EngineMode.USB_HOST_DIRECT,
            enumeratedDevice = enumerated,
            message = "DirectBit active"
        )
        
        // FIX: Mark this device as connected to prevent re-enumeration
        currentlyConnectedDeviceId = device.deviceId
    }

    fun onDetached() {
        for (iface in claimedInterfaces.reversed()) {
            try {
                currentConnection?.releaseInterface(iface)
            } catch (_: Throwable) {
            }
        }
        claimedInterfaces.clear()
        try {
            currentConnection?.close()
        } catch (_: Throwable) {
        }
        currentConnection = null
        currentlyConnectedDeviceId = null  // FIX: Clear device ID on detach
        capsCache.clear()
        _state.value = ConnectionState(message = "USB device detached")
    }

    // (probeExclusiveSupport removed)

    private fun isUsbAudioStreamingDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 0x02) {
                return true
            }
        }
        return false
    }

    private fun enumerate(connection: UsbDeviceConnection, device: UsbDevice): EnumeratedDevice? {
        claimedInterfaces.clear()
        val iface0 = if (device.interfaceCount > 0) device.getInterface(0) else null
        val iface1 = if (device.interfaceCount > 1) device.getInterface(1) else null

        if (iface0 == null || !connection.claimInterface(iface0, true)) {
            Log.w(TAG, "Failed claiming interface 0 before descriptor read")
            return null
        }
        claimedInterfaces.add(iface0)
        if (iface1 == null || !connection.claimInterface(iface1, true)) {
            Log.w(TAG, "Failed claiming interface 1 before descriptor read")
            for (iface in claimedInterfaces.reversed()) {
                try {
                    connection.releaseInterface(iface)
                } catch (_: Throwable) {
                }
            }
            claimedInterfaces.clear()
            return null
        }
        claimedInterfaces.add(iface1)
        
        val raw = connection.rawDescriptors ?: return null

        val sampleRates = linkedSetOf<Int>()
        val bitDepths = linkedSetOf<Int>()
        var maxChannels = 2
        var featureUnitId = 0
        var endpointAddr = 0
        var maxPacket = 0
        var bInterval = 1

        var i = 0
        while (i + 1 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len <= 0 || i + len > raw.size) break

            val type = raw[i + 1].toInt() and 0xFF
            when (type) {
                0x04 -> {
                    if (len >= 9) {
                        val cls = raw[i + 5].toInt() and 0xFF
                        val subCls = raw[i + 6].toInt() and 0xFF
                        if (cls == 0x01 && subCls == 0x02) {
                            maxChannels = maxOf(maxChannels, raw[i + 4].toInt() and 0xFF)
                        }
                    }
                }
                0x24 -> {
                    val subType = raw[i + 2].toInt() and 0xFF
                    when (subType) {
                        0x06 -> {
                            if (len >= 4) {
                                featureUnitId = raw[i + 3].toInt() and 0xFF
                            }
                        }
                        0x02 -> {
                            if (len >= 8) {
                                val channels = raw[i + 4].toInt() and 0xFF
                                if (channels > 0) {
                                    maxChannels = maxOf(maxChannels, channels)
                                }
                                val bitDepth = raw[i + 6].toInt() and 0xFF
                                if (bitDepth in listOf(16, 24, 32)) {
                                    bitDepths.add(bitDepth)
                                }
                                val rateCount = raw[i + 7].toInt() and 0xFF
                                for (k in 0 until rateCount) {
                                    val off = i + 8 + (k * 3)
                                    if (off + 2 < i + len) {
                                        val rate = (raw[off].toInt() and 0xFF) or
                                            ((raw[off + 1].toInt() and 0xFF) shl 8) or
                                            ((raw[off + 2].toInt() and 0xFF) shl 16)
                                        if (rate in 4000..1536000) sampleRates.add(rate)
                                    }
                                }
                            }
                        }
                    }
                }
                0x05 -> {
                    if (len >= 7) {
                        val address = raw[i + 2].toInt() and 0xFF
                        val attributes = raw[i + 3].toInt() and 0xFF
                        val isIso = (attributes and 0x03) == 0x01
                        val isOut = (address and 0x80) == 0
                        if (isIso && isOut) {
                            endpointAddr = address
                            maxPacket = (raw[i + 4].toInt() and 0xFF) or ((raw[i + 5].toInt() and 0xFF) shl 8)
                            bInterval = raw[i + 6].toInt() and 0xFF
                        }
                    }
                }
            }
            i += len
        }

        if (sampleRates.isEmpty()) {
            sampleRates.addAll(listOf(44100, 48000, 96000, 192000))
        }
        if (bitDepths.isEmpty()) {
            bitDepths.addAll(listOf(16, 24))
        }

        val hasHwVol = featureUnitId != 0
        val volRange = queryVolumeRange(connection, featureUnitId)

        Log.i(TAG, "Enumerated ${device.productName} rates=$sampleRates depths=$bitDepths ch=$maxChannels ep=0x${endpointAddr.toString(16)}")

        val enumerated = EnumeratedDevice(
            usbDevice = device,
            nativeSampleRates = sampleRates.toList().sorted(),
            nativeBitDepths = bitDepths.toList().sorted(),
            maxChannels = maxChannels,
            supportsHardwareVolume = hasHwVol,
            dacVolMin = volRange.first,
            dacVolMax = volRange.second,
            featureUnitId = featureUnitId,
            isochronousEndpointAddr = endpointAddr,
            wMaxPacketSize = maxPacket,
            bInterval = bInterval,
            supportsDoP = bitDepths.contains(24) || bitDepths.contains(32)
        )

        Log.d(TAG, "Calling onEnumerationComplete with caps: $enumerated")
        capsCache[device.deviceName] = enumerated
        onEnumerationComplete?.invoke(device, enumerated)
            ?: Log.e(TAG, "onEnumerationComplete is NULL — OutputRouter not wired!")

        return enumerated
    }

    private fun queryVolumeRange(connection: UsbDeviceConnection, featureUnitId: Int): Pair<Int, Int> {
        if (featureUnitId == 0) return Pair(-32768, 0)

        val bufMin = ByteArray(2)
        val bufMax = ByteArray(2)

        // bmRequestType 0xA1: Device-to-Host | Class | Interface
        // bRequest     0x82: GET_MIN
        // wValue       0x0200: Control Selector=Volume(0x02), Channel=Master(0x00)
        // wIndex       FU_ID<<8 | interface(0)
        val minResult = connection.controlTransfer(0xA1, 0x82, 0x0200, featureUnitId shl 8, bufMin, 2, 500)

        // bmRequestType 0xA1: Device-to-Host | Class | Interface
        // bRequest     0x83: GET_MAX
        // wValue       0x0200: Control Selector=Volume(0x02), Channel=Master(0x00)
        // wIndex       FU_ID<<8 | interface(0)
        val maxResult = connection.controlTransfer(0xA1, 0x83, 0x0200, featureUnitId shl 8, bufMax, 2, 500)

        val min = if (minResult >= 2) ((bufMin[1].toInt() shl 8) or (bufMin[0].toInt() and 0xFF)) else -32768
        val max = if (maxResult >= 2) ((bufMax[1].toInt() shl 8) or (bufMax[0].toInt() and 0xFF)) else 0
        return Pair(min, max)
    }
}
