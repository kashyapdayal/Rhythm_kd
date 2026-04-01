// [SIPHON_CUSTOM_ENGINE]
#include "flac_parser.h"
#include <unistd.h>
#include <android/log.h>
#include <cstring>
#include <sys/types.h>

#define LOG_TAG "FlacParser"
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

FlacParser::FlacParser()
    : mFd(-1), mTotalSamples(0), mCurrentSample(0), mDecoder(nullptr), mBufferReadPos(0) {
    mFormat.channelCount = 2;
    mFormat.sampleRate = 44100;
    mFormat.bitDepth = 16;
}

FlacParser::~FlacParser() {
    close();
}

bool FlacParser::openFd(int fd) {
    close();
    mFd = dup(fd);
    if (mFd < 0) {
        ALOGE("Failed to dup fd");
        return false;
    }

    lseek(mFd, 0, SEEK_SET);

    mDecoder = FLAC__stream_decoder_new();
    if (!mDecoder) {
        ALOGE("Failed to create FLAC decoder");
        return false;
    }

    FLAC__stream_decoder_set_md5_checking(mDecoder, false);

    FLAC__StreamDecoderInitStatus initStatus = FLAC__stream_decoder_init_stream(
        mDecoder,
        readCallback,
        seekCallback,
        tellCallback,
        lengthCallback,
        eofCallback,
        writeCallback,
        metadataCallback,
        errorCallback,
        this
    );

    if (initStatus != FLAC__STREAM_DECODER_INIT_STATUS_OK) {
        ALOGE("Failed to initialize FLAC decoder: %d", initStatus);
        close();
        return false;
    }

    if (!FLAC__stream_decoder_process_until_end_of_metadata(mDecoder)) {
        ALOGE("Failed to read FLAC metadata");
        close();
        return false;
    }

    return true;
}

void FlacParser::close() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mDecoder) {
        FLAC__stream_decoder_finish(mDecoder);
        FLAC__stream_decoder_delete(mDecoder);
        mDecoder = nullptr;
    }
    if (mFd >= 0) {
        ::close(mFd);
        mFd = -1;
    }
    mDecodedBuffer.clear();
    mBufferReadPos = 0;
}

AudioFormat FlacParser::getFormat() const {
    return mFormat;
}

int64_t FlacParser::getDurationMs() const {
    if (mFormat.sampleRate == 0) return 0;
    return (mTotalSamples * 1000) / mFormat.sampleRate;
}

size_t FlacParser::readSamples(uint8_t* buffer, size_t maxBytes) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mDecoder || mFd < 0) return 0;

    size_t bytesWritten = 0;
    while (bytesWritten < maxBytes) {
        if (mBufferReadPos >= mDecodedBuffer.size()) {
            mDecodedBuffer.clear();
            mBufferReadPos = 0;
            if (FLAC__stream_decoder_get_state(mDecoder) == FLAC__STREAM_DECODER_END_OF_STREAM) {
                break;
            }
            if (!FLAC__stream_decoder_process_single(mDecoder)) {
                break;
            }
            if (mDecodedBuffer.empty()) {
                if (FLAC__stream_decoder_get_state(mDecoder) == FLAC__STREAM_DECODER_END_OF_STREAM) {
                    break;
                }
                continue;
            }
        }

        size_t bytesAvailable = mDecodedBuffer.size() - mBufferReadPos;
        size_t bytesToCopy = std::min(bytesAvailable, maxBytes - bytesWritten);
        memcpy(buffer + bytesWritten, mDecodedBuffer.data() + mBufferReadPos, bytesToCopy);
        bytesWritten += bytesToCopy;
        mBufferReadPos += bytesToCopy;
    }
    return bytesWritten;
}

void FlacParser::seekToMs(int64_t positionMs) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mDecoder || mFd < 0 || mFormat.sampleRate == 0) return;

    int64_t targetSample = (positionMs * mFormat.sampleRate) / 1000;
    if (targetSample >= mTotalSamples) targetSample = mTotalSamples - 1;

    FLAC__stream_decoder_seek_absolute(mDecoder, targetSample);
    mDecodedBuffer.clear();
    mBufferReadPos = 0;
}

FLAC__StreamDecoderReadStatus FlacParser::readCallback(
    const FLAC__StreamDecoder* decoder,
    FLAC__byte buffer[],
    size_t* bytes,
    void* client_data) {
    FlacParser* parser = static_cast<FlacParser*>(client_data);
    if (*bytes > 0) {
        ssize_t read_bytes = read(parser->mFd, buffer, *bytes);
        if (read_bytes < 0) return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
        if (read_bytes == 0) return FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM;
        *bytes = read_bytes;
        return FLAC__STREAM_DECODER_READ_STATUS_CONTINUE;
    }
    return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
}

