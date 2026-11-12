/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.rtlsdr.dsp;

import org.sensorhub.impl.sensor.rtlsdr.RtlSdrConfig.AudioDemodMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real-time software demodulator for RTL-SDR raw I/Q baseband streams.
 * Supports AM (Airband/HF), NFM (Ham/VHF/UHF/Marine), and WFM (Broadcast FM).
 * Outputs 48 kHz 16-bit mono PCM audio suitable for HTML5 Web Audio and OSH streams.
 */
public class RtlSdrAudioDemodulator {
    private static final Logger log = LoggerFactory.getLogger(RtlSdrAudioDemodulator.class);

    public static final int AUDIO_SAMPLE_RATE = 48000;
    private static final int CHUNK_SAMPLES = 2400; // 50 ms of audio per push chunk at 48 kHz

    public interface AudioFrameListener {
        void onAudioChunk(byte[] pcm16Data, int numSamples, String base64Pcm);
    }

    private final List<AudioFrameListener> listeners = new CopyOnWriteArrayList<>();

    // State
    private volatile AudioDemodMode mode = AudioDemodMode.OFF;
    private volatile float volume = 0.8f;
    private volatile float squelchThresholdDb = -65.0f;

    // Static Hilbert Transform FIR filter (31 taps, Blackman window, odd-symmetric)
    private static final int HILBERT_LENGTH = 31;
    private static final int HILBERT_DELAY = 15;
    private static final float[] HILBERT_TAPS = new float[HILBERT_LENGTH];

    static {
        for (int k = 0; k < HILBERT_LENGTH; k++) {
            int d = k - HILBERT_DELAY;
            if (d != 0 && (d % 2 != 0)) {
                float w = (float) (0.42 - 0.5 * Math.cos(2.0 * Math.PI * k / (HILBERT_LENGTH - 1))
                        + 0.08 * Math.cos(4.0 * Math.PI * k / (HILBERT_LENGTH - 1)));
                HILBERT_TAPS[k] = (float) (2.0 / (Math.PI * d)) * w;
            } else {
                HILBERT_TAPS[k] = 0.0f;
            }
        }
    }

    // Filter states
    private float lastI = 0.0f;
    private float lastQ = 0.0f;
    private float deemphState = 0.0f;
    private float dcBlockStateX = 0.0f;
    private float dcBlockStateY = 0.0f;
    private float amAgcPeak = 0.01f;

    // SSB (USB/LSB) filter states
    private final float[] ssbHistI = new float[32];
    private final float[] ssbHistQ = new float[32];
    private int ssbHistPos = 0;
    private float ssbAgcPeak = 0.01f;
    private float ssbLpState = 0.0f;
    private float ssbHpStateX = 0.0f;
    private float ssbHpStateY = 0.0f;

    // CW filter states
    private volatile float cwBfoPitchHz = 700.0f;
    private float cwPhase = 0.0f;
    private float cwBiquadW1 = 0.0f;
    private float cwBiquadW2 = 0.0f;
    private float cwAgcPeak = 0.01f;
    private float cwA1 = -1.9735f;
    private float cwA2 = 0.9818f;
    private float cwB0 = 0.0091f;

    // Buffer accumulation for 48 kHz audio
    private final byte[] chunkBuffer = new byte[CHUNK_SAMPLES * 2];
    private int chunkBufferPos = 0;

    public void addListener(AudioFrameListener listener) {
        listeners.add(listener);
    }

    public void removeListener(AudioFrameListener listener) {
        listeners.remove(listener);
    }

    public AudioDemodMode getMode() {
        return mode;
    }

