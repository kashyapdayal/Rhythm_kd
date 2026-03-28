#ifndef RHYTHM_PARAMETRIC_EQ_H
#define RHYTHM_PARAMETRIC_EQ_H

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace rhythm {

class ParametricEq {
public:
    ParametricEq();
    ~ParametricEq() = default;

    void process(float* buffer, int numFrames);
    void reset();
    void setSamplingRate(float rate) { mSamplingRate = rate; }
    void setPeakingEq(float freq, float Q, float gainDb);

private:
    float mSamplingRate;
    
    // Filter coefficients
    float a0, a1, a2, b0, b1, b2;
    
    // Delay elements
    float x1, x2, y1, y2;
};

} // namespace rhythm

#endif // RHYTHM_PARAMETRIC_EQ_H
