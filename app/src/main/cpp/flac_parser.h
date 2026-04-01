// [SIPHON_CUSTOM_ENGINE]
#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>
#include <mutex>
#include "format_validator.h"
#include <FLAC/stream_decoder.h>

class FlacParser {
public:
    FlacParser();
    ~FlacParser();

    bool openFd(int fd);
    void close();

    AudioFormat getFormat() const;
    size_t readSamples(uint8_t* buffer, size_t maxBytes);
    void seekToMs(int64_t positionMs);
    int64_t getDurationMs() const;

private:
    int mFd;
    AudioFormat mFormat;
    int64_t mTotalSamples;
    int64_t mCurrentSample;

    FLAC__StreamDecoder* mDecoder;
    std::mutex mMutex;

    // Buffer to hold decoded bytes internally until readSamples picks them up
    std::vector<uint8_t> mDecodedBuffer;
    size_t mBufferReadPos;

    static FLAC__StreamDecoderReadStatus readCallback(
        const FLAC__StreamDecoder* decoder,
        FLAC__byte buffer[],
        size_t* bytes,
        void* client_data);

    static FLAC__StreamDecoderSeekStatus seekCallback(
        const FLAC__StreamDecoder* decoder,
        FLAC__uint64 absolute_byte_offset,
        void* client_data);

    static FLAC__StreamDecoderTellStatus tellCallback(
        const FLAC__StreamDecoder* decoder,
        FLAC__uint64* absolute_byte_offset,
        void* client_data);

    static FLAC__StreamDecoderLengthStatus lengthCallback(
        const FLAC__StreamDecoder* decoder,
        FLAC__uint64* stream_length,
        void* client_data);

    static FLAC__bool eofCallback(
        const FLAC__StreamDecoder* decoder,
        void* client_data);

    static FLAC__StreamDecoderWriteStatus writeCallback(
        const FLAC__StreamDecoder* decoder,
        const FLAC__Frame* frame,
        const FLAC__int32* const buffer[],
        void* client_data);

    static void metadataCallback(
        const FLAC__StreamDecoder* decoder,
        const FLAC__StreamMetadata* metadata,
        void* client_data);

    static void errorCallback(
        const FLAC__StreamDecoder* decoder,
        FLAC__StreamDecoderErrorStatus status,
        void* client_data);
};
