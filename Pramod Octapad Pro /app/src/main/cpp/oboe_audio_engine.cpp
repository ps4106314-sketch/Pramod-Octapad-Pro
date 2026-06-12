#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <memory>
#include <vector>
#include <cstring>
#include <cmath>
#include <mutex>
#include <thread>

#define LOG_TAG "OctapadEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace oboe;

static void restartAudioEngine();

static constexpr int PAD_COUNT = 16;
static constexpr int MAX_STREAMS_PER_PAD = 8; // Increased for delay streams
static constexpr int MAX_SAMPLE_SIZE = 960000; // ~10 seconds at 96kHz
static constexpr float PI = 3.14159265358979323846f;

struct Biquad {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
    float a1 = 0.0f, a2 = 0.0f;
    float x1 = 0.0f, x2 = 0.0f;
    float y1 = 0.0f, y2 = 0.0f;

    void setLowShelf(float Fs, float f0, float dBgain) {
        float A = std::pow(10.0f, dBgain / 40.0f);
        float w0 = 2.0f * PI * f0 / Fs;
        float alpha = std::sin(w0) / 2.0f; // Q = 1
        float a0 = (A+1.0f) + (A-1.0f)*std::cos(w0) + 2.0f*std::sqrt(A)*alpha;
        b0 = (A*( (A+1.0f) - (A-1.0f)*std::cos(w0) + 2.0f*std::sqrt(A)*alpha )) / a0;
        b1 = (2.0f*A*( (A-1.0f) - (A+1.0f)*std::cos(w0) )) / a0;
        b2 = (A*( (A+1.0f) - (A-1.0f)*std::cos(w0) - 2.0f*std::sqrt(A)*alpha )) / a0;
        a1 = (-2.0f*( (A-1.0f) + (A+1.0f)*std::cos(w0) )) / a0;
        a2 = ((A+1.0f) + (A-1.0f)*std::cos(w0) - 2.0f*std::sqrt(A)*alpha) / a0;
    }

    void setPeaking(float Fs, float f0, float Q, float dBgain) {
        float A = std::pow(10.0f, dBgain / 40.0f);
        float w0 = 2.0f * PI * f0 / Fs;
        float alpha = std::sin(w0) / (2.0f * Q);
        float a0 = 1.0f + alpha / A;
        b0 = (1.0f + alpha * A) / a0;
        b1 = (-2.0f * std::cos(w0)) / a0;
        b2 = (1.0f - alpha * A) / a0;
        a1 = (-2.0f * std::cos(w0)) / a0;
        a2 = (1.0f - alpha / A) / a0;
    }

    void setHighShelf(float Fs, float f0, float dBgain) {
        float A = std::pow(10.0f, dBgain / 40.0f);
        float w0 = 2.0f * PI * f0 / Fs;
        float alpha = std::sin(w0) / 2.0f; // Q = 1
        float a0 = (A+1.0f) - (A-1.0f)*std::cos(w0) + 2.0f*std::sqrt(A)*alpha;
        b0 = (A*( (A+1.0f) + (A-1.0f)*std::cos(w0) + 2.0f*std::sqrt(A)*alpha )) / a0;
        b1 = (-2.0f*A*( (A-1.0f) + (A+1.0f)*std::cos(w0) )) / a0;
        b2 = (A*( (A+1.0f) + (A-1.0f)*std::cos(w0) - 2.0f*std::sqrt(A)*alpha )) / a0;
        a1 = (2.0f*( (A-1.0f) - (A+1.0f)*std::cos(w0) )) / a0;
        a2 = ((A+1.0f) - (A-1.0f)*std::cos(w0) - 2.0f*std::sqrt(A)*alpha) / a0;
    }

    float process(float in) {
        float out = b0*in + b1*x1 + b2*x2 - a1*y1 - a2*y2;
        x2 = x1; x1 = in;
        y2 = y1; y1 = out;
        return out;
    }
};

struct AudioSample {
    std::vector<int16_t> data;
    int32_t frameCount = 0;
    float volume = 1.0f;
    float pitch = 1.0f;
    bool loaded = false;
};

struct PlaybackStream {
    int32_t padIndex = -1;
    int32_t sampleIndex = -1;
    float playbackPosition = 0.0f;
    float volume = 1.0f;
    float pitch = 1.0f;
    bool active = false;
    Biquad eqLow, eqMid, eqHigh;
    bool useEq = false;
    int32_t chokeGroup = 0;
    float attackFrames = 0.0f;
    float releaseFrames = 0.0f;
    bool isChoked = false;
    float chokeVolMult = 1.0f;
};