FLAC__StreamDecoderSeekStatus FlacParser::seekCallback(
    const FLAC__StreamDecoder* decoder,
    FLAC__uint64 absolute_byte_offset,
    void* client_data) {
    FlacParser* parser = static_cast<FlacParser*>(client_data);
    if (lseek(parser->mFd, absolute_byte_offset, SEEK_SET) < 0) {
        return FLAC__STREAM_DECODER_SEEK_STATUS_ERROR;
    }
    return FLAC__STREAM_DECODER_SEEK_STATUS_OK;
}

FLAC__StreamDecoderTellStatus FlacParser::tellCallback(
    const FLAC__StreamDecoder* decoder,
    FLAC__uint64* absolute_byte_offset,
    void* client_data) {
    FlacParser* parser = static_cast<FlacParser*>(client_data);
    off_t pos = lseek(parser->mFd, 0, SEEK_CUR);
    if (pos < 0) return FLAC__STREAM_DECODER_TELL_STATUS_ERROR;
    *absolute_byte_offset = pos;
    return FLAC__STREAM_DECODER_TELL_STATUS_OK;
}

FLAC__StreamDecoderLengthStatus FlacParser::lengthCallback(
    const FLAC__StreamDecoder* decoder,
    FLAC__uint64* stream_length,
    void* client_data) {
    FlacParser* parser = static_cast<FlacParser*>(client_data);
    off_t current = lseek(parser->mFd, 0, SEEK_CUR);
    off_t length = lseek(parser->mFd, 0, SEEK_END);
    lseek(parser->mFd, current, SEEK_SET);
    if (length < 0) return FLAC__STREAM_DECODER_LENGTH_STATUS_ERROR;
    *stream_length = length;
    return FLAC__STREAM_DECODER_LENGTH_STATUS_OK;
}

FLAC__bool FlacParser::eofCallback(
    const FLAC__StreamDecoder* decoder,
    void* client_data) {
    FlacParser* parser = static_cast<FlacParser*>(client_data);
    off_t current = lseek(parser->mFd, 0, SEEK_CUR);
    off_t length = lseek(parser->mFd, 0, SEEK_END);
    lseek(parser->mFd, current, SEEK_SET);
    return current >= length;
}

FLAC__StreamDecoderWriteStatus FlacParser::writeCallback(
    const FLAC__StreamDecoder* decoder,
    const FLAC__Frame* frame,
    const FLAC__int32* const buffer[],
    void* client_data) {
    FlacParser* parser = static_cast<FlacParser*>(client_data);
    unsigned channels = frame->header.channels;
    unsigned bps = frame->header.bits_per_sample;
    unsigned samples = frame->header.blocksize;

    size_t byte_per_sample = bps / 8;
    size_t bufferSize = samples * channels * byte_per_sample;
    parser->mDecodedBuffer.reserve(parser->mDecodedBuffer.size() + bufferSize);

    for (unsigned i = 0; i < samples; i++) {
        for (unsigned c = 0; c < channels; c++) {
            FLAC__int32 sample = buffer[c][i];
            if (bps == 16) {
                int16_t s = sample;
                parser->mDecodedBuffer.push_back(s & 0xFF);
                parser->mDecodedBuffer.push_back((s >> 8) & 0xFF);
            } else if (bps == 24) {
                parser->mDecodedBuffer.push_back(sample & 0xFF);
                parser->mDecodedBuffer.push_back((sample >> 8) & 0xFF);
                parser->mDecodedBuffer.push_back((sample >> 16) & 0xFF);
            } else if (bps == 32) {
                parser->mDecodedBuffer.push_back(sample & 0xFF);
                parser->mDecodedBuffer.push_back((sample >> 8) & 0xFF);
                parser->mDecodedBuffer.push_back((sample >> 16) & 0xFF);
                parser->mDecodedBuffer.push_back((sample >> 24) & 0xFF);
            }
        }
    }

    return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}

void FlacParser::metadataCallback(
    const FLAC__StreamDecoder* decoder,
    const FLAC__StreamMetadata* metadata,
    void* client_data) {
    FlacParser* parser = static_cast<FlacParser*>(client_data);
    if (metadata->type == FLAC__METADATA_TYPE_STREAMINFO) {
        parser->mFormat.sampleRate = metadata->data.stream_info.sample_rate;
        parser->mFormat.channelCount = metadata->data.stream_info.channels;
        parser->mFormat.bitDepth = metadata->data.stream_info.bits_per_sample;
        parser->mTotalSamples = metadata->data.stream_info.total_samples;
        ALOGV("FLAC Metadata: %d Hz, %d channels, %d bits",
            parser->mFormat.sampleRate, parser->mFormat.channelCount, parser->mFormat.bitDepth);
    }
}

void FlacParser::errorCallback(
    const FLAC__StreamDecoder* decoder,
    FLAC__StreamDecoderErrorStatus status,
    void* client_data) {
    ALOGE("FLAC decoder error: %d", status);
}
