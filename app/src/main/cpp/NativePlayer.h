#ifndef RHYTHM_NATIVE_PLAYER_H
#define RHYTHM_NATIVE_PLAYER_H

#include <pthread.h>
#include <vector>
#include <atomic>
#include <cstdint>
#include "ParametricEq.h"
#include "CircularBuffer.h"

namespace rhythm {

class NativePlayer {
public:
    NativePlayer();
    ~NativePlayer();

    bool initialize(int sampleRate, int channels, int bitDepth);
    bool start();
    void stop();
    void pause();
    void resume();
    
    void setUsbFileDescriptor(int fd);
    void setUsbDescriptors(const uint8_t* data, int length);
    void pushData(const void* data, int length);
    void setEq(float freq, float q, float gain);

private:
    static void* audioThreadEntry(void* arg);
    void audioThreadLoop();

    std::atomic<bool> mIsRunning{false};
    std::atomic<bool> mIsPaused{false};
    pthread_t mAudioThread{};
    
    int mSampleRate{44100};
    int mChannels{2};
    int mBitDepth{16};
    int mUsbFd{-1};

    ParametricEq mEq;
    CircularBuffer mBuffer;
    std::vector<uint8_t> mUsbDescriptors;
};

} // namespace rhythm

#endif // RHYTHM_NATIVE_PLAYER_H
