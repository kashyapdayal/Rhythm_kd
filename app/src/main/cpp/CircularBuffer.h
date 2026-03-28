#ifndef RHYTHM_CIRCULAR_BUFFER_H
#define RHYTHM_CIRCULAR_BUFFER_H

#include <vector>
#include <atomic>
#include <algorithm>
#include <cstring>

namespace rhythm {

/**
 * Lock-free (for single producer, single consumer) circular buffer for audio data.
 */
class CircularBuffer {
public:
    explicit CircularBuffer(size_t capacity)
        : mData(capacity), mCapacity(capacity), mHead(0), mTail(0) {}

    size_t write(const void* data, size_t size) {
        size_t available = mCapacity - sizeAvailable();
        size_t toWrite = std::min(size, available);
        
        size_t firstPart = std::min(toWrite, mCapacity - mTail);
        std::memcpy(&mData[mTail], data, firstPart);
        
        if (toWrite > firstPart) {
            std::memcpy(&mData[0], static_cast<const char*>(data) + firstPart, toWrite - firstPart);
        }
        
        mTail = (mTail + toWrite) % mCapacity;
        mSize.fetch_add(toWrite);
        return toWrite;
    }

    size_t read(void* data, size_t size) {
        size_t available = sizeAvailable();
        size_t toRead = std::min(size, available);
        
        size_t firstPart = std::min(toRead, mCapacity - mHead);
        std::memcpy(data, &mData[mHead], firstPart);
        
        if (toRead > firstPart) {
            std::memcpy(static_cast<char*>(data) + firstPart, &mData[0], toRead - firstPart);
        }
        
        mHead = (mHead + toRead) % mCapacity;
        mSize.fetch_sub(toRead);
        return toRead;
    }

    size_t sizeAvailable() const {
        return mSize.load();
    }

    void clear() {
        mHead = 0;
        mTail = 0;
        mSize.store(0);
    }

private:
    std::vector<char> mData;
    size_t mCapacity;
    std::atomic<size_t> mHead;
    std::atomic<size_t> mTail;
    std::atomic<size_t> mSize{0};
};

} // namespace rhythm

#endif // RHYTHM_CIRCULAR_BUFFER_H
