// DSD to PCM inner loops (see Dsd.kt): the byte-table FIR at the DSD rate, and the decimating FIR.
#include <jni.h>
#include <stdint.h>

extern "C" {

// out[outOff + i] = sum over g of lut[g * 256 + in[i * step + g]], for i in [0, count).
JNIEXPORT void JNICALL Java_com_hassasin_music_DsdNative_stage1(JNIEnv* env, jclass, jfloatArray jlut, jint groups, jint step,
                                                               jbyteArray jin, jint count, jfloatArray jout, jint outOff) {
    float* lut = (float*) env->GetPrimitiveArrayCritical(jlut, nullptr);
    uint8_t* in = (uint8_t*) env->GetPrimitiveArrayCritical(jin, nullptr);
    float* out = (float*) env->GetPrimitiveArrayCritical(jout, nullptr);
    for (int i = 0; i < count; i++) {
        const uint8_t* b = in + (long) i * step;
        float s0 = 0, s1 = 0, s2 = 0, s3 = 0;
        int g = 0;
        for (; g + 4 <= groups; g += 4) {
            s0 += lut[(g << 8) | b[g]];
            s1 += lut[((g + 1) << 8) | b[g + 1]];
            s2 += lut[((g + 2) << 8) | b[g + 2]];
            s3 += lut[((g + 3) << 8) | b[g + 3]];
        }
        for (; g < groups; g++) s0 += lut[(g << 8) | b[g]];
        out[outOff + i] = (s0 + s1) + (s2 + s3);
    }
    env->ReleasePrimitiveArrayCritical(jout, out, 0);
    env->ReleasePrimitiveArrayCritical(jin, in, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(jlut, lut, JNI_ABORT);
}

// out[f * stride + offset] = gain * sum over k of h[k] * mid[f * dec + k], for f in [0, frames).
JNIEXPORT void JNICALL Java_com_hassasin_music_DsdNative_decimate(JNIEnv* env, jclass, jfloatArray jh, jint n, jfloatArray jmid, jint dec,
                                                                 jint frames, jfloatArray jout, jint stride, jint offset, jfloat gain) {
    float* h = (float*) env->GetPrimitiveArrayCritical(jh, nullptr);
    float* mid = (float*) env->GetPrimitiveArrayCritical(jmid, nullptr);
    float* out = (float*) env->GetPrimitiveArrayCritical(jout, nullptr);
    for (int f = 0; f < frames; f++) {
        const float* m = mid + (long) f * dec;
        float s = 0;
        for (int k = 0; k < n; k++) s += h[k] * m[k];
        out[(long) f * stride + offset] = s * gain;
    }
    env->ReleasePrimitiveArrayCritical(jout, out, 0);
    env->ReleasePrimitiveArrayCritical(jmid, mid, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(jh, h, JNI_ABORT);
}

}
