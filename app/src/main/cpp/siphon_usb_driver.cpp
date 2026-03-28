#pragma once
#include <libusb.h>
#include <android/log.h>
#include <atomic>
#include <thread>
#include <vector>
#include <unistd.h>
#include <sys/time.h>
#include <cstring>
#include <pthread.h>
#include "CircularBuffer.h"

#define LOG_TAG_USBD "SiphonUsbDriver"
#define ULOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG_USBD, __VA_ARGS__)
#define ULOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG_USBD, __VA_ARGS__)
#define ULOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG_USBD, __VA_ARGS__)

using namespace rhythm;

extern void applySoftwareGain(void* data, int numFrames, int channels, int format, float gain);

class SiphonUsbDriver {
public:
    SiphonUsbDriver() : mRing(2 * 1024 * 1024) {}

    int initialize(int fd, int sampleRate, int channelCount, int bitDepth, int packetSize, int interfaceId, int endpointAddress) {
        mFd = fd;
        mSampleRate = sampleRate;
        mChannelCount = channelCount;
        mBitDepth = bitDepth;
        mBytesPerFrame = mChannelCount * ((mBitDepth == 24) ? 3 : (mBitDepth / 8));
        mPacketSize = packetSize > 0 ? packetSize : (mSampleRate * mBytesPerFrame) / 1000;
        mInterfaceId = interfaceId;
        mFrameCounter = 0;

        libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);

        int r = libusb_init(&mCtx);
        if (r < 0) {
            ULOGE("libusb_init failed: %d", r);
            return -1;
        }

        r = libusb_wrap_sys_device(mCtx, (intptr_t)fd, &mDevHandle);
        if (r < 0 || !mDevHandle) {
            ULOGE("libusb_wrap_sys_device failed: %d", r);
            return -1;
        }

        // ── 2. Detach kernel ALSA driver — THE KEY CALL ────────────────────
        r = libusb_detach_kernel_driver(mDevHandle, mInterfaceId);
        if (r == 0) {
            ULOGI("SiphonUsbDriver: snd-usb-audio detached from interface %d. AudioFlinger evicted. DAC is free.", mInterfaceId);
        } else if (r == LIBUSB_ERROR_NOT_FOUND) {
            ULOGI("SiphonUsbDriver: No kernel driver on interface %d — already free.", mInterfaceId);
        } else if (r == LIBUSB_ERROR_NOT_SUPPORTED) {
            ULOGW("SiphonUsbDriver: libusb_detach_kernel_driver not supported on this kernel (%s). Attempting claim anyway.", libusb_error_name(r));
        } else {
            ULOGE("SiphonUsbDriver: libusb_detach_kernel_driver failed: %s", libusb_error_name(r));
            libusb_close(mDevHandle);
            mDevHandle = nullptr;
            return -4; // SIPHON_ERR_DETACH_FAILED
        }

        // Also detach base control interface 0 to be safe
        libusb_detach_kernel_driver(mDevHandle, 0);

        // ── 3. Claim interface — always succeeds after detach ──────────────
        r = libusb_claim_interface(mDevHandle, mInterfaceId);
        if (r < 0) {
            ULOGE("SiphonUsbDriver: libusb_claim_interface failed: %s", libusb_error_name(r));
            libusb_close(mDevHandle);
            mDevHandle = nullptr;
            return -5; // SIPHON_ERR_CLAIM_FAILED
        }
        ULOGI("SiphonUsbDriver: Interface %d claimed exclusively. AudioFlinger fully evicted. libusb owns USB DAC. V4A: Processing=No", mInterfaceId);

        // ── 4. Select alt setting based on actual bytes-per-millisecond requirement ────
        // The DAC's MaxPacketSize per alt setting: 1=192, 2=288, 3=384 (typical)
        // Calculate actual required bandwidth to select the correct alt setting
        int bytesPerSample = (mBitDepth == 24) ? 3 : (mBitDepth / 8);
        int bytesPerSec = mSampleRate * mChannelCount * bytesPerSample;
        int bytesPerMs = bytesPerSec / 1000;
        
        int maxFramesPerMs = mSampleRate / 1000 + ((mSampleRate % 1000 == 0) ? 0 : 1);
        int requiredPacketSize = maxFramesPerMs * mBytesPerFrame;
        
        int altSetting;
        if      (requiredPacketSize <= 192) altSetting = 1;
        else if (requiredPacketSize <= 288) altSetting = 2;
        else                                 altSetting = 3;
        
        ULOGI("SiphonUsbDriver: bytesPerMs=%d, requiredPacketSize=%d, selecting altSetting=%d",
              bytesPerMs, requiredPacketSize, altSetting);

