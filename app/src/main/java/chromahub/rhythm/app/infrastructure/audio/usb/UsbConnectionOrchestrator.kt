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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 1 USB handshake state machine for Siphon DirectBit.
 *
 * Responsible for:
 *   1. Requesting USB permission
 *   2. Opening the USB device
 *   3. Claiming interfaces (with force detach of kernel driver)
 *   4. Reading raw USB descriptors to discover supported formats
 *   5. Firing onEnumerationComplete callback with device capabilities
 *
 * FIX: Added retry logic for claimInterface failures, detailed timing logs,
 * and an onEnumerationFailed callback so the session manager can handle errors.
 */
class UsbConnectionOrchestrator(
    private val context: Context,
    private val usbManager: UsbManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val ACTION_USB_PERMISSION = "chromahub.rhythm.app.USB_PERMISSION"
        private const val TAG = "UsbOrchestrator"
        private const val MAX_CLAIM_RETRIES = 3
        private const val CLAIM_RETRY_DELAY_MS = 300L
    }

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

    @Volatile
    private var currentlyConnectedDeviceId: Int? = null

    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val capsCache = mutableMapOf<String, EnumeratedDevice>()

    fun getCachedCaps(device: UsbDevice): EnumeratedDevice? = capsCache[device.deviceName]

    var onEnumerationComplete: ((UsbDevice, EnumeratedDevice) -> Unit)? = null

    /** NEW: Callback fired when enumeration fails, so upstream can handle errors */
    var onEnumerationFailed: ((UsbDevice, String) -> Unit)? = null

    fun onAttached(device: UsbDevice, usbExclusiveEnabled: Boolean) {
        if (currentlyConnectedDeviceId == device.deviceId && _state.value.connected) {
            Log.d(TAG, "Device ${device.deviceId} already connected — ignoring duplicate attach")
            return
        }

        if (!isUsbAudioStreamingDevice(device)) {
            _state.value = ConnectionState(message = "Not a USB audio streaming device")
            return
        }

        Log.i(TAG, "onAttached: ${device.productName} (vid=0x${device.vendorId.toString(16)}, " +
                "pid=0x${device.productId.toString(16)}, interfaces=${device.interfaceCount})")
        _state.value = _state.value.copy(connected = true, message = "USB audio device attached")

        if (!usbExclusiveEnabled) {
            Log.d(TAG, "Exclusive Mode OFF — not requesting permission")
            _state.value = _state.value.copy(
                activeMode = EngineMode.STANDARD,
                message = "Exclusive mode OFF — staying on AudioFlinger"
            )
            return
        }

        if (!usbManager.hasPermission(device)) {
            Log.d(TAG, "Requesting USB permission for ${device.productName}")
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
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
        Log.i(TAG, "onPermissionResult: granted=$granted, exclusive=$usbExclusiveEnabled, device=${device.productName}")

        if (!granted) {
            _state.value = _state.value.copy(
                permissionGranted = false,
                activeMode = EngineMode.STANDARD,
                isDeviceFullyEnumerated = false,
                message = "Permission denied — standard path active"
            )
            onEnumerationFailed?.invoke(device, "USB permission denied")
            return
        }

        _state.value = _state.value.copy(permissionGranted = true)
        if (!usbExclusiveEnabled) {
            _state.value = _state.value.copy(
                activeMode = EngineMode.STANDARD,
                message = "Enable Exclusive Mode to activate Siphon"
            )
            return
        }

        // Open USB device
        val startTime = System.currentTimeMillis()
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "openDevice() FAILED for ${device.productName} after ${elapsed}ms")
            _state.value = _state.value.copy(
                activeMode = EngineMode.STANDARD,
                message = "Failed to open USB device"
            )
            onEnumerationFailed?.invoke(device, "openDevice() returned null — kernel driver may still hold the interface")
            return
        }
        currentConnection = connection
        Log.d(TAG, "openDevice() succeeded — fd=${connection.fileDescriptor} (${System.currentTimeMillis() - startTime}ms)")

        // Enumerate descriptors (with retry and timeout)
        scope.launch {
            try {
                val enumeratedResult = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        enumerate(connection, device)
                    }
                }
                
                if (enumeratedResult == null) {
                    Log.e(TAG, "Descriptor enumeration FAILED/TIMED OUT for ${device.productName}")
                    _state.value = _state.value.copy(
                        activeMode = EngineMode.STANDARD,
                        message = "Descriptor enumeration failed or timed out"
                    )
                    onEnumerationFailed?.invoke(device, "enumerate() returned null or timed out — claimInterface may have hung")
                    return@launch
                }

                _state.value = _state.value.copy(
                    connected = true,
                    permissionGranted = true,
                    isDeviceFullyEnumerated = true,
                    activeMode = EngineMode.USB_HOST_DIRECT,
                    enumeratedDevice = enumeratedResult,
                    message = "DirectBit active"
                )

                currentlyConnectedDeviceId = device.deviceId
                Log.i(TAG, "Enumeration COMPLETE for ${device.productName} (total ${System.currentTimeMillis() - startTime}ms)")
                
                // Finally, signal completion callback
                capsCache[device.deviceName] = enumeratedResult
                onEnumerationComplete?.invoke(device, enumeratedResult)
            } catch (e: Exception) {
                Log.e(TAG, "Enumeration threw exception for ${device.productName}: ${e.message}", e)
                _state.value = _state.value.copy(
                    activeMode = EngineMode.STANDARD,
                    message = "Enumeration threw exception"
                )
                onEnumerationFailed?.invoke(device, "Enumeration exception: ${e.message}")
            }
        }
    }

    fun onDetached() {
        Log.i(TAG, "onDetached — releasing claimed interfaces")
        for (iface in claimedInterfaces.reversed()) {
            try {
                currentConnection?.releaseInterface(iface)
            } catch (_: Throwable) {}
        }
        claimedInterfaces.clear()
        try {
            currentConnection?.close()
        } catch (_: Throwable) {}
        currentConnection = null
        currentlyConnectedDeviceId = null
        capsCache.clear()
        _state.value = ConnectionState(message = "USB device detached")
    }

    private fun isUsbAudioStreamingDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 0x02) {
                return true
            }
        }
        return false
    }

    /**
     * Enumerate USB audio descriptors from the device.
     *
     * FIX: Added retry logic for claimInterface() failures. The kernel ALSA driver
     * may still hold the interface briefly after AudioFlinger eviction. Retrying
     * with a short delay allows the kernel driver to finish cleanup.
     */
    private fun enumerate(connection: UsbDeviceConnection, device: UsbDevice): EnumeratedDevice? {
        val startTime = System.currentTimeMillis()
        claimedInterfaces.clear()

        val iface0 = if (device.interfaceCount > 0) device.getInterface(0) else null
        val iface1 = if (device.interfaceCount > 1) device.getInterface(1) else null

        Log.d(TAG, "enumerate: ${device.productName}, interfaceCount=${device.interfaceCount}, fd=${connection.fileDescriptor}")

        // Claim interface 0 (control) with retry
        if (iface0 != null) {
            val claimed0 = claimInterfaceWithRetry(connection, iface0, "interface 0 (control)")
            if (!claimed0) {
                Log.e(TAG, "FAILED to claim interface 0 after $MAX_CLAIM_RETRIES retries")
                return null
            }
            claimedInterfaces.add(iface0)
        } else {
            Log.w(TAG, "No interface 0 on device")
            return null
        }

        // Claim interface 1 (streaming) with retry
        if (iface1 != null) {
            val claimed1 = claimInterfaceWithRetry(connection, iface1, "interface 1 (streaming)")
            if (!claimed1) {
                Log.e(TAG, "FAILED to claim interface 1 after $MAX_CLAIM_RETRIES retries — releasing interface 0")
                releaseClaimedInterfaces(connection)
                return null
            }
            claimedInterfaces.add(iface1)
        } else {
            Log.w(TAG, "No interface 1 on device — single-interface device")
        }

        Log.d(TAG, "Interfaces claimed in ${System.currentTimeMillis() - startTime}ms — reading descriptors")

        val raw = connection.rawDescriptors
        if (raw == null) {
            Log.e(TAG, "rawDescriptors returned null!")
            releaseClaimedInterfaces(connection)
            return null
        }
        Log.d(TAG, "Raw descriptors: ${raw.size} bytes")

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
                0x04 -> { // Interface descriptor
                    if (len >= 9) {
                        val cls = raw[i + 5].toInt() and 0xFF
                        val subCls = raw[i + 6].toInt() and 0xFF
                        if (cls == 0x01 && subCls == 0x02) {
                            maxChannels = maxOf(maxChannels, raw[i + 4].toInt() and 0xFF)
                        }
                    }
                }
                0x24 -> { // Class-specific (Audio)
                    if (i + 2 < raw.size) {
                        val subType = raw[i + 2].toInt() and 0xFF
                        when (subType) {
                            0x06 -> { // Feature Unit
                                if (len >= 4) {
                                    featureUnitId = raw[i + 3].toInt() and 0xFF
                                    Log.d(TAG, "Feature Unit ID: $featureUnitId")
                                }
                            }
                            0x02 -> { // Format Type I
                                if (len >= 8) {
                                    val channels = raw[i + 4].toInt() and 0xFF
                                    if (channels > 0) maxChannels = maxOf(maxChannels, channels)
                                    val bitDepth = raw[i + 6].toInt() and 0xFF
                                    if (bitDepth in listOf(16, 24, 32)) bitDepths.add(bitDepth)
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
                                    Log.d(TAG, "Format Type I: ${channels}ch, ${bitDepth}bit, rates=$sampleRates")
                                }
                            }
                        }
                    }
                }
                0x05 -> { // Endpoint descriptor
                    if (len >= 7) {
                        val address = raw[i + 2].toInt() and 0xFF
                        val attributes = raw[i + 3].toInt() and 0xFF
                        val isIso = (attributes and 0x03) == 0x01
                        val isOut = (address and 0x80) == 0
                        if (isIso && isOut) {
                            endpointAddr = address
                            maxPacket = (raw[i + 4].toInt() and 0xFF) or ((raw[i + 5].toInt() and 0xFF) shl 8)
                            bInterval = raw[i + 6].toInt() and 0xFF
                            Log.d(TAG, "ISO OUT endpoint: addr=0x${address.toString(16)}, " +
                                    "maxPacket=$maxPacket, bInterval=$bInterval")
                        }
                    }
                }
            }
            i += len
        }

        // Apply defaults for devices that don't report rates/depths in descriptors
        if (sampleRates.isEmpty()) {
            sampleRates.addAll(listOf(44100, 48000, 96000, 192000))
            Log.w(TAG, "No sample rates in descriptors — using defaults: $sampleRates")
        }
        if (bitDepths.isEmpty()) {
            bitDepths.addAll(listOf(16, 24))
            Log.w(TAG, "No bit depths in descriptors — using defaults: $bitDepths")
        }

        val hasHwVol = featureUnitId != 0
        val volRange = queryVolumeRange(connection, featureUnitId)

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

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "═══ Enumeration complete for ${device.productName} in ${elapsed}ms ═══")
        Log.i(TAG, "  Rates: ${enumerated.nativeSampleRates}")
        Log.i(TAG, "  Depths: ${enumerated.nativeBitDepths}")
        Log.i(TAG, "  Channels: $maxChannels, EP: 0x${endpointAddr.toString(16)}, MaxPkt: $maxPacket")
        Log.i(TAG, "  HW Volume: $hasHwVol (FU=$featureUnitId, range=${volRange.first}..${volRange.second})")

        return enumerated
    }

    /**
     * Attempt to claim a USB interface with retry logic.
     * The force=true parameter detaches the kernel driver (snd-usb-audio).
     *
     * FIX: Previously a single failed claimInterface would abort the entire
     * enumeration. Now retries up to MAX_CLAIM_RETRIES times with delays.
     */
    private fun claimInterfaceWithRetry(
        connection: UsbDeviceConnection,
        iface: UsbInterface,
        label: String
    ): Boolean {
        for (attempt in 1..MAX_CLAIM_RETRIES) {
            val result = connection.claimInterface(iface, true) // force=true → detach kernel driver
            if (result) {
                Log.d(TAG, "claimInterface($label) succeeded on attempt $attempt")
                return true
            }
            Log.w(TAG, "claimInterface($label) FAILED on attempt $attempt/$MAX_CLAIM_RETRIES")
            if (attempt < MAX_CLAIM_RETRIES) {
                Thread.sleep(CLAIM_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private fun releaseClaimedInterfaces(connection: UsbDeviceConnection) {
        for (iface in claimedInterfaces.reversed()) {
            try { connection.releaseInterface(iface) } catch (_: Throwable) {}
        }
        claimedInterfaces.clear()
    }

    private fun queryVolumeRange(connection: UsbDeviceConnection, featureUnitId: Int): Pair<Int, Int> {
        if (featureUnitId == 0) return Pair(-32768, 0)

        val bufMin = ByteArray(2)
        val bufMax = ByteArray(2)

        val minResult = connection.controlTransfer(0xA1, 0x82, 0x0200, featureUnitId shl 8, bufMin, 2, 500)
        val maxResult = connection.controlTransfer(0xA1, 0x83, 0x0200, featureUnitId shl 8, bufMax, 2, 500)

        val min = if (minResult >= 2) ((bufMin[1].toInt() shl 8) or (bufMin[0].toInt() and 0xFF)) else -32768
        val max = if (maxResult >= 2) ((bufMax[1].toInt() shl 8) or (bufMax[0].toInt() and 0xFF)) else 0

        Log.d(TAG, "Volume range query: min=$min, max=$max (result=$minResult/$maxResult)")
        return Pair(min, max)
    }
}
