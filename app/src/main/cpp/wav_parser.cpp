// [SIPHON_CUSTOM_ENGINE]
#include "wav_parser.h"
#include <unistd.h>
#include <fcntl.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "WavParser"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

WavParser::WavParser() : mFd(-1), mDataOffset(0), mDataSize(0), mCurrentPos(0) {
    mFormat = {0, 0, 0, false};
}

WavParser::~WavParser() {
    close();
}

void WavParser::close() {
    if (mFd >= 0) {
        ::close(mFd);
        mFd = -1;
    }
}

bool WavParser::openFd(int fd) {
    close();
    mFd = fd;
    // reset file pointer to beginning just in case
    lseek(mFd, 0, SEEK_SET);
    
    if (!parseHeader()) {
        close();
        return false;
    }
    
    return true;
}

AudioFormat WavParser::getFormat() const {
    return mFormat;
}

size_t WavParser::readSamples(uint8_t* buffer, size_t maxBytes) {
    if (mFd < 0 || mCurrentPos >= mDataSize) return 0;
    
    size_t bytesToRead = maxBytes;
    if (mCurrentPos + bytesToRead > mDataSize) {
        bytesToRead = mDataSize - mCurrentPos;
    }
    
    ssize_t bytesRead = read(mFd, buffer, bytesToRead);
    if (bytesRead > 0) {
        mCurrentPos += bytesRead;
        return bytesRead;
    }
    return 0;
}

void WavParser::seekToMs(int64_t positionMs) {
    if (mFd < 0 || mFormat.sampleRate == 0) return;
    
    int bytesPerSample = (mFormat.bitDepth / 8) * mFormat.channelCount;
    int64_t targetByte = (positionMs * mFormat.sampleRate / 1000) * bytesPerSample;
    
    // Align to frame boundary
    targetByte -= (targetByte % bytesPerSample);
    
    if (targetByte > mDataSize) targetByte = mDataSize;
    if (targetByte < 0) targetByte = 0;
    
    mCurrentPos = targetByte;
    lseek(mFd, mDataOffset + mCurrentPos, SEEK_SET);
}

int64_t WavParser::getDurationMs() const {
    if (mFormat.sampleRate == 0 || mFormat.channelCount == 0) return 0;
    int bytesPerSample = (mFormat.bitDepth / 8) * mFormat.channelCount;
    if (bytesPerSample == 0) return 0;
    
    int64_t totalSamples = mDataSize / bytesPerSample;
    return (totalSamples * 1000) / mFormat.sampleRate;
}

bool WavParser::parseHeader() {
    uint8_t header[44];
    if (read(mFd, header, 44) != 44) {
        LOGE("Failed to read WAV header");
        return false;
    }

    if (memcmp(header, "RIFF", 4) != 0 || memcmp(header + 8, "WAVE", 4) != 0) {
        LOGE("Not a valid WAV file");
        return false;
    }

    // Basic format chunk parsing (assuming simple standard PCM chunk first)
    // A robust parser needs loop over chunks, but this works for basic validation in Phase 1
    if (memcmp(header + 12, "fmt ", 4) != 0) {
        LOGE("Fmt chunk not found where expected");
        return false;
    }

    int audioFormat = header[20] | (header[21] << 8); // 1 = PCM
    mFormat.channelCount = header[22] | (header[23] << 8);
    mFormat.sampleRate = header[24] | (header[25] << 8) | (header[26] << 16) | (header[27] << 24);
    mFormat.bitDepth = header[34] | (header[35] << 8);
    mFormat.isDSD = false; // Add DSF parser later for Phase 3

    if (audioFormat != 1 && audioFormat != 3) { // 1 = PCM, 3 = IEEE Float
        LOGE("Unsupported WAV format: %d", audioFormat);
        return false;
    }

    // Find the data chunk
    mDataOffset = 44;
    mDataSize = 0;
    
    // Quick hack for Phase 1: if data chunk is right after fmt (chunk size 16)
    if (memcmp(header + 36, "data", 4) == 0) {
        mDataSize = header[40] | (header[41] << 8) | (header[42] << 16) | (header[43] << 24);
        mDataOffset = 44;
    } else {
        // Fallback scan for 'data' chunk
        lseek(mFd, 12, SEEK_SET); // skip RIFF header
        uint8_t chunkHeader[8];
        while (read(mFd, chunkHeader, 8) == 8) {
            uint32_t chunkSize = chunkHeader[4] | (chunkHeader[5] << 8) | (chunkHeader[6] << 16) | (chunkHeader[7] << 24);
            if (memcmp(chunkHeader, "data", 4) == 0) {
                mDataSize = chunkSize;
                mDataOffset = lseek(mFd, 0, SEEK_CUR);
                break;
            }
            lseek(mFd, chunkSize, SEEK_CUR);
        }
    }

    if (mDataSize == 0) {
        LOGE("WAV data chunk not found");
        return false;
    }

    LOGI("WAV parsed: %d Hz, %d channels, %d bits, size: %zu bytes", 
         mFormat.sampleRate, mFormat.channelCount, mFormat.bitDepth, mDataSize);
    
    mCurrentPos = 0;
    lseek(mFd, mDataOffset, SEEK_SET);
    return true;
}