class OctapadAudioCallback : public AudioStreamCallback {
private:
    AudioSample samples[PAD_COUNT];
    PlaybackStream streams[PAD_COUNT * MAX_STREAMS_PER_PAD];
    int32_t sampleRate = 48000;
    
public:
    OctapadAudioCallback() {
        for (int i = 0; i < PAD_COUNT; i++) {
            samples[i].loaded = false;
        }
        for (int i = 0; i < PAD_COUNT * MAX_STREAMS_PER_PAD; i++) {
            streams[i].active = false;
        }
    }
    
    void setSampleRate(int32_t rate) {
        sampleRate = rate;
    }
    
    DataCallbackResult onAudioReady(AudioStream *audioStream, void *audioData, int32_t numFrames) override {
        int16_t *outputBuffer = static_cast<int16_t *>(audioData);
        int32_t channelCount = audioStream->getChannelCount();
        int32_t totalSamples = numFrames * channelCount;

        std::fill(outputBuffer, outputBuffer + totalSamples, 0);
        
        for (int streamIdx = 0; streamIdx < PAD_COUNT * MAX_STREAMS_PER_PAD; streamIdx++) {
            PlaybackStream &stream = streams[streamIdx];
            
            if (!stream.active) continue;
            
            AudioSample &sample = samples[stream.sampleIndex];
            if (!sample.loaded || sample.frameCount == 0) {
                stream.active = false;
                continue;
            }
            
            int32_t outIndex = 0;
            float playPos = stream.playbackPosition;
            float pitchRate = stream.pitch;
            float volume = stream.volume;
            
            while (outIndex < numFrames) {
                int32_t intPos = static_cast<int32_t>(playPos);
                
                if (intPos >= sample.frameCount) {
                    stream.active = false;
                    break;
                }
                
                if (intPos < 0) {
                    // Delay padding
                    playPos += pitchRate;
                    outIndex++;
                    continue;
                }
                
                float frac = playPos - intPos;
                int32_t nextPos = intPos + 1;
                if (nextPos >= sample.frameCount) nextPos = sample.frameCount - 1;
                
                float sample1 = sample.data[intPos] / 32768.0f;
                float sample2 = sample.data[nextPos] / 32768.0f;
                float interpolated = sample1 * (1.0f - frac) + sample2 * frac;
                
                if (stream.useEq) {
                    interpolated = stream.eqLow.process(interpolated);
                    interpolated = stream.eqMid.process(interpolated);
                    interpolated = stream.eqHigh.process(interpolated);
                }
                
                // Envelope (Fade In / Fade Out)
                float envMult = 1.0f;
                if (stream.attackFrames > 0 && playPos < stream.attackFrames) {
                    envMult = playPos / stream.attackFrames;
                } else if (stream.releaseFrames > 0 && playPos > (sample.frameCount - stream.releaseFrames)) {
                    envMult = (sample.frameCount - playPos) / stream.releaseFrames;
                    if (envMult < 0) envMult = 0.0f;
                }
                
                // Fast Choke fade-out (prevent clicks)
                if (stream.isChoked) {
                    stream.chokeVolMult -= 0.005f; // approx 200 samples fade out (~4ms)
                    if (stream.chokeVolMult <= 0.0f) {
                        stream.active = false;
                        break;
                    }
                    envMult *= stream.chokeVolMult;
                }
                
                float mixed = interpolated * volume * envMult;
                mixed = std::max(-1.0f, std::min(1.0f, mixed));
                int16_t mixedSample = static_cast<int16_t>(mixed * 32767.0f);
                
                int32_t writeIndex = outIndex * channelCount;
                if (channelCount == 1) {
                    int32_t accum = static_cast<int32_t>(outputBuffer[writeIndex]) + mixedSample;
                    outputBuffer[writeIndex] = static_cast<int16_t>(std::max(-32768, std::min(32767, accum)));
                } else {
                    int32_t accumL = static_cast<int32_t>(outputBuffer[writeIndex]) + mixedSample;
                    int32_t accumR = static_cast<int32_t>(outputBuffer[writeIndex + 1]) + mixedSample;
                    outputBuffer[writeIndex] = static_cast<int16_t>(std::max(-32768, std::min(32767, accumL)));
                    outputBuffer[writeIndex + 1] = static_cast<int16_t>(std::max(-32768, std::min(32767, accumR)));
                }
                
                playPos += pitchRate;
                outIndex++;
            }
            
            stream.playbackPosition = playPos;
        }
        
        return DataCallbackResult::Continue;
    }
    
