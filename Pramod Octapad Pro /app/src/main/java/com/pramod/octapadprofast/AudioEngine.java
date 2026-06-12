package com.pramod.octapadpromidi;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.net.Uri;
import android.util.Log;
import java.io.InputStream;

public class AudioEngine {

    private static final String TAG = "AudioEngine";
    private static final int PAD_COUNT = 16;
    private long nativeHandle = 0;
    private Context context;
    private byte[] waveCache;

    static {
        try {
            System.loadLibrary("oboe_audio_engine");
            Log.i(TAG, "Oboe audio engine library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load Oboe audio engine library", e);
        }
    }

    public static class SampleData {
        public int soundId = 0;
        public Uri uri;
        public boolean loaded = false;
    }

    public AudioEngine(Context ctx) {
        context = ctx;
        nativeHandle = nativeCreateAudioEngine();
        if (nativeHandle != 0) {
            Log.i(TAG, "Audio engine initialized with native Oboe");
        } else {
            Log.e(TAG, "Failed to initialize audio engine");
        }
    }

    public void start() {
        // Already started in nativeCreateAudioEngine
    }

    public void stop() {
        if (nativeHandle != 0) {
            nativeDestroyAudioEngine();
            nativeHandle = 0;
        }
    }

    // ⚡ Load WAV from URI using Oboe
    public SampleData loadWavFromUri(int padIndex, Uri uri) {
        try {
            if (nativeHandle == 0) return null;
            if (padIndex < 0 || padIndex >= PAD_COUNT) {
                Log.e(TAG, "Invalid pad index: " + padIndex);
                return null;
            }

            AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd == null) return null;

            byte[] wavData = readAssetFileDescriptor(afd);
            afd.close();

            short[] pcmData = decodePcmFromWav(wavData);
            if (pcmData == null || pcmData.length == 0) {
                Log.e(TAG, "Failed to decode PCM from WAV");
                return null;
            }

            SampleData sd = new SampleData();
            sd.uri = uri;
            sd.soundId = padIndex;
            sd.loaded = true;

            // Load into native engine at correct pad index
            nativeLoadSample(padIndex, pcmData, pcmData.length);
            Log.i(TAG, "Loaded WAV sample to pad " + padIndex + ": " + pcmData.length + " frames");

            return sd;

        } catch (Exception e) {
            Log.e(TAG, "Error loading WAV from URI", e);
            return null;
        }
    }

    // ⚡ Load raw sound resource using Oboe
    public SampleData loadRawSound(int padIndex, int resId) {
        try {
            if (nativeHandle == 0) return null;
            if (padIndex < 0 || padIndex >= PAD_COUNT) {
                Log.e(TAG, "Invalid pad index: " + padIndex);
                return null;
            }

            InputStream is = context.getResources().openRawResource(resId);
            byte[] wavData = new byte[is.available()];
            is.read(wavData);
            is.close();

            short[] pcmData = decodePcmFromWav(wavData);
            if (pcmData == null || pcmData.length == 0) {
                Log.e(TAG, "Failed to decode PCM from raw resource");
                return null;
            }

            SampleData sd = new SampleData();
            sd.soundId = resId;
            sd.loaded = true;

            // Load into native engine at correct pad index
            nativeLoadSample(padIndex, pcmData, pcmData.length);
            Log.i(TAG, "Loaded raw sound to pad " + padIndex + ": " + pcmData.length + " frames");

            return sd;

        } catch (Exception e) {
            Log.e(TAG, "Error loading raw sound", e);
            return null;
        }
    }

    public void unloadSample(SampleData sample) {
        if (sample != null) {
            sample.soundId = 0;
            sample.loaded = false;
            sample.uri = null;
        }
    }

    public void preloadSample(SampleData sample) {
        // Not needed with Oboe - samples are preloaded automatically
    }

    // ⚡ ULTRA-FAST PLAY with Oboe (< 10ms latency)
    public void playSample(int padIndex, SampleData sample, float volume, float pitch, int loopMode, boolean delayOn, float delayMs, float delayLevel, float eqLow, float eqMid, float eqHigh, int chokeGroup, float attackMs, float releaseMs) {
        try {
            if (nativeHandle == 0 || sample == null || !sample.loaded) return;

            float vol = Math.max(0f, Math.min(1.0f, volume));
            float rate = Math.max(0.5f, Math.min(2.0f, pitch));

            nativePlaySample(padIndex, vol, rate, delayOn, delayMs, delayLevel, eqLow, eqMid, eqHigh, chokeGroup, attackMs, releaseMs);
        } catch (Exception e) {
            Log.e(TAG, "Error playing sample", e);
        }
    }

    // Overload for backward compatibility (in case it's used elsewhere)
    public void playSample(int padIndex, SampleData sample, float volume, float pitch, int loopMode) {
        playSample(padIndex, sample, volume, pitch, loopMode, false, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0, 0.0f, 0.0f);
    }

    public void stopPad(int padIndex) {
        if (nativeHandle != 0) {
            nativeStopPad(padIndex);
        }
    }

    public void stopAll() {
        if (nativeHandle != 0) {
            nativeStopAll();
        }
    }

    // Helper: Decode PCM data from WAV file
    private short[] decodePcmFromWav(byte[] wavData) {
        try {
            if (wavData.length < 44) {
                Log.e(TAG, "WAV file too small");
                return null;
            }

            if (!(wavData[0] == 'R' && wavData[1] == 'I' && wavData[2] == 'F' && wavData[3] == 'F')) {
                Log.e(TAG, "Not a valid WAV file");
                return null;
            }
            if (!(wavData[8] == 'W' && wavData[9] == 'A' && wavData[10] == 'V' && wavData[11] == 'E')) {
                Log.e(TAG, "Not a valid WAV file (missing WAVE header)");
                return null;
            }

            int offset = 12;
            int audioFormat = -1;
            int numChannels = 0;
            int bitsPerSample = 0;
            int dataOffset = -1;
            int dataSize = 0;

            while (offset + 8 <= wavData.length) {
                String chunkId = new String(wavData, offset, 4, "US-ASCII");
                int chunkSize = ((wavData[offset + 7] & 0xFF) << 24)
                              | ((wavData[offset + 6] & 0xFF) << 16)
                              | ((wavData[offset + 5] & 0xFF) << 8)
                              | (wavData[offset + 4] & 0xFF);
                int chunkDataStart = offset + 8;

                if (chunkDataStart + chunkSize > wavData.length) {
                    Log.e(TAG, "Invalid WAV chunk size: " + chunkId);
                    return null;
                }

                if (chunkId.equals("fmt ")) {
                    if (chunkSize < 16) {
                        Log.e(TAG, "Unsupported WAV fmt chunk size: " + chunkSize);
                        return null;
                    }
                    audioFormat = ((wavData[chunkDataStart + 1] & 0xFF) << 8) | (wavData[chunkDataStart] & 0xFF);
                    numChannels = ((wavData[chunkDataStart + 3] & 0xFF) << 8) | (wavData[chunkDataStart + 2] & 0xFF);
                    bitsPerSample = ((wavData[chunkDataStart + 15] & 0xFF) << 8) | (wavData[chunkDataStart + 14] & 0xFF);
                } else if (chunkId.equals("data")) {
                    dataOffset = chunkDataStart;
                    dataSize = chunkSize;
                    break;
                }

                offset = chunkDataStart + chunkSize;
                if ((chunkSize & 1) != 0) {
                    offset++;
                }
            }

            if (audioFormat != 1) {
                Log.e(TAG, "Unsupported WAV format: " + audioFormat);
                return null;
            }
            if (bitsPerSample != 16) {
                Log.e(TAG, "Only 16-bit WAV is supported, found: " + bitsPerSample);
                return null;
            }
            if (numChannels <= 0) {
                Log.e(TAG, "Invalid channel count: " + numChannels);
                return null;
            }
            if (dataOffset < 0 || dataSize <= 0) {
                Log.e(TAG, "WAV data chunk not found");
                return null;
            }

            int sampleCount = dataSize / (2 * numChannels);
            short[] pcmData = new short[sampleCount];
            int dataPos = dataOffset;

            for (int i = 0; i < sampleCount; i++) {
                int sum = 0;
                for (int channel = 0; channel < numChannels; channel++) {
                    int sample = (wavData[dataPos] & 0xFF) | ((wavData[dataPos + 1] & 0xFF) << 8);
                    if (sample > 32767) sample -= 65536;
                    sum += sample;
                    dataPos += 2;
                }
                pcmData[i] = (short) (sum / numChannels);
            }

            return pcmData;
        } catch (Exception e) {
            Log.e(TAG, "Error decoding WAV", e);
            return null;
        }
    }

    private byte[] readAssetFileDescriptor(AssetFileDescriptor afd) throws Exception {
        byte[] data = new byte[(int) afd.getLength()];
        InputStream is = afd.createInputStream();
        is.read(data);
        is.close();
        return data;
    }

    // Native JNI methods
    private native long nativeCreateAudioEngine();
    private native void nativeLoadSample(int padIndex, short[] audioData, int frameCount);
    private native void nativePlaySample(int padIndex, float volume, float pitch, boolean delayOn, float delayMs, float delayLevel, float eqLow, float eqMid, float eqHigh, int chokeGroup, float attackMs, float releaseMs);
    private native void nativeStopPad(int padIndex);
    private native void nativeStopAll();
    private native void nativeDestroyAudioEngine();
}