        r = libusb_set_interface_alt_setting(mDevHandle, mInterfaceId, altSetting);
        if (r < 0) {
            ULOGE("SiphonUsbDriver: libusb_set_interface_alt_setting(%d) failed: %s", altSetting, libusb_error_name(r));
            libusb_release_interface(mDevHandle, mInterfaceId);
            libusb_close(mDevHandle);
            mDevHandle = nullptr;
            return -6; // SIPHON_ERR_ALT_SETTING_FAILED
        }
        ULOGI("SiphonUsbDriver: Alt setting %d active — %d Hz / %d-bit / %d ch / packetSize=%d bytes",
              altSetting, mSampleRate, mBitDepth, mChannelCount, mPacketSize);

        mEndpoint = endpointAddress; // Out endpoint
        mRing.clear();
        mFirstTransferLogged.store(false);
        return 0;
    }

    int submitAudioData(const uint8_t* data, size_t size, bool isHardwareMode, double softwareGain) {
        if (!data || size == 0) return -1;

        // NOTE: Software gain is ALREADY applied in siphon_engine.cpp::nativeWritePcm()
        // DO NOT apply gain here again - this maintains bit-perfect output when hardware mode is ON
        // and consistent single-pass gain when software mode is ON
        
        // When isHardwareMode is true, data passes through UNMODIFIED = bit-perfect
        // When isHardwareMode is false, gain was already applied in JNI layer
        return mRing.write(data, size);
    }

    void start() {
        if (mRunning.exchange(true)) return;
        mThread = std::thread([this]() {
            // Elevate writer thread to real-time priority for USB audio
            // Try SCHED_FIFO first (requires root), fall back to SCHED_RR, then nice(-20)
            struct sched_param param = {};
            param.sched_priority = 90;
            
            if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &param) == 0) {
                ULOGI("Writer thread running at SCHED_FIFO priority 90");
            } else {
                // SCHED_FIFO failed (non-root) — try SCHED_RR as fallback
                param.sched_priority = 50;
                if (pthread_setschedparam(pthread_self(), SCHED_RR, &param) == 0) {
                    ULOGI("Writer thread running at SCHED_RR priority 50 (FIFO unavailable)");
                } else {
                    // Both real-time schedulers failed — use nice to maximize priority
                    nice(-20);
                    ULOGW("Real-time scheduling unavailable — using nice(-20) for elevated priority");
                }
            }
            this->runWriter();
        });
        mEventThread = std::thread([this]() { this->runEvents(); });
    }

    void stop() {
        if (!mRunning.exchange(false, std::memory_order_seq_cst)) return;
        
        // Signal event loop to wake up if it's blocking
        if (mCtx) {
            libusb_interrupt_event_handler(mCtx);
        }

        if (mThread.joinable()) {
            ULOGI("SiphonUsbDriver: Joining writer thread...");
            mThread.join();
        }
        
        if (mEventThread.joinable()) {
            ULOGI("SiphonUsbDriver: Joining event thread...");
            mEventThread.join();
        }
        ULOGI("SiphonUsbDriver: Driver threads stopped cleanly.");
    }

    void release() {
        stop();
        if (mDevHandle) {
            // Cancel any remaining transfers before releasing interface
            ULOGI("SiphonUsbDriver: Releasing USB interface and handle.");
            libusb_release_interface(mDevHandle, mInterfaceId);

            // Re-attach the kernel ALSA driver so AudioFlinger can use the device again
            int r = libusb_attach_kernel_driver(mDevHandle, mInterfaceId);
            if (r == 0) {
                ULOGI("SiphonUsbDriver: snd-usb-audio re-attached to interface %d. AudioFlinger can reclaim the DAC.", mInterfaceId);
            } else if (r == LIBUSB_ERROR_NOT_FOUND) {
                ULOGI("SiphonUsbDriver: No kernel driver was attached, or detach was handled correctly.");
            } else if (r == LIBUSB_ERROR_NOT_SUPPORTED || r == LIBUSB_ERROR_BUSY) {
                ULOGI("SiphonUsbDriver: kernel driver re-attach status: %s (harmless)", libusb_error_name(r));
            } else {
                ULOGW("SiphonUsbDriver: libusb_attach_kernel_driver failed: %s", libusb_error_name(r));
            }

            libusb_close(mDevHandle);
            mDevHandle = nullptr;
        }
        if (mCtx) {
            libusb_exit(mCtx);
            mCtx = nullptr;
        }
        mFd = -1;
    }

    
