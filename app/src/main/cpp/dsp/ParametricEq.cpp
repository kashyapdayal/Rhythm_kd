// [SIPHON_CUSTOM_ENGINE]
#include "ParametricEq.h"
#ifndef _USE_MATH_DEFINES
#define _USE_MATH_DEFINES
#endif
#include <cmath>

namespace rhythm {

ParametricEq::ParametricEq() : mSamplingRate(44100.0f) {
    reset();
}

void ParametricEq::reset() {
    x1 = x2 = y1 = y2 = 0.0f;
    a0 = 1.0f; a1 = a2 = b0 = b1 = b2 = 0.0f;
}

void ParametricEq::setPeakingEq(float freq, float Q, float gainDb) {
    float A = std::pow(10.0f, gainDb / 40.0f);
    float omega = 2.0f * M_PI * freq / mSamplingRate;
    float sn = std::sin(omega);
    float cs = std::cos(omega);
    float alpha = sn / (2.0f * Q);

    b0 = 1.0f + alpha * A;
    b1 = -2.0f * cs;
    b2 = 1.0f - alpha * A;
    a0 = 1.0f + alpha / A;
    a1 = -2.0f * cs;
    a2 = 1.0f - alpha / A;

    // Normalize coefficients
    b0 /= a0;
    b1 /= a0;
    b2 /= a0;
    a1 /= a0;
    a2 /= a0;
}

void ParametricEq::process(float* buffer, int numFrames) {
    for (int i = 0; i < numFrames * 2; ++i) { // Assuming stereo
        float x = buffer[i];
        float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        
        x2 = x1;
        x1 = x;
        y2 = y1;
        y1 = y;
        
        buffer[i] = y;
    }
}

} // namespace rhythm
