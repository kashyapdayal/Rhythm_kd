// [SIPHON_CUSTOM_ENGINE]
#pragma once
#include <cstdint>
#include <cstddef>
#include "format_validator.h"

// A lightweight, dependency-free WAV RIFF parser
class WavParser {
public:
    WavParser();
    ~WavParser();

    // Open via Android file descriptor (e.g., from ContentResolver)
    bool openFd(int fd);
    void close();

    AudioFormat getFormat() const;
    size_t readSamples(uint8_t* buffer, size_t maxBytes);
    void seekToMs(int64_t positionMs);
    int64_t getDurationMs() const;

private:
    int mFd;
    AudioFormat mFormat;
    size_t mDataOffset;
    size_t mDataSize;
    size_t mCurrentPos; // Byte position within data section
    
    bool parseHeader();
};
