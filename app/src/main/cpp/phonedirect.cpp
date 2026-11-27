// Phone direct output: an AAudio EXCLUSIVE (MMAP) stream on the phone's own speaker/headphone
// output, which writes straight into the hardware buffer and skips Android's mixer and effects.
// The hardware runs at a fixed rate (48 kHz on most phones): songs at that rate pass bit-exact;
// others are converted here with a windowed-sinc resampler (Kaiser, ~-110 dB stopband).
//
// Input from Java: 32-bit little-endian stereo frames at the song's rate.

#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <dlfcn.h>
#include <mutex>
#include <vector>

#define TAG "PhoneDirect"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// ---- windowed-sinc resampler (stereo, double precision) ----
struct Resampler {
    int inRate = 0, outRate = 0;
    double step = 1.0;        // input frames per output frame
    double cutoff = 1.0;      // relative to input Nyquist
    int half = 0;             // half filter length in input frames
    static constexpr int PHASES = 1024;
    std::vector<double> table; // (PHASES + 1) x (2 * half)
    std::vector<double> hist;  // interleaved stereo input history
    double pos = 0;            // position of the next output, in history frames

    static double bessel0(double x) {
        double s = 1, t = 1;
        for (int k = 1; k < 40; k++) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; }
        return s;
    }

    void setup(int in, int out) {
        inRate = in; outRate = out;
        hist.clear(); pos = 0;
        if (in == out) { half = 0; table.clear(); return; }
        step = (double) in / out;
        cutoff = (in > out ? (double) out / in : 1.0) * 0.96;
        half = (int) std::ceil(32.0 / cutoff);
        const double beta = 10.0, i0b = bessel0(beta);
        table.assign((size_t) (PHASES + 1) * 2 * half, 0.0);
        for (int p = 0; p <= PHASES; p++) {
            double frac = (double) p / PHASES;
            double sum = 0;
            for (int k = 0; k < 2 * half; k++) {
                double t = (k - half + 1) - frac;              // distance from the output point
                double x = t / half;
                double w = std::fabs(x) >= 1 ? 0 : bessel0(beta * std::sqrt(1 - x * x)) / i0b;
                double a = M_PI * cutoff * t;
                double sinc = std::fabs(a) < 1e-12 ? 1.0 : std::sin(a) / a;
                double c = cutoff * sinc * w;
                table[(size_t) p * 2 * half + k] = c;
                sum += c;
            }
            for (int k = 0; k < 2 * half; k++) table[(size_t) p * 2 * half + k] /= sum;  // unity gain
        }
    }

    size_t historyFrames() const { return hist.size() / 2; }

    void push(const int32_t* src, size_t frames) {
        size_t old = hist.size();
        hist.resize(old + frames * 2);
        for (size_t i = 0; i < frames * 2; i++) hist[old + i] = src[i];
    }

    // Writes up to maxOut frames; returns how many.
    size_t pull(int32_t* dst, size_t maxOut) {
        size_t n = 0;
        size_t frames = historyFrames();
        while (n < maxOut) {
            long base = (long) std::floor(pos);
            if (base + half + 1 >= (long) frames) break;          // needs more input
            if (base - half + 1 < 0) { pos += step; continue; }   // warm-up at the very start
            double frac = pos - base;
            double fp = frac * PHASES;
            int p = (int) fp;
            double mix = fp - p;
            const double* c0 = &table[(size_t) p * 2 * half];
            const double* c1 = &table[(size_t) (p + 1) * 2 * half];
            double l = 0, r = 0;
            const double* h = &hist[(size_t) (base - half + 1) * 2];
            for (int k = 0; k < 2 * half; k++) {
                double c = c0[k] + (c1[k] - c0[k]) * mix;
                l += h[2 * k] * c;
                r += h[2 * k + 1] * c;
            }
            dst[2 * n] = (int32_t) std::lround(std::fmax(-2147483648.0, std::fmin(2147483647.0, l)));
            dst[2 * n + 1] = (int32_t) std::lround(std::fmax(-2147483648.0, std::fmin(2147483647.0, r)));
            n++;
            pos += step;
        }
        // Drop history that no future output needs.
        long keepFrom = (long) std::floor(pos) - half;
        if (keepFrom > 4096) {
            hist.erase(hist.begin(), hist.begin() + keepFrom * 2);
            pos -= keepFrom;
        }
        return n;
    }
};

