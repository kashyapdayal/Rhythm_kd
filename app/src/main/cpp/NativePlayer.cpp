#include "NativePlayer.h"
#include <android/log.h>
#include <unistd.h>
#include <vector>
#include <cstdint>
#include <ctime>
#include "usb/UacDescriptorParser.h"
#include "usb/UsbTransfer.h"

#ifndef TAG
#define TAG "RhythmNativePlayer"
#endif

#ifndef LOGD
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#endif
#ifndef LOGI
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#endif
#ifndef LOGW
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#endif
#ifndef LOGE
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

namespace rhythm {

NativePlayer::NativePlayer() 
    : mIsRunning(false), 
      mIsPaused(false), 
      mBuffer(1024 * 1024) { // 1MB ring buffer
    LOGD("NativePlayer created.");
}

NativePlayer::~NativePlayer() {
    stop();
    LOGD("NativePlayer destroyed.");
}

bool NativePlayer::initialize(int sampleRate, int channels, int bitDepth) {
    mSampleRate = sampleRate;
    mChannels = channels;
    mBitDepth = bitDepth;
    
    // Clear any stale data from a previous session
    mBuffer.clear();
    
    LOGI("NativePlayer initialized: SR=%d, Channels=%d, Bits=%d", sampleRate, channels, bitDepth);
    return true;
}

bool NativePlayer::start() {
    if (mIsRunning) {
        LOGW("NativePlayer start() called but already running — resuming.");
        mIsPaused = false;
        return true;
    }
    mIsRunning = true;
    mIsPaused = false;
    
    if (pthread_create(&mAudioThread, nullptr, audioThreadEntry, this) != 0) {
        mIsRunning = false;
        LOGE("CRITICAL: Failed to create audio thread!");
        return false;
    }
    
    LOGI("NativePlayer thread started successfully.");
    return true;
}

void NativePlayer::stop() {
    if (!mIsRunning) return;
    LOGI("NativePlayer stopping...");
    mIsRunning = false;
    pthread_join(mAudioThread, nullptr);
    mBuffer.clear();
    LOGI("NativePlayer thread stopped and buffer cleared.");
}

void NativePlayer::pause() {
    mIsPaused = true;
    LOGD("NativePlayer paused.");
}

void NativePlayer::resume() {
    mIsPaused = false;
    LOGD("NativePlayer resumed.");
}

void NativePlayer::setUsbFileDescriptor(int fd) {
    mUsbFd = fd;
    LOGI("NativePlayer USB FD set: %d", fd);
}

void NativePlayer::setUsbDescriptors(const uint8_t* data, int length) {
    mUsbDescriptors.assign(data, data + length);
    LOGI("NativePlayer USB descriptors set: %d bytes", length);
}

void NativePlayer::pushData(const void* data, int length) {
    if (!mIsRunning) {
        LOGW("pushData() called but engine not running — dropping %d bytes", length);
        return;
    }
    
    size_t written = mBuffer.write(data, length);
    if (written < (size_t)length) {
        LOGW("CircularBuffer overflow: tried %d, wrote %zu (buffer has %zu available)",
             length, written, mBuffer.sizeAvailable());
    }
}

void NativePlayer::setEq(float freq, float Q, float gain) {
    mEq.setPeakingEq(freq, Q, gain);
    LOGD("NativePlayer EQ updated: Freq=%.1fHz, Q=%.2f, Gain=%.1fdB", freq, Q, gain);
}

void* NativePlayer::audioThreadEntry(void* arg) {
    auto* player = static_cast<NativePlayer*>(arg);
    player->audioThreadLoop();
    return nullptr;
}

void NativePlayer::audioThreadLoop() {
    LOGI("=== Entering native audio thread loop ===");
    LOGI("  SR=%d, Channels=%d, Bits=%d, USB FD=%d, Descriptors=%zu bytes",
         mSampleRate, mChannels, mBitDepth, mUsbFd, mUsbDescriptors.size());
    
    const int numFrames = 512;
    const int bytesPerFrame = mChannels * (mBitDepth / 8);
    const int bufferSize = numFrames * bytesPerFrame;
    
    LOGI("  Buffer config: %d frames, %d bytes/frame, %d bytes/chunk", 
         numFrames, bytesPerFrame, bufferSize);
    
    std::vector<char> pcmBuffer(bufferSize);
    std::vector<float> floatBuffer(numFrames * mChannels);
    
    usb::UsbIsochronousStream* usbStream = nullptr;
    
    // Attempt to initialize USB stream if descriptors and FD are available
    if (mUsbFd != -1 && !mUsbDescriptors.empty()) {
        LOGI("Parsing USB descriptors for audio endpoints...");
        auto endpoints = usb::UacDescriptorParser::parse(mUsbDescriptors.data(), mUsbDescriptors.size());
        if (!endpoints.empty()) {
            LOGI("Found %zu audio endpoints. Using: addr=0x%02x, maxPkt=%d, UAC%d", 
                 endpoints.size(), endpoints[0].address, endpoints[0].maxPacketSize, endpoints[0].uacVersion);
            
            usbStream = new usb::UsbIsochronousStream(mUsbFd, endpoints[0].address, endpoints[0].maxPacketSize);
            usbStream->start();
            LOGI("USB isochronous stream started.");
        } else {
            LOGW("No suitable USB audio endpoints found in descriptors.");
        }
    } else {
        LOGI("No USB device configured (FD=%d, descriptors=%zu bytes). Running in buffer-only mode.",
             mUsbFd, mUsbDescriptors.size());
    }
    
    // ── Heartbeat / diagnostics ──
    long totalBytesConsumed = 0;
    int loopIterations = 0;
    int starvedIterations = 0;
    int usbWriteErrors = 0;
    struct timespec lastHeartbeat;
    clock_gettime(CLOCK_MONOTONIC, &lastHeartbeat);
    
    while (mIsRunning) {
        if (mIsPaused) {
            usleep(10000); // 10ms
            continue;
        }
        
        if (mBuffer.sizeAvailable() < (size_t)bufferSize) {
            starvedIterations++;
            usleep(1000); // 1ms — wait for data
            
            // ── Heartbeat every ~500ms (500 × 1ms sleeps) ──
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            long elapsedMs = (now.tv_sec - lastHeartbeat.tv_sec) * 1000 + 
                             (now.tv_nsec - lastHeartbeat.tv_nsec) / 1000000;
            if (elapsedMs >= 500) {
                LOGD("♥ HEARTBEAT: alive, starved=%d/%d iters, consumed=%ld bytes, buffer=%zu",
                     starvedIterations, loopIterations, totalBytesConsumed, mBuffer.sizeAvailable());
                starvedIterations = 0;
                loopIterations = 0;
                totalBytesConsumed = 0;
                lastHeartbeat = now;
            }
            continue;
        }
        
        mBuffer.read(pcmBuffer.data(), bufferSize);
        totalBytesConsumed += bufferSize;
        loopIterations++;
        
        // Convert to Float
        if (mBitDepth == 16) {
            const int16_t* pcm16 = reinterpret_cast<const int16_t*>(pcmBuffer.data());
            for (int i = 0; i < numFrames * mChannels; ++i) {
                floatBuffer[i] = pcm16[i] / 32768.0f;
            }
        } else if (mBitDepth == 24) {
             for (int i = 0; i < numFrames * mChannels; ++i) {
                 int32_t sample = (static_cast<uint8_t>(pcmBuffer[i*3]) << 8) |
                                  (static_cast<uint8_t>(pcmBuffer[i*3+1]) << 16) |
                                  (static_cast<int8_t>(pcmBuffer[i*3+2]) << 24);
                 floatBuffer[i] = sample / 2147483648.0f;
             }
        } else if (mBitDepth == 32) {
            const int32_t* pcm32 = reinterpret_cast<const int32_t*>(pcmBuffer.data());
            for (int i = 0; i < numFrames * mChannels; ++i) {
                floatBuffer[i] = pcm32[i] / 2147483648.0f;
            }
        }
        
        // Apply DSP
        mEq.process(floatBuffer.data(), numFrames);
        
        // Convert back to original PCM format for output
        if (mBitDepth == 16) {
            int16_t* pcm16 = reinterpret_cast<int16_t*>(pcmBuffer.data());
            for (int i = 0; i < numFrames * mChannels; ++i) {
                float sample = floatBuffer[i] * 32767.0f;
                pcm16[i] = static_cast<int16_t>(std::max(-32768.0f, std::min(32767.0f, sample)));
            }
        } else if (mBitDepth == 24) {
             for (int i = 0; i < numFrames * mChannels; ++i) {
                 int32_t sample = static_cast<int32_t>(floatBuffer[i] * 8388607.0f);
                 sample = std::max(-8388608, std::min(8388607, sample));
                 pcmBuffer[i*3] = sample & 0xFF;
                 pcmBuffer[i*3+1] = (sample >> 8) & 0xFF;
                 pcmBuffer[i*3+2] = (sample >> 16) & 0xFF;
             }
        } else if (mBitDepth == 32) {
            int32_t* pcm32 = reinterpret_cast<int32_t*>(pcmBuffer.data());
            for (int i = 0; i < numFrames * mChannels; ++i) {
                float sample = floatBuffer[i] * 2147483647.0f;
                pcm32[i] = static_cast<int32_t>(std::max(-2147483648.0f, std::min(2147483647.0f, sample)));
            }
        }

        // Output to USB if available
        if (usbStream != nullptr) {
            if (!usbStream->write(pcmBuffer.data(), bufferSize)) {
                usbWriteErrors++;
                if (usbWriteErrors <= 3 || (usbWriteErrors % 100) == 0) {
                    LOGW("USB write failed (error count: %d)", usbWriteErrors);
                }
            } else {
                usbWriteErrors = 0; // Reset on success
            }
        }
        
        // ── Heartbeat every ~500ms ──
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        long elapsedMs = (now.tv_sec - lastHeartbeat.tv_sec) * 1000 + 
                         (now.tv_nsec - lastHeartbeat.tv_nsec) / 1000000;
        if (elapsedMs >= 500) {
            long bytesPerSec = totalBytesConsumed * 1000 / (elapsedMs > 0 ? elapsedMs : 1);
            LOGI("♥ HEARTBEAT: loops=%d, consumed=%ld B, rate=%ld B/s, buf=%zu, usb_errs=%d",
                 loopIterations, totalBytesConsumed, bytesPerSec, mBuffer.sizeAvailable(), usbWriteErrors);
            starvedIterations = 0;
            loopIterations = 0;
            totalBytesConsumed = 0;
            lastHeartbeat = now;
        }
    }
    
    if (usbStream) {
        usbStream->stop();
        delete usbStream;
        LOGI("USB stream stopped and deleted.");
    }
    
    LOGI("=== Exiting native audio thread loop ===");
}

} // namespace rhythm