#define UAC2_CLASS_REQUEST     0x21  // bmRequestType: class, interface, host->device
#define UAC2_CLASS_REQUEST_GET 0xA1  // bmRequestType: class, interface, device->host
#define UAC2_SET_CUR           0x01
#define UAC2_GET_MIN           0x82
#define UAC2_GET_MAX           0x83
#define UAC2_VOLUME_CS         0x02  // Volume Control Selector
#define UAC2_MASTER_CN         0x00  // Master channel
#define UAC2_FEATURE_UNIT_ID   0x02
#define UAC2_CTRL_INTERFACE    0x00  // Audio Control interface = 0

    void queryVolumeRange() {
        if (!mDevHandle) return;
        unsigned char data[2] = {0, 0};

        uint16_t wValue = (UAC2_VOLUME_CS << 8) | UAC2_MASTER_CN;
        uint16_t wIndex = (UAC2_FEATURE_UNIT_ID << 8) | UAC2_CTRL_INTERFACE;

        // Query minimum
        libusb_control_transfer(mDevHandle,
          UAC2_CLASS_REQUEST_GET, UAC2_GET_MIN,
          wValue, wIndex, data, 2, 500);
        int16_t rawMin = (int16_t)(data[0] | (data[1] << 8));
        dacMinVolumeDb = std::max(rawMin / 256.0f, -60.0f); // clamp to -60 dB

        // Query maximum
        libusb_control_transfer(mDevHandle,
          UAC2_CLASS_REQUEST_GET, UAC2_GET_MAX,
          wValue, wIndex, data, 2, 500);
        int16_t rawMax = (int16_t)(data[0] | (data[1] << 8));
        dacMaxVolumeDb = rawMax / 256.0f;

        ULOGI("DAC volume range queried: %.2f dB to %.2f dB", dacMinVolumeDb, dacMaxVolumeDb);
        volumeRangeQueried = true;
    }

    int setHardwareVolume(int percent) {
        if (!mDevHandle) return -3; // SIPHON_ERR_NOT_INITIALIZED
        if (!volumeRangeQueried) queryVolumeRange();

        uint16_t wValue = (UAC2_VOLUME_CS << 8) | UAC2_MASTER_CN;
        uint16_t wIndex = (UAC2_FEATURE_UNIT_ID << 8) | UAC2_CTRL_INTERFACE;

        if (percent <= 0) {
          // Send MUTE via CUR with minimum value
          unsigned char mute[1] = {1};
          libusb_control_transfer(mDevHandle,
            UAC2_CLASS_REQUEST, UAC2_SET_CUR,
            0x0100,  // Mute CS
            wIndex, mute, 1, 500);
          ULOGI("Hardware volume: MUTED");
          return 0; // SIPHON_OK
        }

        // Unmute first
        unsigned char unmute[1] = {0};
        libusb_control_transfer(mDevHandle,
          UAC2_CLASS_REQUEST, UAC2_SET_CUR,
          0x0100, wIndex, unmute, 1, 500);

        // Log taper: same curve as software for consistent feel
        float normalized = percent / 100.0f;
        float dB = dacMinVolumeDb + (normalized * normalized) *
                   (dacMaxVolumeDb - dacMinVolumeDb);
        int16_t uac2Val = (int16_t)(dB * 256.0f);

        unsigned char data[2];
        data[0] = uac2Val & 0xFF;
        data[1] = (uac2Val >> 8) & 0xFF;

        int r = libusb_control_transfer(mDevHandle,
          UAC2_CLASS_REQUEST, UAC2_SET_CUR,
          wValue, wIndex, data, 2, 1000);

        if (r < 0) {
          ULOGE("setHardwareVolume: control transfer failed: %s", libusb_error_name(r));
          return -7; // SIPHON_ERR_VOLUME_FAILED
        }

        ULOGI("Hardware volume: %d%% -> %.2f dB (UAC2 0x%04X)", percent, dB, (uint16_t)uac2Val);
        return 0; // SIPHON_OK
    }