struct Engine {
    AAudioStream* stream = nullptr;
    int hwRate = 48000;
    int format = AAUDIO_FORMAT_PCM_FLOAT;
    bool exclusive = false;
    // Ring of 32-bit stereo frames at the hardware rate (single producer / single consumer).
    std::vector<int32_t> ring;
    size_t capacity = 0;             // frames
    std::atomic<uint64_t> writePos{0}, readPos{0};
    std::atomic<bool> paused{true};
    std::atomic<bool> running{false};
    std::atomic<long> underruns{0};
    std::atomic<uint64_t> played{0};  // hardware frames played (excluding silence while paused)
    std::mutex writeLock;
    Resampler rs;
    int inRate = 48000;
    std::vector<int32_t> scratch;
};

aaudio_data_callback_result_t onData(AAudioStream*, void* user, void* audio, int32_t frames) {
    auto* e = (Engine*) user;
    uint64_t r = e->readPos.load(std::memory_order_acquire);
    uint64_t w = e->writePos.load(std::memory_order_acquire);
    uint64_t avail = w - r;
    size_t take = 0;
    if (!e->paused.load()) {
        take = (size_t) std::min<uint64_t>(avail, (uint64_t) frames);
        if (take < (size_t) frames && avail == 0 && e->played.load() > 0) e->underruns++;
    }
    if (e->format == AAUDIO_FORMAT_PCM_I32) {
        auto* out = (int32_t*) audio;
        for (size_t i = 0; i < take; i++) {
            size_t idx = (size_t) ((r + i) % e->capacity) * 2;
            out[2 * i] = e->ring[idx];
            out[2 * i + 1] = e->ring[idx + 1];
        }
        memset(out + take * 2, 0, (frames - take) * 2 * sizeof(int32_t));
    } else if (e->format == AAUDIO_FORMAT_PCM_I16) {
        // 16-bit hardware: TPDF dither from 32-bit.
        auto* out = (int16_t*) audio;
        static uint32_t seed = 22222;
        for (size_t i = 0; i < take * 2; i++) {
            size_t idx = (size_t) ((r + i / 2) % e->capacity) * 2 + (i & 1);
            int64_t v = e->ring[idx];
            seed = seed * 1664525u + 1013904223u; int32_t d1 = (int32_t) (seed >> 16) & 0xFFFF;
            seed = seed * 1664525u + 1013904223u; int32_t d2 = (int32_t) (seed >> 16) & 0xFFFF;
            v += (int64_t) (d1 - d2);
            int64_t q = (v + 32768) >> 16;
            out[i] = (int16_t) std::max<int64_t>(-32768, std::min<int64_t>(32767, q));
        }
        memset(out + take * 2, 0, (frames - take) * 2 * sizeof(int16_t));
    } else if (e->format == AAUDIO_FORMAT_PCM_I24_PACKED) {
        auto* out = (uint8_t*) audio;
        for (size_t i = 0; i < take * 2; i++) {
            size_t idx = (size_t) ((r + i / 2) % e->capacity) * 2 + (i & 1);
            int32_t v = e->ring[idx] >> 8;
            out[3 * i] = (uint8_t) v; out[3 * i + 1] = (uint8_t) (v >> 8); out[3 * i + 2] = (uint8_t) (v >> 16);
        }
        memset(out + take * 6, 0, (frames - take) * 6);
    } else {
        auto* out = (float*) audio;
        for (size_t i = 0; i < take; i++) {
            size_t idx = (size_t) ((r + i) % e->capacity) * 2;
            out[2 * i] = (float) (e->ring[idx] / 2147483648.0);
            out[2 * i + 1] = (float) (e->ring[idx + 1] / 2147483648.0);
        }
        memset(out + take * 2, 0, (frames - take) * 2 * sizeof(float));
    }
    e->readPos.store(r + take, std::memory_order_release);
    e->played += take;
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void onError(AAudioStream*, void* user, aaudio_result_t err) {
    auto* e = (Engine*) user;
    LOGW("stream error %d (%s): output lost", err, AAudio_convertResultToText(err));
    e->running = false;
}

}  // namespace

