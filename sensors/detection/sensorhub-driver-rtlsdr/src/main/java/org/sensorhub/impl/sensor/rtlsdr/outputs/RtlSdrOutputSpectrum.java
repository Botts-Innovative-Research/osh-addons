/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.rtlsdr.outputs;

import net.opengis.swe.v20.*;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.data.DataArrayImpl;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

/**
 * Output specification and provider for RTL-SDR spectrum data.
 * Computes power spectral density (dBFS) across frequency bins from single-channel I/Q samples.
 */
public class RtlSdrOutputSpectrum extends AbstractSensorOutput<RtlSdrDriver> {
    private static final Logger logger = LoggerFactory.getLogger(RtlSdrOutputSpectrum.class);

    static final String SENSOR_OUTPUT_NAME = "rtlsdr_spectrum";
    static final String SENSOR_OUTPUT_LABEL = "Spectrum";
    static final String SENSOR_OUTPUT_DESCRIPTION = "RF spectrum data from the RTL-SDR receiver";

    public static class SpectrumFrame {
        public final long timestamp;
        public final double centerFreqHz;
        public final int sampleRateHz;
        public final float[] freqAxis;
        public final float[] powerDb;

        public SpectrumFrame(long timestamp, double centerFreqHz, int sampleRateHz, float[] freqAxis, float[] powerDb) {
            this.timestamp = timestamp;
            this.centerFreqHz = centerFreqHz;
            this.sampleRateHz = sampleRateHz;
            this.freqAxis = freqAxis;
            this.powerDb = powerDb;
        }
    }

    private volatile SpectrumFrame latestFrame;

    private DataRecord dataStruct;
    private DataEncoding dataEncoding;

    private DataArray freqAxisArray;
    private DataArray amplitudeArray;

    private int fftSize = 1024;
    private float[] window;
    private long lastStareProcessTime = 0;

    public RtlSdrOutputSpectrum(RtlSdrDriver parentSensor) {
        super(SENSOR_OUTPUT_NAME, parentSensor);
        initFFT(1024);
    }

    public void doInit() {
        logger.info("Initializing RTL-SDR Spectrum Output");

        SWEHelper sweFactory = new SWEHelper();

        String freqCountId = "NUM_FREQ_BINS";
        String ampCountId = "NUM_AMP_BINS";

        dataStruct = sweFactory.createRecord()
                .name(SENSOR_OUTPUT_NAME)
                .label(SENSOR_OUTPUT_LABEL)
                .definition(SWEHelper.getPropertyUri("RtlSdrSpectrumData"))
                .addField("sample_time", sweFactory.createTime()
                        .asSamplingTimeIsoUTC()
                        .label("Sample Time")
                        .description("Time of RF spectrum measurement")
                        .definition(SWEConstants.QUDT_URI_PREFIX + "Time"))
                .addField("center_frequency", sweFactory.createQuantity()
                        .uomCode("Hz")
                        .label("Center Frequency")
                        .description("Tuned center frequency in Hz")
                        .definition(SWEConstants.QUDT_URI_PREFIX + "Frequency"))
                .addField("freq_count", sweFactory.createCount()
                        .id(freqCountId)
                        .label("Frequency Axis Size")
                        .description("Number of points in the frequency_axis array")
                        .definition(SWEHelper.getPropertyUri("NumberOfFrequencyPoints")))
                .addField("frequency_axis", sweFactory.createArray()
                        .withVariableSize(freqCountId)
                        .label("Frequency Axis")
                        .description("Center frequency of each sample point in Hz")
                        .definition(SWEConstants.QUDT_URI_PREFIX + "Frequency")
                        .withElement("frequency", sweFactory.createQuantity()
                                .uomCode("Hz")
                                .definition(SWEConstants.QUDT_URI_PREFIX + "Frequency")))
                .addField("amp_count", sweFactory.createCount()
                        .id(ampCountId)
                        .label("Amplitude Axis Size")
                        .description("Number of points in the amplitude array")
                        .definition(SWEHelper.getPropertyUri("NumberOfAmplitudePoints")))
                .addField("amplitude", sweFactory.createArray()
                        .withVariableSize(ampCountId)
                        .label("Spectrum Amplitude")
                        .description("Signal amplitude in dBFS across the spectrum")
                        .definition(SWEHelper.getPropertyUri("SpectrumAmplitude"))
                        .withElement("amplitude", sweFactory.createQuantity()
                                .uomCode("dB")
                                .definition(SWEHelper.getPropertyUri("DecibelFullScale"))))
                .build();

        freqAxisArray = (DataArray) dataStruct.getField("frequency_axis");
        amplitudeArray = (DataArray) dataStruct.getField("amplitude");

        dataEncoding = sweFactory.newTextEncoding(",", "\n");

        if (parentSensor != null && parentSensor.getConfiguration() != null) {
            initFFT(parentSensor.getConfiguration().fftSize);
        }
    }

