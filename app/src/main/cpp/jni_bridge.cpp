// [SIPHON_CUSTOM_ENGINE]
#include <jni.h>
#include <string>
#include <android/log.h>
#include <thread>
#include <atomic>
#include <unistd.h>
#include <fcntl.h>
#include "wav_parser.h"
#include "flac_parser.h"
#include "siphon_usb_driver.cpp"

#define LOG_TAG "RhythmNativeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

enum class DecoderType { NONE, WAV, FLAC };
static DecoderType gCurrentDecoder = DecoderType::NONE;

static WavParser gWavParser;
static FlacParser gFlacParser;

static std::thread gDecoderThread;
static std::atomic<bool> gIsPlaying{false};
static std::atomic<bool> gStopThread{false};
static SiphonUsbDriver* gUsbOutput = nullptr;

void decoderLoop() {
    uint8_t buffer[8192];
    while (!gStopThread) {
        if (gIsPlaying) {
            size_t bytesRead = 0;
            if (gCurrentDecoder == DecoderType::WAV) {
                bytesRead = gWavParser.readSamples(buffer, sizeof(buffer));
            } else if (gCurrentDecoder == DecoderType::FLAC) {
                bytesRead = gFlacParser.readSamples(buffer, sizeof(buffer));
            }

            if (bytesRead > 0 && gUsbOutput) {
                // For direct bit-perfect, hardware mode = true (unmodified)
                gUsbOutput->submitAudioData(buffer, bytesRead, true, 1.0f);
            } else if (bytesRead == 0 && gCurrentDecoder != DecoderType::NONE) {
                gIsPlaying = false; // EOF
            }
        } else {
            usleep(10000); // 10ms sleep when paused
        }
    }
}

extern "C" {

JNIEXPORT void JNICALL
Java_chromahub_rhythm_app_engine_NativeUsbEngine_nativeInit(JNIEnv *env, jobject thiz) {
    LOGI("nativeInit called - Setting up Audio Engine");
    if (!gUsbOutput) {
        gUsbOutput = new SiphonUsbDriver();
    }
}

JNIEXPORT void JNICALL
Java_chromahub_rhythm_app_engine_NativeUsbEngine_nativeLoad(JNIEnv *env, jobject thiz, jint fd) {
    LOGI("nativeLoad called with FD: %d", fd);
    gStopThread = true;
    if (gDecoderThread.joinable()) {
        gDecoderThread.join();
    }

    gWavParser.close();
    gFlacParser.close();
    gCurrentDecoder = DecoderType::NONE;

    // Detect format
    char magic[4];
    lseek(fd, 0, SEEK_SET);
    int readBytes = read(fd, magic, 4);
    lseek(fd, 0, SEEK_SET);

    gStopThread = false;

    if (readBytes == 4 && magic[0] == 'R' && magic[1] == 'I' && magic[2] == 'F' && magic[3] == 'F') {
        if (gWavParser.openFd(fd)) {
            gCurrentDecoder = DecoderType::WAV;
            AudioFormat format = gWavParser.getFormat();
            LOGI("WAV Format: %d Hz, %d bit, %d ch", format.sampleRate, format.bitDepth, format.channelCount);
        } else {
            LOGE("Failed to parse WAV from FD");
        }
    } else if (readBytes == 4 && magic[0] == 'f' && magic[1] == 'L' && magic[2] == 'a' && magic[3] == 'C') {
        if (gFlacParser.openFd(fd)) {
            gCurrentDecoder = DecoderType::FLAC;
            AudioFormat format = gFlacParser.getFormat();
            LOGI("FLAC Format: %d Hz, %d bit, %d ch", format.sampleRate, format.bitDepth, format.channelCount);
        } else {
            LOGE("Failed to parse FLAC from FD");
        }
    } else {
        LOGE("Unknown audio format. Magic: %c%c%c%c", magic[0], magic[1], magic[2], magic[3]);
    }
}

JNIEXPORT void JNICALL
Java_chromahub_rhythm_app_engine_NativeUsbEngine_nativePlay(JNIEnv *env, jobject thiz) {
    LOGI("nativePlay called");
    if (!gDecoderThread.joinable()) {
        gDecoderThread = std::thread(decoderLoop);
    }
    gIsPlaying = true;
}

JNIEXPORT void JNICALL
Java_chromahub_rhythm_app_engine_NativeUsbEngine_nativePause(JNIEnv *env, jobject thiz) {
    LOGI("nativePause called");
    gIsPlaying = false;
}

JNIEXPORT void JNICALL
Java_chromahub_rhythm_app_engine_NativeUsbEngine_nativeStop(JNIEnv *env, jobject thiz) {
    LOGI("nativeStop called");
    gIsPlaying = false;
    gStopThread = true;
    if (gDecoderThread.joinable()) gDecoderThread.join();
    gWavParser.close();
    gFlacParser.close();
    gCurrentDecoder = DecoderType::NONE;
}

JNIEXPORT void JNICALL
Java_chromahub_rhythm_app_engine_NativeUsbEngine_nativeSeek(JNIEnv *env, jobject thiz, jlong position_ms) {
    LOGI("nativeSeek called to %lld ms", (long long)position_ms);
    if (gCurrentDecoder == DecoderType::WAV) {
        gWavParser.seekToMs(position_ms);
    } else if (gCurrentDecoder == DecoderType::FLAC) {
        gFlacParser.seekToMs(position_ms);
    }
}

JNIEXPORT void JNICALL
Java_chromahub_rhythm_app_engine_NativeUsbEngine_nativeRelease(JNIEnv *env, jobject thiz) {
    LOGI("nativeRelease called");
    Java_chromahub_rhythm_app_engine_NativeUsbEngine_nativeStop(env, thiz);
    if (gUsbOutput) {
        delete gUsbOutput;
        gUsbOutput = nullptr;
    }
}

JNIEXPORT jlong JNICALL
Java_chromahub_rhythm_app_engine_NativeUsbEngine_nativeGetDuration(JNIEnv *env, jobject thiz) {
    if (gCurrentDecoder == DecoderType::WAV) return gWavParser.getDurationMs();
    if (gCurrentDecoder == DecoderType::FLAC) return gFlacParser.getDurationMs();
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_chromahub_rhythm_app_engine_NativeUsbEngine_nativeIsPlaying(JNIEnv *env, jobject thiz) {
    return gIsPlaying;
}

} // extern "C"