    public void setMode(AudioDemodMode mode) {
        this.mode = mode != null ? mode : AudioDemodMode.OFF;
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public float getSquelchThresholdDb() {
        return squelchThresholdDb;
    }

    public void setSquelchThresholdDb(float squelchThresholdDb) {
        this.squelchThresholdDb = squelchThresholdDb;
    }

    public float getCwBfoPitchHz() {
        return cwBfoPitchHz;
    }

    public void setCwBfoPitchHz(float pitchHz) {
        this.cwBfoPitchHz = Math.max(200.0f, Math.min(2000.0f, pitchHz));
        updateCwFilter();
    }

    private synchronized void updateCwFilter() {
        double omega = 2.0 * Math.PI * cwBfoPitchHz / AUDIO_SAMPLE_RATE;
        double sinW = Math.sin(omega);
        double cosW = Math.cos(omega);
        double alpha = sinW / (2.0 * 5.0); // Q = 5 bandpass
        double a0 = 1.0 + alpha;
        cwA1 = (float) (-2.0 * cosW / a0);
        cwA2 = (float) ((1.0 - alpha) / a0);
        cwB0 = (float) (alpha / a0);
    }

    /**
     * Process incoming interleaved I/Q samples from the RTL-SDR dongle.
     *
     * @param buffer   Interleaved 8-bit unsigned IQ bytes
     * @param len      Length of raw buffer
     * @param inRateHz Input sample rate in Hz (e.g. 2,048,000)
     */
    public void processSamples(byte[] buffer, int len, int inRateHz) {
        if (mode == AudioDemodMode.OFF || len <= 0 || inRateHz <= 0) {
            return;
        }

        switch (mode) {
            case WFM:
                demodulateWfm(buffer, len, inRateHz);
                break;
            case NFM:
                demodulateNfm(buffer, len, inRateHz);
                break;
            case AM:
                demodulateAm(buffer, len, inRateHz);
                break;
            case USB:
                demodulateSsb(buffer, len, inRateHz, true);
                break;
            case LSB:
                demodulateSsb(buffer, len, inRateHz, false);
                break;
            case CW:
                demodulateCw(buffer, len, inRateHz);
                break;
            default:
                break;
        }
    }

    /**
     * Wideband FM Demodulator (Broadcast Radio ~150-200 kHz bandwidth).
     * 1. Decimates RF from inRate to an intermediate rate (~240-256 kSPS).
     * 2. Runs quadrature frequency discriminator.
     * 3. Decimates audio to 48 kSPS with 75 us de-emphasis filter.
     */
    private void demodulateWfm(byte[] buffer, int len, int inRateHz) {
        int numIqPairs = len / 2;
        int m1 = Math.max(1, Math.round((float) inRateHz / 256000.0f));
        float ifRate = (float) inRateHz / m1;
        float audioResampleStep = ifRate / (float) AUDIO_SAMPLE_RATE;

        float deemphAlpha = 0.2425f; // 75 us de-emphasis at 48 kHz
        float dcAlpha = 0.995f;

        float ifAccI = 0.0f;
        float ifAccQ = 0.0f;
        int ifCount = 0;

        float lastAudioSample = 0.0f;
        float prevAudioSample = 0.0f;
        float fractionalPos = 0.0f;

        for (int i = 0; i < numIqPairs; i++) {
            float curI = ((buffer[i * 2] & 0xFF) - 127.5f) / 127.5f;
            float curQ = ((buffer[i * 2 + 1] & 0xFF) - 127.5f) / 127.5f;

            ifAccI += curI;
            ifAccQ += curQ;
            ifCount++;

            if (ifCount >= m1) {
                float ifI = ifAccI / m1;
                float ifQ = ifAccQ / m1;
                ifAccI = 0.0f;
                ifAccQ = 0.0f;
                ifCount = 0;

                // FM Discriminator: delta angle = arg(s[n] * conj(s[n-1]))
                float dot = ifI * lastI + ifQ * lastQ;
                float cross = ifQ * lastI - ifI * lastQ;
                lastI = ifI;
                lastQ = ifQ;

                float angle = (float) Math.atan2(cross, dot); // radians in [-pi, pi]
                // 75 kHz deviation at 256 kSPS is ~ 1.84 rad
                float demodSample = angle / 1.85f;

                prevAudioSample = lastAudioSample;
                lastAudioSample = demodSample;

                // Resample to 48 kHz audio
                fractionalPos += 1.0f;
                while (fractionalPos >= audioResampleStep) {
                    fractionalPos -= audioResampleStep;
                    float frac = fractionalPos / audioResampleStep;
                    float interpolated = (1.0f - frac) * lastAudioSample + frac * prevAudioSample;

                    // 75 us de-emphasis
                    deemphState = deemphState + deemphAlpha * (interpolated - deemphState);

                    // DC block
                    float dcOut = deemphState - dcBlockStateX + dcAlpha * dcBlockStateY;
                    dcBlockStateX = deemphState;
                    dcBlockStateY = dcOut;

                    emitAudioSample(dcOut * volume);
                }
            }
        }
    }

    /**
     * Narrowband FM Demodulator (Ham 2m/70cm, Marine VHF, Weather Radio, Public Safety).
     * Decimates directly to 48 kSPS channel rate with squelch and de-emphasis.
     */
    private void demodulateNfm(byte[] buffer, int len, int inRateHz) {
        int numIqPairs = len / 2;
        int m = Math.max(1, Math.round((float) inRateHz / (float) AUDIO_SAMPLE_RATE));

        float deemphAlpha = 0.25f;
        float dcAlpha = 0.995f;

        float accI = 0.0f;
        float accQ = 0.0f;
        int count = 0;

        for (int i = 0; i < numIqPairs; i++) {
            float curI = ((buffer[i * 2] & 0xFF) - 127.5f) / 127.5f;
            float curQ = ((buffer[i * 2 + 1] & 0xFF) - 127.5f) / 127.5f;

            accI += curI;
            accQ += curQ;
            count++;

            if (count >= m) {
                float chI = accI / m;
                float chQ = accQ / m;
                accI = 0.0f;
                accQ = 0.0f;
                count = 0;

                // Squelch check (only squelch if threshold > -95 dBFS)
                float power = chI * chI + chQ * chQ;
                float powerDb = (float) (10.0 * Math.log10(power + 1e-12));

                if (squelchThresholdDb > -95.0f && powerDb < squelchThresholdDb) {
                    lastI = chI;
                    lastQ = chQ;
                    emitAudioSample(0.0f);
                    continue;
                }

                // Discriminator
                float dot = chI * lastI + chQ * lastQ;
                float cross = chQ * lastI - chI * lastQ;
                lastI = chI;
                lastQ = chQ;

                float angle = (float) Math.atan2(cross, dot);
                // Normalized to 5 kHz deviation at 48 kSPS (~0.65 rad)
                float audio = angle * 1.5f;

                // De-emphasis
                deemphState = deemphState + deemphAlpha * (audio - deemphState);

                // DC block
                float dcOut = deemphState - dcBlockStateX + dcAlpha * dcBlockStateY;
                dcBlockStateX = deemphState;
                dcBlockStateY = dcOut;

                emitAudioSample(dcOut * volume);
            }
        }
    }

    /**
     * Amplitude Modulation Demodulator (Aviation Airband, HF Shortwave).
     * Envelope detection: sqrt(I^2 + Q^2) with DC blocking, AGC, and squelch.
     */
    private void demodulateAm(byte[] buffer, int len, int inRateHz) {
        int numIqPairs = len / 2;
        int m = Math.max(1, Math.round((float) inRateHz / (float) AUDIO_SAMPLE_RATE));
        float dcAlpha = 0.995f;

        float accI = 0.0f;
        float accQ = 0.0f;
        int count = 0;

        for (int i = 0; i < numIqPairs; i++) {
            float curI = ((buffer[i * 2] & 0xFF) - 127.5f) / 127.5f;
            float curQ = ((buffer[i * 2 + 1] & 0xFF) - 127.5f) / 127.5f;

            accI += curI;
            accQ += curQ;
            count++;

            if (count >= m) {
                float chI = accI / m;
                float chQ = accQ / m;
                accI = 0.0f;
                accQ = 0.0f;
                count = 0;

                float power = chI * chI + chQ * chQ;
                float powerDb = (float) (10.0 * Math.log10(power + 1e-12));

                // Squelch only if threshold is above -95 dBFS
                if (squelchThresholdDb > -95.0f && powerDb < squelchThresholdDb) {
                    emitAudioSample(0.0f);
                    continue;
                }

                // Envelope detection
                float env = (float) Math.sqrt(power);

                // DC block (removes carrier DC voltage, leaving AC audio)
                float acAudio = env - dcBlockStateX + dcAlpha * dcBlockStateY;
                dcBlockStateX = env;
                dcBlockStateY = acAudio;

                // Responsive AGC tracking (fast attack, smooth decay)
                float absVal = Math.abs(acAudio);
                if (absVal > amAgcPeak) {
                    amAgcPeak = amAgcPeak * 0.9f + absVal * 0.1f;
                } else {
                    amAgcPeak = Math.max(1e-4f, amAgcPeak * 0.9998f);
                }
                float normalized = acAudio / (amAgcPeak + 1e-4f);

                emitAudioSample(normalized * volume);
            }
        }
    }

    /**
     * Single Sideband Demodulator (USB / LSB for HF amateur, maritime, and utility radio).
     * Phasing method using a 31-tap linear-phase Hilbert Transform filter.
     * USB: I[n-15] - Q_hat[n]
     * LSB: I[n-15] + Q_hat[n]
     */
    private void demodulateSsb(byte[] buffer, int len, int inRateHz, boolean isUsb) {
        int numIqPairs = len / 2;
        int m = Math.max(1, Math.round((float) inRateHz / (float) AUDIO_SAMPLE_RATE));

        float accI = 0.0f;
        float accQ = 0.0f;
        int count = 0;

        for (int i = 0; i < numIqPairs; i++) {
            float curI = ((buffer[i * 2] & 0xFF) - 127.5f) / 127.5f;
            float curQ = ((buffer[i * 2 + 1] & 0xFF) - 127.5f) / 127.5f;

            accI += curI;
            accQ += curQ;
            count++;

            if (count >= m) {
                float chI = accI / m;
                float chQ = accQ / m;
                accI = 0.0f;
                accQ = 0.0f;
                count = 0;

                float power = chI * chI + chQ * chQ;
                float powerDb = (float) (10.0 * Math.log10(power + 1e-12));

                if (squelchThresholdDb > -95.0f && powerDb < squelchThresholdDb) {
                    emitAudioSample(0.0f);
                    continue;
                }

                // Push samples into circular history buffer (size 32)
                ssbHistPos = (ssbHistPos + 1) & 31;
                ssbHistI[ssbHistPos] = chI;
                ssbHistQ[ssbHistPos] = chQ;

                // Group-delayed I sample
                float delayedI = ssbHistI[(ssbHistPos - HILBERT_DELAY) & 31];

                // Hilbert transform of Q (90-degree phase shift)
                float hilbertQ = 0.0f;
                for (int k = 0; k < HILBERT_LENGTH; k++) {
                    if (HILBERT_TAPS[k] != 0.0f) {
                        hilbertQ += HILBERT_TAPS[k] * ssbHistQ[(ssbHistPos - k) & 31];
                    }
                }

                // Phasing sideband selection
                float rawAudio = isUsb ? (delayedI - hilbertQ) : (delayedI + hilbertQ);

                // Voice bandpass: highpass ~300 Hz + lowpass ~2800 Hz at 48 kHz
                float hpOut = rawAudio - ssbHpStateX + 0.96f * ssbHpStateY;
                ssbHpStateX = rawAudio;
                ssbHpStateY = hpOut;

                ssbLpState = ssbLpState + 0.27f * (hpOut - ssbLpState);

                // Syllabic speech AGC (fast attack, smooth release)
                float absAudio = Math.abs(ssbLpState);
                if (absAudio > ssbAgcPeak) {
                    ssbAgcPeak = ssbAgcPeak * 0.85f + absAudio * 0.15f;
                } else {
                    ssbAgcPeak = Math.max(1e-4f, ssbAgcPeak * 0.9998f);
                }
                float normAudio = ssbLpState / (ssbAgcPeak + 1e-4f);

                emitAudioSample(normAudio * volume);
            }
        }
    }

    /**
     * Continuous Wave Demodulator (CW / Morse Code).
     * Heterodynes carrier against a BFO tone (default 700 Hz) followed by a sharp
     * 2nd-order IIR bandpass filter centered at the BFO pitch.
     */
    private void demodulateCw(byte[] buffer, int len, int inRateHz) {
        int numIqPairs = len / 2;
        int m = Math.max(1, Math.round((float) inRateHz / (float) AUDIO_SAMPLE_RATE));
        float phaseInc = (float) (2.0 * Math.PI * cwBfoPitchHz / (float) AUDIO_SAMPLE_RATE);

        float accI = 0.0f;
        float accQ = 0.0f;
        int count = 0;

        for (int i = 0; i < numIqPairs; i++) {
            float curI = ((buffer[i * 2] & 0xFF) - 127.5f) / 127.5f;
            float curQ = ((buffer[i * 2 + 1] & 0xFF) - 127.5f) / 127.5f;

            accI += curI;
            accQ += curQ;
            count++;

            if (count >= m) {
                float chI = accI / m;
                float chQ = accQ / m;
                accI = 0.0f;
                accQ = 0.0f;
                count = 0;

                float power = chI * chI + chQ * chQ;
                float powerDb = (float) (10.0 * Math.log10(power + 1e-12));

                if (squelchThresholdDb > -95.0f && powerDb < squelchThresholdDb) {
                    emitAudioSample(0.0f);
                    continue;
                }

                // Heterodyne: shift carrier to BFO pitch
                cwPhase += phaseInc;
                if (cwPhase > (float) (2.0 * Math.PI)) {
                    cwPhase -= (float) (2.0 * Math.PI);
                }
                float heterodyne = chI * (float) Math.cos(cwPhase) - chQ * (float) Math.sin(cwPhase);

                // Direct Form II Biquad bandpass filter around BFO pitch
                float w = heterodyne - cwA1 * cwBiquadW1 - cwA2 * cwBiquadW2;
                float y = cwB0 * (w - cwBiquadW2);
                cwBiquadW2 = cwBiquadW1;
                cwBiquadW1 = w;

                // Morse tone AGC
                float absY = Math.abs(y);
                if (absY > cwAgcPeak) {
                    cwAgcPeak = cwAgcPeak * 0.85f + absY * 0.15f;
                } else {
                    cwAgcPeak = Math.max(1e-4f, cwAgcPeak * 0.9998f);
                }
                float normTone = y / (cwAgcPeak + 1e-4f);

                emitAudioSample(normTone * volume);
            }
        }
    }

    /**
     * Emits a single 48 kHz audio sample (-1.0 to +1.0) into the PCM chunk buffer.
     * When CHUNK_SAMPLES is reached, flushes to listeners.
     */
    private void emitAudioSample(float sample) {
        // Clamp to [-1.0, 1.0]
        if (sample > 1.0f) sample = 1.0f;
        else if (sample < -1.0f) sample = -1.0f;

        short s16 = (short) Math.round(sample * 32760.0f);

        // Little Endian 16-bit PCM
        chunkBuffer[chunkBufferPos++] = (byte) (s16 & 0xFF);
        chunkBuffer[chunkBufferPos++] = (byte) ((s16 >> 8) & 0xFF);

        if (chunkBufferPos >= chunkBuffer.length) {
            flushChunk();
        }
    }

    private void flushChunk() {
        if (chunkBufferPos == 0 || listeners.isEmpty()) {
            chunkBufferPos = 0;
            return;
        }

        byte[] pcmData = new byte[chunkBufferPos];
        System.arraycopy(chunkBuffer, 0, pcmData, 0, chunkBufferPos);
        int numSamples = chunkBufferPos / 2;
        chunkBufferPos = 0;

        String base64 = Base64.getEncoder().encodeToString(pcmData);

        for (AudioFrameListener listener : listeners) {
            try {
                listener.onAudioChunk(pcmData, numSamples, base64);
            } catch (Exception e) {
                log.trace("Error dispatching audio chunk: {}", e.getMessage());
            }
        }
    }
}