private:
private:
    std::atomic<int> mActiveTransfers{0};

    void runEvents() {
        while (mRunning.load(std::memory_order_acquire) || mActiveTransfers.load(std::memory_order_acquire) > 0) {
            struct timeval tv = {0, 100000};  // 100ms
            libusb_handle_events_timeout_completed(mCtx, &tv, nullptr);
        }
    }

    void runWriter() {
        const int NUM_TRANSFERS = 8;
        const int PACKETS_PER_TRANSFER = 8;
        const int MAX_PACKET_SIZE = mPacketSize > 0 ? mPacketSize : ((mSampleRate / 1000 + ((mSampleRate % 1000 == 0) ? 0 : 1)) * mBytesPerFrame); 

        std::vector<libusb_transfer*> transfers;
        for (int i = 0; i < NUM_TRANSFERS; ++i) {
            libusb_transfer* t = libusb_alloc_transfer(PACKETS_PER_TRANSFER);
            uint8_t* buf = new uint8_t[MAX_PACKET_SIZE * PACKETS_PER_TRANSFER];
            t->user_data = this;
            t->buffer = buf;
            
            libusb_fill_iso_transfer(t, mDevHandle, mEndpoint | LIBUSB_ENDPOINT_OUT, buf, 
                                     MAX_PACKET_SIZE * PACKETS_PER_TRANSFER, PACKETS_PER_TRANSFER, 
                                     isoCallback, this, 1000);
            
            libusb_set_iso_packet_lengths(t, MAX_PACKET_SIZE);
            transfers.push_back(t);
        }

        mActiveTransfers.store(NUM_TRANSFERS, std::memory_order_release);

        for (auto t : transfers) {
            fillTransferAndSubmit(t);
        }

        while (mRunning.load(std::memory_order_acquire)) {
            usleep(50000); 
        }

        for (auto t : transfers) {
            libusb_cancel_transfer(t);
        }

        while (mActiveTransfers.load(std::memory_order_acquire) > 0) {
            usleep(10000);
        }

        for (auto t : transfers) {
            delete[] t->buffer;
            libusb_free_transfer(t);
        }
    }

        void fillTransferAndSubmit(libusb_transfer* t) {
        if (!mRunning.load(std::memory_order_acquire)) return;

        int totalBytes = 0;

        for (int i = 0; i < t->num_iso_packets; ++i) {
            int frames = mSampleRate / 1000;
            mFrameCounter += (mSampleRate % 1000);
            if (mFrameCounter >= 1000) {
                frames += 1;
                mFrameCounter -= 1000;
            }
            
            int packetBytes = frames * mBytesPerFrame;
            t->iso_packet_desc[i].length = packetBytes;
            totalBytes += packetBytes;
        }

        // We must also update the transfer total length 
        // to match the sum of all packet lengths!
        t->length = totalBytes;

        size_t readBytes = mRing.read(t->buffer, totalBytes);
        if (readBytes < static_cast<size_t>(totalBytes)) {
            memset(t->buffer + readBytes, 0, totalBytes - readBytes);
        }
        
        int r = libusb_submit_transfer(t);
        if (r == LIBUSB_ERROR_NO_DEVICE || r == LIBUSB_ERROR_IO) {
            ULOGW("SiphonUsbDriver: device lost during playback: %s", libusb_error_name(r));
            extern void notifyDeviceLost();
            notifyDeviceLost();
            mRunning.store(false, std::memory_order_release);
            mActiveTransfers.fetch_sub(1, std::memory_order_release);
        } else if (r != 0) {
            ULOGE("SiphonUsbDriver: submit failed: %s", libusb_error_name(r));
            mActiveTransfers.fetch_sub(1, std::memory_order_release);
        } else if (!mFirstTransferLogged.exchange(true)) {
            ULOGI("SiphonUsbDriver: First ISO transfer submitted. PCM flowing direct to DAC.");
        }
    }

    static void LIBUSB_CALL isoCallback(struct libusb_transfer* transfer) {
        SiphonUsbDriver* engine = static_cast<SiphonUsbDriver*>(transfer->user_data);
        if (!engine->mRunning.load(std::memory_order_acquire)) {
            engine->mActiveTransfers.fetch_sub(1, std::memory_order_release);
            return;
        }
        if (transfer->status == LIBUSB_TRANSFER_COMPLETED || transfer->status == LIBUSB_TRANSFER_TIMED_OUT) {
            engine->fillTransferAndSubmit(transfer);
        } else {
            engine->mActiveTransfers.fetch_sub(1, std::memory_order_release);
        }
    }

    int mFd = -1;
    libusb_context* mCtx = nullptr;
    libusb_device_handle* mDevHandle = nullptr;
    int mEndpoint = 0x01;
    int mInterfaceId = 1;

    int mSampleRate = 44100;
    int mChannelCount = 2;
    int mBitDepth = 16;
    int mBytesPerFrame = 4;
    int mPacketSize = 0;
    int mFrameCounter = 0;
    
    rhythm::CircularBuffer mRing;
    std::atomic<bool> mRunning{false};
    std::atomic<bool> mFirstTransferLogged{false};
    std::thread mThread;
        float dacMinVolumeDb = -60.0f;
    float dacMaxVolumeDb = 0.0f;
    bool volumeRangeQueried = false;
std::thread mEventThread;
};







