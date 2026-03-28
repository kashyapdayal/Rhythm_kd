#ifndef RHYTHM_USB_TRANSFER_H
#define RHYTHM_USB_TRANSFER_H

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#ifndef USBDEVFS_REAPURBNDELAY
#define USBDEVFS_REAPURBNDELAY _IOW('U', 13, void *)
#endif

#include <unistd.h>
#include <vector>
#include <cstdint>
#include <cstring>
#include <android/log.h>
#include <errno.h>
#include <algorithm>

#define TAG_USB "RhythmUsbTransfer"
#ifndef LOGE_USB
#define LOGE_USB(...) __android_log_print(ANDROID_LOG_ERROR, TAG_USB, __VA_ARGS__)
#endif
#ifndef LOGD_USB
#define LOGD_USB(...) __android_log_print(ANDROID_LOG_DEBUG, TAG_USB, __VA_ARGS__)
#endif

namespace rhythm {
namespace usb {

// Helper struct to manage the URB and its trailing isochronous frame descriptors
struct UrbContext {
    std::vector<uint8_t> urbMemory; // Holds struct usbdevfs_urb + frame descriptors
    std::vector<uint8_t> dataBuffer; // Holds the actual PCM data
    
    struct usbdevfs_urb* getUrb() {
        return reinterpret_cast<struct usbdevfs_urb*>(urbMemory.data());
    }
};

class UsbIsochronousStream {
public:
    static constexpr int NUM_URBS = 4;
    static constexpr int PACKETS_PER_URB = 8;

    UsbIsochronousStream(int fd, uint8_t endpoint, uint16_t maxPacketSize)
        : mFd(fd), mEndpoint(endpoint), mMaxPacketSize(maxPacketSize) {
        
        mContexts.resize(NUM_URBS);
        
        for (int i = 0; i < NUM_URBS; ++i) {
            setupUrb(i);
        }
    }

    ~UsbIsochronousStream() {
        stop();
    }

    void start() {
        mIsRunning = true;
        LOGD_USB("Starting USB isochronous stream on endpoint 0x%02x", mEndpoint);
        for (int i = 0; i < NUM_URBS; ++i) {
            submitUrb(i);
        }
    }

    void stop() {
        mIsRunning = false;
        // In a production app, we would use USBDEVFS_DISCARDURB here
    }

    bool write(const void* data, int length) {
        if (!mIsRunning) return false;

        // Reap a completed URB
        struct usbdevfs_urb* reapedUrb = nullptr;
        int ret = ioctl(mFd, USBDEVFS_REAPURBNDELAY, &reapedUrb);
        
        if (ret < 0) {
            // No URB ready yet.
            return false; 
        }

        // Find which context this URB belongs to
        int index = -1;
        for (int i = 0; i < NUM_URBS; ++i) {
            if (mContexts[i].getUrb() == reapedUrb) {
                index = i;
                break;
            }
        }

        if (index != -1) {
            // Fill the URB buffer with the new data
            int bytesToCopy = std::min((int)mContexts[index].dataBuffer.size(), length);
            memcpy(mContexts[index].dataBuffer.data(), data, bytesToCopy);
            
            // Re-submit
            submitUrb(index);
            return true;
        }

        return false;
    }

private:
    void setupUrb(int index) {
        auto& ctx = mContexts[index];
        
        // Allocate memory for URB + frame descriptors
        size_t urbSize = sizeof(struct usbdevfs_urb) + 
                         sizeof(struct usbdevfs_iso_packet_desc) * PACKETS_PER_URB;
        ctx.urbMemory.assign(urbSize, 0);
        
        // Allocate memory for the actual data
        ctx.dataBuffer.assign(mMaxPacketSize * PACKETS_PER_URB, 0);
        
        struct usbdevfs_urb* urb = ctx.getUrb();
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = mEndpoint;
        urb->buffer = ctx.dataBuffer.data();
        urb->buffer_length = ctx.dataBuffer.size();
        urb->number_of_packets = PACKETS_PER_URB;
        
        // Initialize packet descriptors
        auto* packets = reinterpret_cast<struct usbdevfs_iso_packet_desc*>(urb + 1);
        for (int p = 0; p < PACKETS_PER_URB; ++p) {
            packets[p].length = mMaxPacketSize;
        }
    }

    void submitUrb(int index) {
        if (ioctl(mFd, USBDEVFS_SUBMITURB, mContexts[index].getUrb()) < 0) {
            LOGE_USB("Failed to submit URB %d: %s (errno %d)", index, strerror(errno), errno);
        }
    }

    int mFd;
    uint8_t mEndpoint;
    uint16_t mMaxPacketSize;
    bool mIsRunning{false};
    
    std::vector<UrbContext> mContexts;
};

} // namespace usb
} // namespace rhythm

#endif // RHYTHM_USB_TRANSFER_H