extern "C" {

// Opens the exclusive stream. Returns a handle, or 0 when the phone would only give a shared
// (mixed) stream, in which case direct output is not possible right now.
JNIEXPORT jlong JNICALL
Java_com_hassasin_music_PhoneDirectNative_open(JNIEnv*, jclass, jint bufferMs, jboolean allowShared) {
    // Android 16+ lets an app see and choose the MMAP policy (the phone maker may default to "never").
    if (void* lib = dlopen("libaaudio.so", RTLD_NOW)) {
        typedef int (*PolicyFor)(int, int);
        typedef int (*SetPolicy)(int);
        typedef int (*GetPolicy)();
        auto platform = (PolicyFor) dlsym(lib, "AAudio_getPlatformMMapPolicy");
        auto platformExcl = (PolicyFor) dlsym(lib, "AAudio_getPlatformMMapExclusivePolicy");
        auto setPolicy = (SetPolicy) dlsym(lib, "AAudio_setMMapPolicy");
        auto getPolicy = (GetPolicy) dlsym(lib, "AAudio_getMMapPolicy");
        // device 2 = built-in speaker, 4 = wired headphones; direction 0 = output
        if (platform) LOGI("platform MMAP policy: speaker %d, wired %d", platform(2, 0), platform(4, 0));
        if (platformExcl) LOGI("platform exclusive MMAP policy: speaker %d, wired %d", platformExcl(2, 0), platformExcl(4, 0));
        // AUTO: MMAP when the phone allows it, else Android's normal path (ALWAYS fails outright
        // on phones that block MMAP for apps, like the vivo tested).
        if (setPolicy) setPolicy(2 /* AAUDIO_POLICY_AUTO */);
        if (getPolicy) LOGI("MMAP policy now %d", getPolicy());
        if (!platform && !setPolicy) LOGI("this Android has no MMAP policy calls");
    }
    auto* e = new Engine();
    // Try the formats and settings phones accept for exclusive (MMAP) streams; keep the first
    // stream that really is exclusive.
    const aaudio_format_t formats[] = {AAUDIO_FORMAT_PCM_FLOAT, AAUDIO_FORMAT_PCM_I16, AAUDIO_FORMAT_PCM_I32, AAUDIO_FORMAT_PCM_I24_PACKED};
    aaudio_result_t res = AAUDIO_ERROR_INTERNAL;
    for (int withUsage = 1; withUsage >= 0 && !e->exclusive; withUsage--) {
        for (aaudio_format_t f : formats) {
            AAudioStreamBuilder* b = nullptr;
            if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) break;
            AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
            AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            // Positional stereo (an index mask keeps some phones off the MMAP path).
            {
                static void* lib = dlopen("libaaudio.so", RTLD_NOW);
                typedef void (*SetMask)(AAudioStreamBuilder*, uint32_t);
                typedef void (*SetPkg)(AAudioStreamBuilder*, const char*);
                static auto setMask = lib ? (SetMask) dlsym(lib, "AAudioStreamBuilder_setChannelMask") : nullptr;
                static auto setPkg = lib ? (SetPkg) dlsym(lib, "AAudioStreamBuilder_setPackageName") : nullptr;
                if (setMask) setMask(b, 0x3 /* AAUDIO_CHANNEL_STEREO */); else AAudioStreamBuilder_setChannelCount(b, 2);
                if (setPkg) setPkg(b, "com.hassasin.music");
            }
            AAudioStreamBuilder_setSampleRate(b, 48000);
            AAudioStreamBuilder_setFormat(b, f);
            if (withUsage) {
                AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
                AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
            }
            AAudioStreamBuilder_setDataCallback(b, onData, e);
            AAudioStreamBuilder_setErrorCallback(b, onError, e);
            AAudioStream* st = nullptr;
            res = AAudioStreamBuilder_openStream(b, &st);
            AAudioStreamBuilder_delete(b);
            if (res != AAUDIO_OK || st == nullptr) { LOGI("format %d usage %d: open failed %s", f, withUsage, AAudio_convertResultToText(res)); continue; }
            bool excl = AAudioStream_getSharingMode(st) == AAUDIO_SHARING_MODE_EXCLUSIVE;
            LOGI("format %d usage %d: %s", f, withUsage, excl ? "EXCLUSIVE" : "shared");
            if (excl) { e->stream = st; e->exclusive = true; break; }
            if (allowShared && e->stream == nullptr) { e->stream = st; continue; }
            AAudioStream_close(st);
        }
    }
    if (e->stream == nullptr) { LOGW("no stream"); delete e; return 0; }
    e->exclusive = AAudioStream_getSharingMode(e->stream) == AAUDIO_SHARING_MODE_EXCLUSIVE;
    e->hwRate = AAudioStream_getSampleRate(e->stream);
    e->format = AAudioStream_getFormat(e->stream);
    LOGI("stream: %s, %d Hz, format %d, burst %d, buffer %d",
         e->exclusive ? "EXCLUSIVE" : "SHARED", e->hwRate, e->format,
         AAudioStream_getFramesPerBurst(e->stream), AAudioStream_getBufferCapacityInFrames(e->stream));
    if (!e->exclusive && !allowShared) {
        AAudioStream_close(e->stream);
        delete e;
        return 0;
    }
    // Use the whole hardware buffer: fewer wake-ups, no glitches when the phone is busy.
    AAudioStream_setBufferSizeInFrames(e->stream, AAudioStream_getBufferCapacityInFrames(e->stream));
    e->capacity = (size_t) e->hwRate * bufferMs / 1000;
    e->ring.assign(e->capacity * 2, 0);
    e->rs.setup(e->inRate, e->hwRate);
    if (AAudioStream_requestStart(e->stream) != AAUDIO_OK) { AAudioStream_close(e->stream); delete e; return 0; }
    e->running = true;
    return (jlong) e;
}