    void onErrorAfterClose(AudioStream *audioStream, Result error) override {
        LOGI("Audio stream error/disconnected. Restarting engine in a new thread...");
        std::thread restartThread(restartAudioEngine);
        restartThread.detach();
    }
    
    void loadSample(int padIndex, const int16_t *audioData, int32_t frameCount) {
        if (padIndex < 0 || padIndex >= PAD_COUNT) return;
        if (frameCount > MAX_SAMPLE_SIZE) return;
        
        samples[padIndex].data.resize(frameCount);
        std::copy(audioData, audioData + frameCount, samples[padIndex].data.begin());
        samples[padIndex].frameCount = frameCount;
        samples[padIndex].loaded = true;
    }
    
    void playSample(int padIndex, float volume, float pitch, bool delayOn, float delayMs, float delayLevel, float eqLow, float eqMid, float eqHigh, int chokeGroup, float attackMs, float releaseMs) {
        if (padIndex < 0 || padIndex >= PAD_COUNT) return;
        if (!samples[padIndex].loaded) return;
        
        bool hasEq = (std::abs(eqLow) > 0.1f || std::abs(eqMid) > 0.1f || std::abs(eqHigh) > 0.1f);
        float attFrames = (attackMs > 0) ? (attackMs * sampleRate / 1000.0f) : 0;
        float relFrames = (releaseMs > 0) ? (releaseMs * sampleRate / 1000.0f) : 0;
        
        // Choke other streams in the same group (if group > 0)
        if (chokeGroup > 0) {
            for (int i = 0; i < PAD_COUNT * MAX_STREAMS_PER_PAD; i++) {
                if (streams[i].active && streams[i].chokeGroup == chokeGroup) {
                    streams[i].isChoked = true;
                }
            }
        }
        
        // Find streams
        PlaybackStream* mainStream = nullptr;
        PlaybackStream* fxStream = nullptr;
        
        for (int i = 0; i < PAD_COUNT * MAX_STREAMS_PER_PAD; i++) {
            if (!streams[i].active) {
                if (!mainStream) {
                    mainStream = &streams[i];
                } else if (delayOn && !fxStream) {
                    fxStream = &streams[i];
                    break; // Found both
                } else if (!delayOn) {
                    break; // Only need main
                }
            }
        }
        
        if (mainStream) {
            mainStream->padIndex = padIndex;
            mainStream->sampleIndex = padIndex;
            mainStream->playbackPosition = 0.0f;
            mainStream->volume = std::max(0.0f, std::min(1.0f, volume));
            mainStream->pitch = std::max(0.5f, std::min(2.0f, pitch));
            mainStream->useEq = hasEq;
            mainStream->chokeGroup = chokeGroup;
            mainStream->attackFrames = attFrames;
            mainStream->releaseFrames = relFrames;
            mainStream->isChoked = false;
            mainStream->chokeVolMult = 1.0f;
            if (hasEq) {
                mainStream->eqLow.setLowShelf(sampleRate, 200.0f, eqLow);
                mainStream->eqMid.setPeaking(sampleRate, 1000.0f, 0.7f, eqMid);
                mainStream->eqHigh.setHighShelf(sampleRate, 5000.0f, eqHigh);
            }
            mainStream->active = true;
        }
        
        if (delayOn && fxStream) {
            fxStream->padIndex = padIndex;
            fxStream->sampleIndex = padIndex;
            fxStream->playbackPosition = -(delayMs * sampleRate / 1000.0f) * pitch;
            fxStream->volume = std::max(0.0f, std::min(1.0f, volume * delayLevel));
            fxStream->pitch = std::max(0.5f, std::min(2.0f, pitch));
            fxStream->useEq = hasEq;
            fxStream->chokeGroup = chokeGroup;
            fxStream->attackFrames = attFrames;
            fxStream->releaseFrames = relFrames;
            fxStream->isChoked = false;
            fxStream->chokeVolMult = 1.0f;
            if (hasEq) {
                fxStream->eqLow.setLowShelf(sampleRate, 200.0f, eqLow);
                fxStream->eqMid.setPeaking(sampleRate, 1000.0f, 0.7f, eqMid);
                fxStream->eqHigh.setHighShelf(sampleRate, 5000.0f, eqHigh);
            }
            fxStream->active = true;
        }
    }
    
    void stopPad(int padIndex) {
        for (int i = 0; i < PAD_COUNT * MAX_STREAMS_PER_PAD; i++) {
            if (streams[i].padIndex == padIndex) {
                streams[i].active = false;
            }
        }
    }
    
