// [SIPHON_CUSTOM_ENGINE]
#include "format_validator.h"

FormatValidationResult validateFormat(const AudioFormat& file, const DacFormat& dac) {
    if (file.isDSD && !dac.supportsDSD && !dac.supportsDoP) {
        return FormatValidationResult::UNSUPPORTED;
    }
    
    if (file.sampleRate != dac.sampleRate) {
        // Check if DAC supports the file's rate at all
        bool isSupported = false;
        for (int rate : dac.supportedSampleRates) {
            if (rate == file.sampleRate) {
                isSupported = true;
                break;
            }
        }
        
        if (!isSupported) {
            return FormatValidationResult::UNSUPPORTED;
        }
        
        // If it's supported by the DAC but currently the DAC isn't set to this,
        // it means we either need to reset the DAC alternate setting or resample.
        // In Bit-Perfect mode, resampling is strictly denied by the engine logic.
        return FormatValidationResult::RESAMPLE_NEEDED; 
    }
    
    if (file.bitDepth > dac.maxBitDepth) {
        return FormatValidationResult::RESAMPLE_NEEDED;
    }
    
    return FormatValidationResult::EXACT_MATCH;
}