JNIEXPORT jint JNICALL
Java_com_hassasin_music_PhoneDirectNative_hwRate(JNIEnv*, jclass, jlong h) { return ((Engine*) h)->hwRate; }

JNIEXPORT jint JNICALL
Java_com_hassasin_music_PhoneDirectNative_hwBits(JNIEnv*, jclass, jlong h) {
    int f = ((Engine*) h)->format;
    return f == AAUDIO_FORMAT_PCM_I32 ? 32 : f == AAUDIO_FORMAT_PCM_I16 ? 16 : 24;
}

JNIEXPORT jboolean JNICALL
Java_com_hassasin_music_PhoneDirectNative_exclusive(JNIEnv*, jclass, jlong h) { return ((Engine*) h)->exclusive; }

// The song's rate: the converter is set up for it (and cleared).
JNIEXPORT void JNICALL
Java_com_hassasin_music_PhoneDirectNative_setInputRate(JNIEnv*, jclass, jlong h, jint rate) {
    auto* e = (Engine*) h;
    std::lock_guard<std::mutex> g(e->writeLock);
    e->inRate = rate;
    e->rs.setup(rate, e->hwRate);
    e->played = 0;
}

// Takes 32-bit stereo frames at the song's rate; returns the bytes accepted.
JNIEXPORT jint JNICALL
Java_com_hassasin_music_PhoneDirectNative_write(JNIEnv* env, jclass, jlong h, jbyteArray data, jint offset, jint length) {
    auto* e = (Engine*) h;
    if (!e->running) return -1;
    std::lock_guard<std::mutex> g(e->writeLock);
    uint64_t w = e->writePos.load(std::memory_order_acquire);
    uint64_t r = e->readPos.load(std::memory_order_acquire);
    size_t space = e->capacity - (size_t) (w - r);
    int frames = length / 8;
    jbyte* src = env->GetByteArrayElements(data, nullptr);
    auto* in = (const int32_t*) (src + offset);
    int accepted = 0;
    auto put = [&](const int32_t* f, size_t n) {
        for (size_t i = 0; i < n; i++) {
            size_t idx = (size_t) ((w + i) % e->capacity) * 2;
            e->ring[idx] = f[2 * i];
            e->ring[idx + 1] = f[2 * i + 1];
        }
        w += n;
    };
    if (e->inRate == e->hwRate) {
        size_t n = std::min<size_t>(space, (size_t) frames);
        put(in, n);
        accepted = (int) n;
    } else {
        // Keep at most about 0.2 s waiting in the converter.
        size_t room = (size_t) e->inRate / 5;
        size_t queued = e->rs.historyFrames();
        size_t take = queued >= room ? 0 : std::min<size_t>(room - queued, (size_t) frames);
        e->rs.push(in, take);
        accepted = (int) take;
        if (e->scratch.size() < space * 2) e->scratch.resize(space * 2);
        size_t made = e->rs.pull(e->scratch.data(), space);
        put(e->scratch.data(), made);
    }
    env->ReleaseByteArrayElements(data, src, JNI_ABORT);
    e->writePos.store(w, std::memory_order_release);
    return accepted * 8;
}

