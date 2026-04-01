// [SIPHON_CUSTOM_ENGINE]
#ifndef RHYTHM_UAC_DESCRIPTOR_PARSER_H
#define RHYTHM_UAC_DESCRIPTOR_PARSER_H

#include <vector>
#include <cstdint>
#include <android/log.h>

namespace rhythm {
namespace usb {

struct AudioEndpoint {
    uint8_t address;
    uint8_t attributes;
    uint16_t maxPacketSize;
    uint8_t interval;
    int uacVersion; // 1, 2, or 3
};

class UacDescriptorParser {
public:
    static std::vector<AudioEndpoint> parse(const uint8_t* descriptors, int length) {
        std::vector<AudioEndpoint> endpoints;
        int offset = 0;
        int currentUacVersion = 1;

        while (offset < length) {
            uint8_t bLength = descriptors[offset];
            uint8_t bDescriptorType = descriptors[offset + 1];

            if (bLength == 0) break;

            if (bDescriptorType == 0x04) { // Interface Descriptor
                uint8_t bInterfaceClass = descriptors[offset + 5];
                uint8_t bInterfaceSubClass = descriptors[offset + 6];
                uint8_t bInterfaceProtocol = descriptors[offset + 7];

                if (bInterfaceClass == 0x01) { // Audio Class
                    if (bInterfaceProtocol == 0x20) currentUacVersion = 2;
                    else if (bInterfaceProtocol == 0x30) currentUacVersion = 3;
                    else currentUacVersion = 1;
                }
            } else if (bDescriptorType == 0x05) { // Endpoint Descriptor
                uint8_t bEndpointAddress = descriptors[offset + 2];
                uint8_t bmAttributes = descriptors[offset + 3];
                uint16_t wMaxPacketSize = descriptors[offset + 4] | (descriptors[offset + 5] << 8);
                uint8_t bInterval = descriptors[offset + 6];

                // Check if it's an isochronous output endpoint
                if ((bEndpointAddress & 0x80) == 0 && (bmAttributes & 0x03) == 0x01) {
                    endpoints.push_back({
                        bEndpointAddress,
                        bmAttributes,
                        wMaxPacketSize,
                        bInterval,
                        currentUacVersion
                    });
                }
            }

            offset += bLength;
        }

        return endpoints;
    }
};

} // namespace usb
} // namespace rhythm

#endif // RHYTHM_UAC_DESCRIPTOR_PARSER_H
