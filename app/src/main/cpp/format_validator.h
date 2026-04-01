// [SIPHON_CUSTOM_ENGINE]
#pragma once
#include <vector>

struct AudioFormat {
    int sampleRate;
    int bitDepth;      // 16, 24, 32
    int channelCount;
    bool isDSD;
};

struct DacFormat {
    int sampleRate;
    int maxBitDepth;
    bool supportsDSD;
    bool supportsDoP;
    std::vector<int> supportedSampleRates;
};

enum class FormatValidationResult {
    EXACT_MATCH,       // Play immediately, bit-perfect
    RESAMPLE_NEEDED,   // DAC doesn't match file natively, resampler required
    UNSUPPORTED        // Can't even be resampled/played (e.g. unknown format)
};

// Validates the parsed file format against the DAC's locked/supported format
FormatValidationResult validateFormat(const AudioFormat& fileFormat, const DacFormat& dacFormat);