    public synchronized void initFFT(int size) {
        // Enforce power of 2
        int p = 1;
        while (p < size && p < 16384) {
            p <<= 1;
        }
        this.fftSize = p;

        // Precompute Hann window
        window = new float[fftSize];
        for (int i = 0; i < fftSize; i++) {
            window[i] = (float) (0.5 * (1.0 - Math.cos((2.0 * Math.PI * i) / (fftSize - 1))));
        }
    }

    @Override
    public DataComponent getRecordDescription() {
        return dataStruct;
    }

    @Override
    public DataEncoding getRecommendedEncoding() {
        return dataEncoding;
    }

    @Override
    public double getAverageSamplingPeriod() {
        return 0.1;
    }

    public SpectrumFrame getLatestSpectrumFrame() {
        return latestFrame;
    }

    /**
     * Ingests raw interleaved unsigned 8-bit I/Q bytes, computes FFT and publishes spectrum.
     */
    public synchronized void processSamples(byte[] buffer, int len, long centerFreqHz, int sampleRateHz) {
        if (len < fftSize * 2) {
            return;
        }

        long now = System.currentTimeMillis();
        // Rate-limit Stare mode FFT processing to ~25 fps (40 ms)
        if (now - lastStareProcessTime < 40) {
            return;
        }
        lastStareProcessTime = now;

        int N = fftSize;
        float[] real = new float[N];
        float[] imag = new float[N];

        // Convert interleaved unsigned 8-bit (0-255) to normalized -1.0..+1.0 with window
        for (int i = 0; i < N; i++) {
            float iSample = ((buffer[i * 2] & 0xFF) - 127.5f) / 127.5f;
            float qSample = ((buffer[i * 2 + 1] & 0xFF) - 127.5f) / 127.5f;
            real[i] = iSample * window[i];
            imag[i] = qSample * window[i];
        }

        // Compute FFT
        fft(real, imag, N);

        // FFT shift: swap halves so center frequency is in the middle
        float[] powerDb = new float[N];
        float[] freqAxis = new float[N];
        double binWidth = (double) sampleRateHz / N;
        double startFreq = centerFreqHz - (sampleRateHz / 2.0);

        for (int i = 0; i < N; i++) {
            int shiftedIdx = (i + N / 2) % N;
            float r = real[shiftedIdx];
            float im = imag[shiftedIdx];
            float magSq = r * r + im * im;
            // Convert to dBFS
            powerDb[i] = (magSq > 1e-12f) ? (float) (10.0 * Math.log10(magSq / N)) : -120.0f;
            freqAxis[i] = (float) (startFreq + i * binWidth);
        }

        latestFrame = new SpectrumFrame(now, centerFreqHz, sampleRateHz, freqAxis, powerDb);

        // Publish SWE record if initialized
        if (dataStruct != null && eventHandler != null) {
            try {
                ((DataArrayImpl) freqAxisArray).updateSize(N);
                ((DataArrayImpl) amplitudeArray).updateSize(N);

                DataBlock dataBlock = dataStruct.createDataBlock();

                OffsetDateTime odt = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC);

                dataBlock.setDateTime(0, odt);
                dataBlock.setDoubleValue(1, centerFreqHz);
                dataBlock.setIntValue(2, N);
                for (int i = 0; i < N; i++) {
                    dataBlock.setFloatValue(3 + i, freqAxis[i]);
                }
                dataBlock.setIntValue(3 + N, N);
                for (int i = 0; i < N; i++) {
                    dataBlock.setFloatValue(3 + N + 1 + i, powerDb[i]);
                }

                latestRecord = dataBlock;
                latestRecordTime = now;

                eventHandler.publish(new DataEvent(latestRecordTime, RtlSdrOutputSpectrum.this, dataBlock));
            } catch (Exception e) {
                logger.error("Failed to publish RTL-SDR spectrum data: {}", e.getMessage());
            }
        }
    }


    /**
     * In-place radix-2 Cooley-Tukey complex FFT.
     */
    public static void fft(float[] real, float[] imag, int n) {
        int j = 0;
        for (int i = 0; i < n - 1; i++) {
            if (i < j) {
                float tr = real[i]; real[i] = real[j]; real[j] = tr;
                float ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
            int k = n >> 1;
            while (k <= j) {
                j -= k;
                k >>= 1;
            }
            j += k;
        }

        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            float wlenR = (float) Math.cos(angle);
            float wlenI = (float) Math.sin(angle);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                float wR = 1.0f;
                float wI = 0.0f;
                for (int k = 0; k < half; k++) {
                    int u = i + k;
                    int v = i + k + half;
                    float vR = real[v] * wR - imag[v] * wI;
                    float vI = real[v] * wI + imag[v] * wR;
                    real[v] = real[u] - vR;
                    imag[v] = imag[u] - vI;
                    real[u] = real[u] + vR;
                    imag[u] = imag[u] + vI;
                    float nextWR = wR * wlenR - wI * wlenI;
                    wI = wR * wlenI + wI * wlenR;
                    wR = nextWR;
                }
            }
        }
    }
}
