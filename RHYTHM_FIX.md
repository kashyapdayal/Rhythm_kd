# Rhythm App — Complete Bug Fix & Feature Implementation Guide

**Source:** Analysis of `Log_2026-03-21_12-38-46.txt`, `crash_20260321_113852.txt`, `rhythm_20260321_112956.txt`, and architecture document.  
**Target:** Bit-perfect, lossless USB DAC output with working hardware volume control.

---

## Table of Contents

1. [BUG-01 — SIGSEGV in `nativeAttachKernelDriver` (libusb null context)](#bug-01)
2. [BUG-02 — `recoverFromZombieState` crash loop on every startup](#bug-02)
3. [BUG-03 — Audio still routes through AudioFlinger after DAC connected](#bug-03)
4. [BUG-04 — No audio after unplug + reconnect (silent deadlock)](#bug-04)
5. [BUG-05 — `SiphonGain` applying software attenuation (-3.88 dB) — not bit-perfect](#bug-05)
6. [BUG-06 — `nativeSampleRates=[770]` bitmask parsed incorrectly](#bug-06)
7. [BUG-07 — `ForegroundService` missing `mediaPlayback` type declaration](#bug-07)
8. [BUG-08 — AudioEffect (Equalizer) chain initialized for Siphon session — wrong](#bug-08)
9. [FEAT-01 — Volume buttons do nothing in Exclusive Mode (MediaSession + VolumeProvider)](#feat-01)
10. [FEAT-02 — Hardware volume via USB `SET_CUR` control transfer](#feat-02)
11. [FEAT-03 — Software volume fallback (JNI PCM gain) for DACs without hardware vol](#feat-03)
12. [Implementation Checklist](#checklist)

---

## BUG-01 — SIGSEGV in `nativeAttachKernelDriver` (libusb null context) {#bug-01}

**Severity:** CRITICAL — crashes the app every time a USB DAC is connected.  
**File:** `app/src/main/cpp/siphon_engine.cpp`  
**Evidence from log:**
```
F libc: Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000000001a8
F DEBUG: #03 libusb_wrap_sys_device+200
F DEBUG: #04 Java_...SiphonIsochronousEngine_nativeAttachKernelDriver+64
```

**Root cause:** `libusb_wrap_sys_device()` is called with a null `libusb_context*`, or with a context that was initialized without `LIBUSB_OPTION_NO_DEVICE_DISCOVERY`. On unrooted Android, if you skip this option, libusb tries to scan `/dev/bus/usb/` directly, fails silently, and leaves the context in a broken state. The subsequent `libusb_wrap_sys_device()` dereferences the null/broken context at offset `0x1a8` (the USB session list head).

**Fix — `siphon_engine.cpp`:**

Replace your current init sequence with the exact pattern below. The `set_option` call **must come before** `libusb_init`.

```cpp
#include <libusb.h>

// Store these as fields on your engine struct/class so they survive across calls
static libusb_context* g_usb_ctx = nullptr;
static libusb_device_handle* g_usb_handle = nullptr;

JNIEXPORT jint JNICALL
Java_chromahub_rhythm_app_infrastructure_audio_siphon_SiphonIsochronousEngine_nativeAttachKernelDriver(
        JNIEnv* env, jobject thiz, jint fileDescriptor) {

    // Cleanup any previous context first
    if (g_usb_handle) {
        libusb_close(g_usb_handle);
        g_usb_handle = nullptr;
    }
    if (g_usb_ctx) {
        libusb_exit(g_usb_ctx);
        g_usb_ctx = nullptr;
    }

    // STEP 1: Tell libusb NOT to scan /dev/bus/usb — MUST be before libusb_init
    int r = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_set_option failed: %s", libusb_error_name(r));
        return r;
    }

    // STEP 2: Init with a real context pointer (NEVER pass null to libusb_init on Android)
    r = libusb_init(&g_usb_ctx);
    if (r < 0) {
        LOGE("libusb_init failed: %s", libusb_error_name(r));
        g_usb_ctx = nullptr;
        return r;
    }

    // STEP 3: Wrap the Android-provided fd into a libusb device handle
    r = libusb_wrap_sys_device(g_usb_ctx, (intptr_t)fileDescriptor, &g_usb_handle);
    if (r < 0 || g_usb_handle == nullptr) {
        LOGE("libusb_wrap_sys_device failed: %s", libusb_error_name(r));
        libusb_exit(g_usb_ctx);
        g_usb_ctx = nullptr;
        return r;
    }

    // STEP 4: Claim the audio streaming interface (interface 1)
    // The kernel USB audio driver may own it — detach it first
    if (libusb_kernel_driver_active(g_usb_handle, 1) == 1) {
        r = libusb_detach_kernel_driver(g_usb_handle, 1);
        if (r < 0) {
            LOGE("libusb_detach_kernel_driver failed: %s", libusb_error_name(r));
            // Non-fatal on Android — the kernel driver may not be attached at all
        }
    }

    r = libusb_claim_interface(g_usb_handle, 1);
    if (r < 0) {
        LOGE("libusb_claim_interface failed: %s", libusb_error_name(r));
        libusb_close(g_usb_handle);
        g_usb_handle = nullptr;
        libusb_exit(g_usb_ctx);
        g_usb_ctx = nullptr;
        return r;
    }

    LOGI("nativeAttachKernelDriver: success, fd=%d", fileDescriptor);
    return LIBUSB_SUCCESS;
}

// Cleanup function — call this from nativeClose/nativeRelease
JNIEXPORT void JNICALL
Java_chromahub_rhythm_app_infrastructure_audio_siphon_SiphonIsochronousEngine_nativeClose(
        JNIEnv* env, jobject thiz) {
    if (g_usb_handle) {
        libusb_release_interface(g_usb_handle, 1);
        libusb_close(g_usb_handle);
        g_usb_handle = nullptr;
    }
    if (g_usb_ctx) {
        libusb_exit(g_usb_ctx);
        g_usb_ctx = nullptr;
    }
}
```

---

## BUG-02 — `recoverFromZombieState` crash loop on every startup {#bug-02}

**Severity:** CRITICAL — causes a 6-crash loop (seen in log at 12:38:55–12:39:02).  
**File:** `SiphonManager.kt`  
**Evidence from log:**
```
I UsbAudioManager: Restoring persisted USB attach event for USB-C Audio
F DEBUG: #07 SiphonManager.recoverFromZombieState+106
F DEBUG: #09 SiphonManager.start+124
(repeats 6 times with ~2s backoff each)
```

**Root cause:** When the app process dies (crash or kill), `UsbAudioManager` has persisted a USB attach event. On the next process start, `SiphonManager.start()` calls `recoverFromZombieState()` which calls `nativeAttachKernelDriver(persistedFd)`. The fd from the dead process is invalid in the new process — libusb crashes. The service restarts, sees the persisted state again, crashes again.

**Fix — `SiphonManager.kt`:**

```kotlin
// Add a SharedPreferences key for clean shutdown tracking
private val prefs = context.getSharedPreferences("siphon_state", Context.MODE_PRIVATE)
private val KEY_CLEAN_SHUTDOWN = "clean_shutdown"
private val KEY_PERSISTED_DEVICE = "persisted_device_name"

fun start() {
    val hadCleanShutdown = prefs.getBoolean(KEY_CLEAN_SHUTDOWN, true)
    val persistedDevice = prefs.getString(KEY_PERSISTED_DEVICE, null)

    if (persistedDevice != null && !hadCleanShutdown) {
        // Previous session crashed with USB connected.
        // DO NOT try to reattach with the old (dead) fd.
        // The DAC is still physically plugged in — Android will re-deliver
        // USB_DEVICE_ATTACHED within ~1 second via UsbAudioManager.
        Log.w(TAG, "Detected zombie USB state for '$persistedDevice' — clearing. " +
                   "Will wait for fresh USB_DEVICE_ATTACHED broadcast.")
        clearPersistedUsbState()
        // Mark that we're starting fresh so we don't loop
        prefs.edit().putBoolean(KEY_CLEAN_SHUTDOWN, true).apply()
        return  // DO NOT call recoverFromZombieState()
    }

    // Normal startup — mark as not-cleanly-shut-down until onDestroy
    prefs.edit().putBoolean(KEY_CLEAN_SHUTDOWN, false).apply()

    // ... rest of normal start() logic
}

fun onDestroy() {
    // Mark clean shutdown so next start knows it was intentional
    prefs.edit()
        .putBoolean(KEY_CLEAN_SHUTDOWN, true)
        .remove(KEY_PERSISTED_DEVICE)
        .apply()
    nativeClose()
}

private fun clearPersistedUsbState() {
    prefs.edit()
        .remove(KEY_PERSISTED_DEVICE)
        .apply()
    // Also tell UsbAudioManager to clear its persisted state
    usbAudioManager.clearPersistedAttachEvent()
}
```

**Also add to `UsbAudioManager.kt`:**
```kotlin
fun clearPersistedAttachEvent() {
    // Clear whatever SharedPreferences key you use to persist the USB device name/fd
    prefs.edit().remove("persisted_usb_device").apply()
}
```

---

## BUG-03 — Audio still routes through AudioFlinger after DAC connected {#bug-03}

**Severity:** HIGH — the entire point of Siphon fails.  
**Files:** `OutputRouter.kt`, `SiphonManager.kt`, `RhythmPlayerEngine.kt`  
**Evidence from log:**
```
D audio_sw_mixer: sw_mixer_source_attach T_FAST rate 48000 → T_PRIMARY rate 192000
I ViPER4Android: Input sampling rate: 48000
(Siphon switch fires at 12:39:56, but mixer was active since 12:39:12)
```

**Root cause:** Two separate issues:

1. Siphon crashes on every attach (BUG-01), so the `SiphonUsbAudioSink` is never actually used and ExoPlayer stays on `DefaultAudioSink` → AudioFlinger.
2. Even when Siphon is activated at 12:39:56, the `executeSwitchToSiphon` path has a race: `swapAudioSink()` triggers ExoPlayer re-init, but `SiphonUsbAudioSink.open()` must be **fully blocking** — it must not return until `nativeAttachKernelDriver` + `nativeSetAlternateSetting` + transfer buffers are all ready. If it returns before the transfer is ready, ExoPlayer will try to write to an uninitialized sink.

**Fix — `SiphonUsbAudioSink.kt`:**

```kotlin
override fun open(inputFormat: Format): SinkOpenResult {
    // This must be BLOCKING — do not return until the USB endpoint is ready
    val result = siphonEngine.openBlocking(
        fileDescriptor = currentFd,
        sampleRate = inputFormat.sampleRate,
        channelCount = inputFormat.channelCount,
        bitDepth = if (inputFormat.pcmEncoding == C.ENCODING_PCM_FLOAT) 32 else 24,
        timeoutMs = 2000
    )
    if (result != SUCCESS) {
        Log.e(TAG, "SiphonUsbAudioSink.open() failed: $result")
        return SinkOpenResult.UNSUPPORTED  // Forces ExoPlayer to fall back
    }
    isReady = true
    return SinkOpenResult.SUCCESS
}
```

**Fix — `siphon_engine.cpp` — add blocking open:**

```cpp
JNIEXPORT jint JNICALL
Java_..._nativeOpenBlocking(JNIEnv* env, jobject thiz,
        jint sampleRate, jint channels, jint bitDepth, jlong timeoutMs) {

    if (!g_usb_handle) {
        LOGE("nativeOpenBlocking: no USB handle — did nativeAttachKernelDriver succeed?");
        return -1;
    }

    // Select the correct USB Alternate Setting for this sample rate
    // AlternateSetting 1 = 192B packets (any rate up to ~48kHz @ 24bit stereo)
    // AlternateSetting 2 = 288B packets
    // AlternateSetting 3 = 384B packets (max)
    int altSetting = selectAltSetting(sampleRate, channels, bitDepth);

    int r = libusb_set_interface_alt_setting(g_usb_handle, 1, altSetting);
    if (r < 0) {
        LOGE("set_interface_alt_setting(%d) failed: %s", altSetting, libusb_error_name(r));
        return r;
    }

    // Allocate isochronous transfer buffers
    g_transfer = libusb_alloc_transfer(NUM_ISO_PACKETS);
    if (!g_transfer) {
        LOGE("libusb_alloc_transfer failed");
        return LIBUSB_ERROR_NO_MEM;
    }

    // ... fill in transfer params, start event thread, etc.
    g_sample_rate = sampleRate;
    g_channels = channels;
    g_bit_depth = bitDepth;
    g_is_open = true;

    LOGI("nativeOpenBlocking: ready sr=%d ch=%d bits=%d altSetting=%d",
         sampleRate, channels, bitDepth, altSetting);
    return LIBUSB_SUCCESS;
}

static int selectAltSetting(int sampleRate, int channels, int bitDepth) {
    // For TTGK USB-C Audio:
    //   AltSetting 1 → wMaxPacketSize=192 (fits 48kHz/16bit/stereo: 48000/1000*2*2=192 bytes)
    //   AltSetting 2 → wMaxPacketSize=288 (fits 48kHz/24bit/stereo: 48000/1000*2*3=288 bytes)
    //   AltSetting 3 → wMaxPacketSize=384 (fits 96kHz/24bit/stereo: 96000/1000*2*2=384 bytes)
    int bytesPerMs = (sampleRate / 1000) * channels * (bitDepth / 8);
    if (bytesPerMs <= 192) return 1;
    if (bytesPerMs <= 288) return 2;
    return 3;
}
```

---

## BUG-04 — No audio after unplug + reconnect {#bug-04}

**Severity:** HIGH — core use case broken.  
**File:** `OutputRouter.kt`  
**Evidence from log:**
```
D UsbAudioManager: USB audio device detached
(user replays, plugs back in)
I OutputRouter: executeSwitchToSiphon: transition requested
I RhythmPlayerEngine: pauseForRouting: already paused, skipping
(resumeAfterRouting is never called — player stays silent forever)
```

**Root cause:** `executeSwitchToSiphon` pauses ExoPlayer then calls into native code. If the native init crashes (BUG-01) or times out, `resumeAfterRouting()` is never called. ExoPlayer stays permanently paused.

**Fix — `OutputRouter.kt`:**

```kotlin
private val SIPHON_INIT_TIMEOUT_MS = 3000L

fun executeSwitchToSiphon(device: UsbDevice) {
    val timeoutJob = scope.launch {
        delay(SIPHON_INIT_TIMEOUT_MS)
        if (!siphonInitCompleted) {
            Log.w(TAG, "Siphon init timed out after ${SIPHON_INIT_TIMEOUT_MS}ms — " +
                       "falling back to DefaultAudioSink and resuming playback")
            siphonInitCompleted = false
            withContext(Dispatchers.Main) {
                switchToStandardSink()
                rhythmPlayerEngine.resumeAfterRouting()
            }
        }
    }

    scope.launch {
        try {
            siphonInitCompleted = false

            // Open the USB device — get a fresh fd
            val connection = usbManager.openDevice(device)
                ?: throw IOException("openDevice returned null — was permission granted?")
            val fd = connection.fileDescriptor

            // Init libusb and attach
            val attachResult = siphonEngine.nativeAttachKernelDriver(fd)
            if (attachResult != 0) throw IOException("nativeAttachKernelDriver failed: $attachResult")

            // Open audio endpoint (blocking until ready)
            val currentTrackFormat = rhythmPlayerEngine.currentTrackFormat
            val openResult = siphonUsbAudioSink.open(currentTrackFormat)
            if (openResult != SinkOpenResult.SUCCESS) throw IOException("Sink open failed")

            // Swap ExoPlayer to the Siphon sink
            rhythmPlayerEngine.swapAudioSink(siphonUsbAudioSink)

            siphonInitCompleted = true
            timeoutJob.cancel()

            // Resume playback
            rhythmPlayerEngine.resumeAfterRouting()

        } catch (e: Exception) {
            Log.e(TAG, "Siphon switch failed: ${e.message}", e)
            siphonInitCompleted = false
            timeoutJob.cancel()
            switchToStandardSink()
            rhythmPlayerEngine.resumeAfterRouting()  // Always resume — never leave silent
        }
    }
}
```

---

## BUG-05 — `SiphonGain` applying software attenuation — not bit-perfect {#bug-05}

**Severity:** HIGH — destroys bit-perfect output goal.  
**File:** `SiphonGain.kt`, `SiphonUsbAudioSink.kt`  
**Evidence from log:**
```
I SiphonGain: Software gain: 80% -> 0.640000 linear (-3.88 dB)
```
The DAC reports `supportsHardwareVolume=true` — software scaling should never happen.

**Fix — `SiphonUsbAudioSink.kt`:**

Remove any call to `SiphonGain.applyGain()` from the `write()` method. PCM bytes must pass through unmodified:

```kotlin
override fun write(buffer: ByteBuffer, size: Int, presentationTimeUs: Long): Int {
    // DO NOT call siphonGain.applyGain(buffer) here
    // DO NOT scale, multiply, or modify buffer in any way
    // Pass raw bytes directly to native transfer
    return siphonEngine.nativeWrite(buffer, size)
}
```

Volume changes from the user must instead go through hardware SET_CUR (see FEAT-02). `SiphonGain` should only be used when `supportsHardwareVolume == false`.

---

## BUG-06 — `nativeSampleRates=[770]` parsed incorrectly {#bug-06}

**Severity:** MEDIUM — causes wrong USB Alternate Setting selection, wrong sample rate.  
**File:** `UsbConnectionOrchestrator.kt`  
**Evidence from log:**
```
I UsbOrchestrator: Enumerated USB-C Audio rates=[770] depths=[16, 24]
```
Bitmask 770 = `0b1100000010` = 11025 + 96000 + 176400 Hz. Actual rates (from ALSA log): `192000|96000|48000|44100|32000|16000|8000`.

**Root cause:** The USB Audio Class Type I Format Descriptor stores discrete sample rates as 3-byte little-endian integers. The parser is ORing the bytes into a bitmask instead of reading them as frequency values.

**Fix — `UsbConnectionOrchestrator.kt`:**

```kotlin
/**
 * Parse USB Audio Class Type I Format Descriptor (UAC1).
 * Bytes: bLength, bDescriptorType, bDescriptorSubtype, bFormatType,
 *        bNrChannels, bSubFrameSize, bBitResolution, bSamFreqType,
 *        tSamFreq[0..bSamFreqType-1] (3 bytes each, little-endian Hz)
 */
fun parseSampleRates(descriptor: ByteArray): List<Int> {
    val rates = mutableListOf<Int>()
    if (descriptor.size < 9) return rates

    val bSamFreqType = descriptor[8].toInt() and 0xFF

    // bSamFreqType == 0 means continuous range (min/max) — not discrete
    if (bSamFreqType == 0) {
        // Read min and max from tSamFreq[0] and tSamFreq[1]
        // For simplicity, return common rates within that range
        val minRate = read3ByteLE(descriptor, 9)
        val maxRate = read3ByteLE(descriptor, 12)
        return listOf(44100, 48000, 88200, 96000, 176400, 192000)
            .filter { it in minRate..maxRate }
    }

    // Discrete rates
    for (i in 0 until bSamFreqType) {
        val offset = 9 + i * 3
        if (offset + 3 > descriptor.size) break
        val hz = read3ByteLE(descriptor, offset)
        if (hz > 0) rates.add(hz)
    }
    return rates  // e.g. [44100, 48000, 96000, 192000]
}

private fun read3ByteLE(buf: ByteArray, offset: Int): Int {
    return (buf[offset].toInt() and 0xFF) or
           ((buf[offset + 1].toInt() and 0xFF) shl 8) or
           ((buf[offset + 2].toInt() and 0xFF) shl 16)
}
```

Then store the parsed list in `EnumeratedDevice.nativeSampleRates: List<Int>` instead of a bitmask.

---

## BUG-07 — `ForegroundService` missing `mediaPlayback` type {#bug-07}

**Severity:** MEDIUM — OS may kill the service while in background.  
**Evidence from log:**
```
W ForegroundServiceTypeLoggerModule: Foreground service start for UID: 10417 does not have any types
```

**Fix — `AndroidManifest.xml`:**

```xml
<service
    android:name=".infrastructure.service.MediaPlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="false" />
```

**Fix — `MediaPlaybackService.kt` (Android 10+ / API 29+):**

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // For API 29+, specify the service type
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    } else {
        startForeground(NOTIFICATION_ID, buildNotification())
    }
    return START_STICKY
}
```

---

## BUG-08 — AudioEffect chain initialized for Siphon session {#bug-08}

**Severity:** MEDIUM — wastes resources, may cause system-side audio processing on a session that isn't going through AudioFlinger.  
**Evidence from log:**
```
I MediaPlaybackService: Effects chain initialized for session 21073
D MediaPlaybackService: Equalizer initialized with 5 bands for session 21073
(session 21073 is the Siphon-mode ExoPlayer session)
```

**Root cause:** `AudioEffect` objects (Equalizer, BassBoost, etc.) are tied to an `AudioTrack` session ID. In Siphon mode, ExoPlayer still creates an `AudioTrack` internally (for session ID allocation), but audio never flows through it. Attaching effects to this session is meaningless and may confuse Android's audio policy.

**Fix — `MediaPlaybackService.kt`:**

```kotlin
private fun initializeAudioEffects(sessionId: Int) {
    // Do NOT initialize AudioEffect objects when in Siphon/exclusive mode
    if (outputRouter.currentMode == OutputMode.SIPHON_DIRECT) {
        Log.d(TAG, "Siphon mode active — skipping AudioEffect initialization for session $sessionId")
        releaseAudioEffects()
        return
    }
    // ... normal effects init for Standard mode
    equalizer = Equalizer(0, sessionId)
    bassBoost = BassBoost(0, sessionId)
    // etc.
}
```

---

## FEAT-01 — Volume buttons in Exclusive Mode (MediaSession + VolumeProvider) {#feat-01}

**Problem:** Volume buttons do nothing in Siphon mode. The log shows `onRemoteUpdate: Rhythm: 0 of 100` — the VolumeProvider is registered but its current volume is stuck at 0 and button presses never reach `VolumeController.kt`.

**Why this happens:** In Exclusive Mode, Android's `STREAM_MUSIC` is not being used. Volume buttons default to the ringer unless your `MediaSession` has an active `VolumeProvider` attached. Even then, the provider must call `setCurrentVolume()` to update the UI, and must trigger hardware volume changes.

**Implementation — `MediaPlaybackService.kt`:**

```kotlin
private var volumeProvider: VolumeProviderCompat? = null
private var currentSiphonVolume = 80  // 0-100, load from SharedPreferences

private fun setupVolumeProvider() {
    // Only used in Siphon mode — tells Android "I control this volume"
    volumeProvider = object : VolumeProviderCompat(
        VOLUME_CONTROL_ABSOLUTE,  // We support absolute set, not just up/down
        100,                       // maxVolume
        currentSiphonVolume        // initialVolume (0-100)
    ) {
        override fun onSetVolumeTo(volume: Int) {
            currentSiphonVolume = volume.coerceIn(0, 100)
            setCurrentVolume(currentSiphonVolume)  // Update the OS volume UI
            applyVolume(currentSiphonVolume)
            saveVolumeToPrefs(currentSiphonVolume)
        }

        override fun onAdjustVolume(direction: Int) {
            // direction: +1 = up, -1 = down, 0 = mute toggle
            val step = 5  // 5% per button press — adjust to taste
            val newVol = (currentSiphonVolume + direction * step).coerceIn(0, 100)
            onSetVolumeTo(newVol)
        }
    }

    // Attach to MediaSession — this is what makes volume buttons work in background
    mediaSession.setPlaybackToRemote(volumeProvider!!)
}

private fun applyVolume(volumePercent: Int) {
    if (outputRouter.currentMode == OutputMode.SIPHON_DIRECT) {
        val dacCaps = usbConnectionOrchestrator.currentDacCaps
        if (dacCaps?.supportsHardwareVolume == true) {
            // Send SET_CUR to DAC hardware (see FEAT-02)
            volumeController.setHardwareVolume(
                connection = siphonManager.currentUsbConnection!!,
                featureUnitId = dacCaps.featureUnitId,
                dacVolMin = dacCaps.dacVolMin,
                dacVolMax = dacCaps.dacVolMax,
                volumePercent = volumePercent
            )
        } else {
            // DAC has no hardware volume — apply software gain (see FEAT-03)
            siphonEngine.nativeSetSoftwareGain(volumePercent.toFloat() / 100f)
        }
    }
    // In Standard mode, Android handles volume automatically — do nothing
}

// Call this when switching TO Siphon mode
fun onSwitchedToSiphonMode() {
    setupVolumeProvider()
    applyVolume(currentSiphonVolume)  // Restore saved volume
}

// Call this when switching AWAY from Siphon mode
fun onSwitchedToStandardMode() {
    // Return volume control to Android
    mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
    volumeProvider = null
}
```

**Also override volume keys inside the Activity for when the app is in foreground:**

```kotlin
// In MainActivity.kt
override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    return when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> {
            mediaController.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            true
        }
        KeyEvent.KEYCODE_VOLUME_DOWN -> {
            mediaController.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }
}
```

---

## FEAT-02 — Hardware volume via USB `SET_CUR` control transfer {#feat-02}

**DAC capabilities from log:**
```
supportsHardwareVolume=true
featureUnitId=2
dacVolMin=-32768
dacVolMax=0
```
Volume range is expressed in 1/256 dB units: -32768 = -128 dB (mute), 0 = 0 dB (full).

**Implementation — `VolumeController.kt`:**

```kotlin
object VolumeController {

    /**
     * Send a USB Audio Class SET_CUR request to the DAC's Feature Unit.
     * This directly controls the DAC's internal hardware amplifier.
     *
     * @param connection    Active UsbDeviceConnection (from usbManager.openDevice())
     * @param featureUnitId From EnumeratedDevice.featureUnitId (= 2 for TTGK DAC)
     * @param dacVolMin     From EnumeratedDevice.dacVolMin (= -32768)
     * @param dacVolMax     From EnumeratedDevice.dacVolMax (= 0)
     * @param volumePercent User-facing 0-100%
     * @param interfaceNum  Audio control interface number (= 0 for UAC1)
     */
    fun setHardwareVolume(
        connection: UsbDeviceConnection,
        featureUnitId: Int,
        dacVolMin: Int,
        dacVolMax: Int,
        volumePercent: Int,
        interfaceNum: Int = 0
    ): Boolean {
        // Map 0-100% to dacVolMin..dacVolMax (linear in dB units)
        val dacRange = dacVolMax - dacVolMin  // = 32768
        val dacValue = dacVolMin + (dacRange.toLong() * volumePercent / 100).toInt()

        val volLow  = (dacValue and 0xFF).toByte()
        val volHigh = ((dacValue shr 8) and 0xFF).toByte()
        val volBytes = byteArrayOf(volLow, volHigh)

        // USB Audio Class 1.0 SET_CUR request:
        //   bmRequestType = 0x21  (class request, interface, host-to-device)
        //   bRequest      = 0x01  (SET_CUR)
        //   wValue        = 0x0200 (VOLUME_CONTROL selector = 0x02, channel master = 0x00)
        //   wIndex        = (featureUnitId << 8) | interfaceNum
        //   data          = 2-byte little-endian volume in 1/256 dB
        val result = connection.controlTransfer(
            /* bmRequestType */ 0x21,
            /* bRequest      */ 0x01,
            /* wValue        */ 0x0200,
            /* wIndex        */ (featureUnitId shl 8) or interfaceNum,
            /* buffer        */ volBytes,
            /* length        */ 2,
            /* timeout       */ 1000
        )

        return if (result >= 0) {
            Log.d(TAG, "HW volume set: $volumePercent% → dacValue=$dacValue (${dacValue / 256.0} dB)")
            true
        } else {
            Log.e(TAG, "controlTransfer failed: $result")
            false
        }
    }

    /**
     * Query the DAC's current hardware volume (GET_CUR).
     * Use this on connect to sync the UI to the DAC's current state.
     */
    fun getHardwareVolume(
        connection: UsbDeviceConnection,
        featureUnitId: Int,
        dacVolMin: Int,
        dacVolMax: Int,
        interfaceNum: Int = 0
    ): Int {
        val buf = ByteArray(2)
        val result = connection.controlTransfer(
            /* bmRequestType */ 0xA1,  // class, interface, device-to-host
            /* bRequest      */ 0x81,  // GET_CUR
            /* wValue        */ 0x0200,
            /* wIndex        */ (featureUnitId shl 8) or interfaceNum,
            buf, 2, 1000
        )
        if (result < 2) return 80  // Default to 80% on failure

        val dacValue = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
        val signedDacValue = if (dacValue > 32767) dacValue - 65536 else dacValue

        val dacRange = dacVolMax - dacVolMin
        return if (dacRange == 0) 100
        else ((signedDacValue - dacVolMin) * 100 / dacRange).coerceIn(0, 100)
    }
}
```

**In `OutputRouter.kt`, query current volume after successful Siphon connection:**

```kotlin
// After successful nativeAttachKernelDriver and sink open:
val savedVol = VolumeController.getHardwareVolume(
    connection = usbConnection,
    featureUnitId = dacCaps.featureUnitId,
    dacVolMin = dacCaps.dacVolMin,
    dacVolMax = dacCaps.dacVolMax
)
mediaPlaybackService.onSiphonConnected(initialVolumePercent = savedVol)
```

---

## FEAT-03 — Software volume fallback in JNI (for DACs without hardware volume) {#feat-03}

For DACs where `supportsHardwareVolume == false`, you must scale PCM samples in native code. **This is the only acceptable alternative — do not use `SiphonGain` to apply this in Kotlin before the bytes reach native code**, because that forces 32-bit float math on a hot path and the results depend on Java's floating-point behavior.

**Implementation — `siphon_engine.cpp`:**

```cpp
// Thread-safe atomic gain (0.0 = silent, 1.0 = unity gain)
static std::atomic<float> g_software_gain{1.0f};

JNIEXPORT void JNICALL
Java_..._SiphonIsochronousEngine_nativeSetSoftwareGain(
        JNIEnv* env, jobject thiz, jfloat gainLinear) {
    g_software_gain.store(gainLinear, std::memory_order_relaxed);
}

// Call this inside your isochronous write callback before submitting to libusb
static void applyGainIfNeeded(int16_t* samples, int sampleCount) {
    float gain = g_software_gain.load(std::memory_order_relaxed);
    if (gain >= 0.999f) return;  // Unity gain — skip (bit-perfect path)

    for (int i = 0; i < sampleCount; i++) {
        float scaled = samples[i] * gain;
        // Clamp to int16 range
        samples[i] = (int16_t)std::max(-32768.0f, std::min(32767.0f, scaled));
    }
}

// For 24-bit samples packed as int32:
static void applyGain24(int32_t* samples, int sampleCount) {
    float gain = g_software_gain.load(std::memory_order_relaxed);
    if (gain >= 0.999f) return;
    const int32_t MAX24 = 8388607;
    const int32_t MIN24 = -8388608;
    for (int i = 0; i < sampleCount; i++) {
        float scaled = samples[i] * gain;
        samples[i] = (int32_t)std::max((float)MIN24, std::min((float)MAX24, scaled));
    }
}
```

---

## Implementation Checklist {#checklist}

Work through these in order. Each item is a discrete task your AI agent can execute.

### Phase 1 — Stop the crashes (do these first, nothing else works until done)

- [ ] **`siphon_engine.cpp`**: Add `libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr)` before `libusb_init(&ctx)`. Pass `&g_usb_ctx` to `libusb_init`, never null. Pass `g_usb_ctx` as first arg to `libusb_wrap_sys_device`. (BUG-01)
- [ ] **`siphon_engine.cpp`**: Add `nativeClose()` JNI function that calls `libusb_release_interface`, `libusb_close`, `libusb_exit` and nulls all pointers. (BUG-01)
- [ ] **`SiphonManager.kt`**: In `start()`, check for zombie state. If crash flag is set, call `clearPersistedUsbState()` and return immediately — do NOT call `recoverFromZombieState()`. (BUG-02)
- [ ] **`MediaPlaybackService.kt`**: In `onDestroy()`, write `cleanShutdown = true` flag to SharedPreferences before calling `siphonManager.onDestroy()`. (BUG-02)
- [ ] **`UsbAudioManager.kt`**: Add `clearPersistedAttachEvent()` method that removes the persisted USB device from SharedPreferences. (BUG-02)

### Phase 2 — Make Siphon actually work

- [ ] **`siphon_engine.cpp`**: Implement `nativeOpenBlocking(fd, sampleRate, channels, bitDepth, timeoutMs)` that calls `libusb_set_interface_alt_setting()` with the correct alt setting, allocates transfer buffers, and returns only when ready. (BUG-03)
- [ ] **`siphon_engine.cpp`**: Implement `selectAltSetting(sampleRate, channels, bitDepth)` that picks alt setting 1/2/3 based on `bytesPerMs = (sampleRate/1000) * channels * (bitDepth/8)`. (BUG-03, BUG-06)
- [ ] **`SiphonUsbAudioSink.kt`**: Make `open()` call `nativeOpenBlocking()` and return `UNSUPPORTED` on failure so ExoPlayer falls back cleanly. Remove any call to `SiphonGain.applyGain()` from `write()`. (BUG-03, BUG-05)
- [ ] **`OutputRouter.kt`**: Wrap `executeSwitchToSiphon` in a coroutine with a 3-second timeout. On timeout OR exception, call `switchToStandardSink()` then `resumeAfterRouting()`. Always call `resumeAfterRouting()` regardless of Siphon success/failure. (BUG-04)
- [ ] **`UsbConnectionOrchestrator.kt`**: Fix `parseSampleRates()` to read `tSamFreq` as 3-byte little-endian Hz values, not as a bitmask. Change `nativeSampleRates` type from `Int` (bitmask) to `List<Int>`. (BUG-06)

### Phase 3 — Volume control

- [ ] **`VolumeController.kt`**: Implement `setHardwareVolume()` using `UsbDeviceConnection.controlTransfer()` with `bmRequestType=0x21, bRequest=0x01, wValue=0x0200, wIndex=(featureUnitId shl 8)`. (FEAT-02)
- [ ] **`VolumeController.kt`**: Implement `getHardwareVolume()` using `controlTransfer()` with `bmRequestType=0xA1, bRequest=0x81` to query DAC's current level on connect. (FEAT-02)
- [ ] **`MediaPlaybackService.kt`**: Create `VolumeProviderCompat` with `VOLUME_CONTROL_ABSOLUTE, maxVolume=100`. Implement `onSetVolumeTo()` and `onAdjustVolume()` to call `VolumeController.setHardwareVolume()`. Call `mediaSession.setPlaybackToRemote(volumeProvider)` when switching to Siphon. Call `mediaSession.setPlaybackToLocal(STREAM_MUSIC)` when switching away. (FEAT-01)
- [ ] **`siphon_engine.cpp`**: Implement `nativeSetSoftwareGain(float gain)` that stores gain atomically, and `applyGainIfNeeded()` that applies it to PCM samples before USB write — only used when `supportsHardwareVolume == false`. (FEAT-03)
- [ ] **`MainActivity.kt`**: Override `onKeyDown()` to call `mediaController.adjustVolume()` for `KEYCODE_VOLUME_UP/DOWN`. (FEAT-01)

### Phase 4 — Polish and correctness

- [ ] **`AndroidManifest.xml`**: Add `android:foregroundServiceType="mediaPlayback"` to `MediaPlaybackService`. (BUG-07)
- [ ] **`MediaPlaybackService.kt`**: In `startForeground()`, add `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` on API 29+. (BUG-07)
- [ ] **`MediaPlaybackService.kt`**: In `initializeAudioEffects()`, skip AudioEffect initialization when `outputRouter.currentMode == SIPHON_DIRECT`. (BUG-08)
- [ ] **`SiphonGain.kt`**: Add a guard — log a warning and return immediately if `dacCaps.supportsHardwareVolume == true`. Software gain must never be used when hardware volume is available. (BUG-05)

---

## Key architectural facts from your logs

| Observation | Log evidence | Implication |
|---|---|---|
| libusb loads successfully | `nativeloader: Load libsiphon_engine.so: ok` | JNI linkage is fixed. The crash is runtime, not build-time. |
| Siphon path activates once | `Mode: Siphon Direct — bypassing AudioFlinger` at 12:39:56 | Architecture is correct — just blocked by the libusb crash on reconnect |
| DAC supports hardware volume | `supportsHardwareVolume=true, featureUnitId=2, dacVolMin=-32768, dacVolMax=0` | Must use SET_CUR, not SiphonGain |
| Volume provider registered but stuck at 0 | `onRemoteUpdate: Rhythm: 0 of 100` | VolumeProvider exists but `setCurrentVolume()` and hardware SET_CUR are not wired up |
| AudioFlinger mixer always at 48 kHz | `chooseTargetSampleRate() hiFiState 1 isDeviceSupportHiFi 0 targetSampleRate 48000` | MediaTek SoC forces 48kHz on standard path — Siphon is the ONLY path to native rate |
| Service type missing | `Foreground service start...does not have any types` | Service may be killed by OS in background |
| DAC has 3 alternate settings | `UsbInterface mId=1, AlternateSetting=1/2/3` | Alt 1=192B, Alt 2=288B, Alt 3=384B — must select based on track sample rate |
| Effects init on Siphon session | `Equalizer initialized with 5 bands for session 21073` | Wastes resources, may interfere |

---

*Generated from log analysis on 2026-03-21. Device: Xiaomi rodin (2412DPC0AG), MediaTek mt6899, Android 14/15 (API 36). DAC: TTGK USB-C Audio, VendorId=13058, ProductId=13152.*