    void stopAll() {
        for (int i = 0; i < PAD_COUNT * MAX_STREAMS_PER_PAD; i++) {
            streams[i].active = false;
        }
    }
};

static std::shared_ptr<AudioStream> audioStream;
static std::unique_ptr<OctapadAudioCallback> audioCallback;
static std::mutex restartMutex;

static void restartAudioEngine() {
    std::lock_guard<std::mutex> lock(restartMutex);
    LOGI("Restarting audio engine...");
    if (audioStream) {
        audioStream->stop();
        audioStream->close();
    }
    
    AudioStreamBuilder builder;
    builder.setChannelCount(2)
           ->setFormat(AudioFormat::I16)
           ->setCallback(audioCallback.get())
           ->setPerformanceMode(PerformanceMode::LowLatency)
           ->setSharingMode(SharingMode::Shared);
    
    auto result = builder.openStream(audioStream);
    if (result != Result::OK) {
        LOGE("Failed to restart audio stream: %s", convertToText(result));
        return;
    }
    
    if (audioCallback) {
        audioCallback->setSampleRate(audioStream->getSampleRate());
    }
    audioStream->start();
    LOGI("Audio engine restarted successfully");
}

extern "C" {
    JNIEXPORT jlong JNICALL Java_com_pramod_octapadprofast_AudioEngine_nativeCreateAudioEngine(
            JNIEnv *env, jobject instance) {
        try {
            audioCallback = std::make_unique<OctapadAudioCallback>();
            
            AudioStreamBuilder builder;
            builder.setChannelCount(2)
                   ->setFormat(AudioFormat::I16)
                   ->setCallback(audioCallback.get())
                   ->setPerformanceMode(PerformanceMode::LowLatency)
                   ->setSharingMode(SharingMode::Shared)
                   ->setUsage(Usage::Game) // Helps with fast response
                   ->setContentType(ContentType::Sonification);
            
            auto result = builder.openStream(audioStream);
            if (result != Result::OK) {
                LOGE("Failed to open audio stream: %s", convertToText(result));
                return 0;
            }
            
            // Optimize buffer size for ultra-low latency
            audioStream->setBufferSizeInFrames(audioStream->getFramesPerBurst() * 2);
            
            audioCallback->setSampleRate(audioStream->getSampleRate());
            audioStream->start();
            LOGI("Audio engine created successfully");
            return reinterpret_cast<jlong>(audioStream.get());
        } catch (const std::exception &e) {
            LOGE("Exception: %s", e.what());
            return 0;
        }
    }
    
    JNIEXPORT void JNICALL Java_com_pramod_octapadprofast_AudioEngine_nativeLoadSample(
            JNIEnv *env, jobject instance, jint padIndex, jshortArray audioData, jint frameCount) {
        if (!audioCallback) return;
        jshort *data = env->GetShortArrayElements(audioData, nullptr);
        audioCallback->loadSample(padIndex, data, frameCount);
        env->ReleaseShortArrayElements(audioData, data, JNI_ABORT);
    }
    
    JNIEXPORT void JNICALL Java_com_pramod_octapadprofast_AudioEngine_nativePlaySample(
            JNIEnv *env, jobject instance, jint padIndex, jfloat volume, jfloat pitch, 
            jboolean delayOn, jfloat delayMs, jfloat delayLevel, 
            jfloat eqLow, jfloat eqMid, jfloat eqHigh,
            jint chokeGroup, jfloat attackMs, jfloat releaseMs) {
        if (!audioCallback) return;
        audioCallback->playSample(padIndex, volume, pitch, delayOn, delayMs, delayLevel, eqLow, eqMid, eqHigh, chokeGroup, attackMs, releaseMs);
    }
    
    JNIEXPORT void JNICALL Java_com_pramod_octapadprofast_AudioEngine_nativeStopPad(
            JNIEnv *env, jobject instance, jint padIndex) {
        if (!audioCallback) return;
        audioCallback->stopPad(padIndex);
    }
    
    JNIEXPORT void JNICALL Java_com_pramod_octapadprofast_AudioEngine_nativeStopAll(
            JNIEnv *env, jobject instance) {
        if (!audioCallback) return;
        audioCallback->stopAll();
    }
    
    JNIEXPORT void JNICALL Java_com_pramod_octapadprofast_AudioEngine_nativeDestroyAudioEngine(
            JNIEnv *env, jobject instance) {
        if (audioStream) {
            audioStream->stop();
            audioStream->close();
        }
        audioCallback = nullptr;
    }
}