// Frames waiting (converter + ring), counted at the song's rate.
JNIEXPORT jlong JNICALL
Java_com_hassasin_music_PhoneDirectNative_queuedFrames(JNIEnv*, jclass, jlong h) {
    auto* e = (Engine*) h;
    uint64_t ring = e->writePos.load() - e->readPos.load();
    double ratio = (double) e->inRate / e->hwRate;
    long waiting = e->inRate == e->hwRate ? 0 : std::max(0L, (long) e->rs.historyFrames() - (long) e->rs.pos);
    return (jlong) (ring * ratio) + waiting;
}

// Frames heard so far, counted at the song's rate.
JNIEXPORT jlong JNICALL
Java_com_hassasin_music_PhoneDirectNative_framesPlayed(JNIEnv*, jclass, jlong h) {
    auto* e = (Engine*) h;
    return (jlong) (e->played.load() * (double) e->inRate / e->hwRate);
}

JNIEXPORT jlong JNICALL
Java_com_hassasin_music_PhoneDirectNative_underruns(JNIEnv*, jclass, jlong h) { return ((Engine*) h)->underruns.load(); }

JNIEXPORT jboolean JNICALL
Java_com_hassasin_music_PhoneDirectNative_isRunning(JNIEnv*, jclass, jlong h) { return ((Engine*) h)->running.load(); }

JNIEXPORT void JNICALL
Java_com_hassasin_music_PhoneDirectNative_setPaused(JNIEnv*, jclass, jlong h, jboolean p) { ((Engine*) h)->paused = p; }

JNIEXPORT void JNICALL
Java_com_hassasin_music_PhoneDirectNative_flush(JNIEnv*, jclass, jlong h) {
    auto* e = (Engine*) h;
    std::lock_guard<std::mutex> g(e->writeLock);
    e->paused = true;
    e->readPos.store(e->writePos.load());
    e->played = 0;
    e->rs.setup(e->inRate, e->hwRate);
}

JNIEXPORT void JNICALL
Java_com_hassasin_music_PhoneDirectNative_close(JNIEnv*, jclass, jlong h) {
    auto* e = (Engine*) h;
    e->running = false;
    if (e->stream) { AAudioStream_requestStop(e->stream); AAudioStream_close(e->stream); }
    delete e;
}

}  // extern "C"
