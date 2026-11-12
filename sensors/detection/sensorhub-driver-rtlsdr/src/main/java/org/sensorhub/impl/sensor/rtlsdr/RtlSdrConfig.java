/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.rtlsdr;

import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.sensor.SensorConfig;

/**
 * Configuration settings for the {@link RtlSdrDriver} driver exposed via the OpenSensorHub Admin panel.
 */
public class RtlSdrConfig extends SensorConfig {

    public enum OperatingMode {
        SPECTRUM_AUDIO,
        ADSB_RECEIVER,
        AIS_RECEIVER
    }

    public enum AudioDemodMode {
        OFF,
        AM,
        NFM,
        WFM,
        USB,
        LSB,
        CW
    }

    @DisplayInfo.Required
    @DisplayInfo(label = "Operating Mode", desc = "Driver operating mode: Spectrum/Audio monitor, 1090 MHz ADS-B Aircraft Receiver, or 162 MHz Marine AIS Receiver")
    public OperatingMode operatingMode = OperatingMode.SPECTRUM_AUDIO;

    @DisplayInfo.Required
    @DisplayInfo(desc = "Serial number or unique identifier")
    public String serialNumber = "rtlsdr001";

    @DisplayInfo.Required
    @DisplayInfo(label = "Device Index", desc = "Zero-based index of the RTL-SDR USB dongle (typically 0)")
    public int deviceIndex = 0;

    @DisplayInfo.Required
    @DisplayInfo(label = "Center Frequency (MHz)", desc = "Tuned center frequency in MHz (e.g. 100.0 for FM, 433.92 for ISM)")
    public double centerFrequency = 98.0;

    @DisplayInfo.Required
    @DisplayInfo(label = "Sample Rate (SPS)", desc = "Sample rate in samples/second (typically 2048000, 2400000, or 2560000)")
    public int sampleRate = 2048000;


    @DisplayInfo(label = "Tuner Gain (dB)", desc = "Tuner gain in dB (0.0 to 49.6). Ignored if Auto Gain is enabled.")
    public double tunerGain = 20.0;

    @DisplayInfo(label = "Auto Gain Control (Tuner)", desc = "Enable automatic gain control for tuner")
    public boolean autoGain = true;

    @DisplayInfo(label = "RTL Digital AGC", desc = "Enable digital AGC on the RTL2832 chip")
    public boolean agcMode = false;

    @DisplayInfo(label = "Frequency Correction (PPM)", desc = "Frequency error correction in parts per million (PPM)")
    public int ppmCorrection = 0;

    @DisplayInfo(label = "Direct Sampling Mode", desc = "0 = Disabled (tuner active), 1 = I-branch ADC direct sampling (HF), 2 = Q-branch ADC direct sampling (HF)")
    public int directSampling = 0;

    @DisplayInfo(label = "FFT Size", desc = "FFT size for spectrum output (must be power of 2, e.g. 512, 1024, 2048)")
    public int fftSize = 1024;

    @DisplayInfo(label = "Audio Demodulation Mode", desc = "Audio demodulator mode (OFF, AM, NFM, WFM)")
    public AudioDemodMode audioMode = AudioDemodMode.OFF;

    @DisplayInfo(label = "Audio Volume (%)", desc = "Audio output gain / volume percentage (0 to 100)")
    public int audioVolume = 40;

    @DisplayInfo(label = "Squelch Threshold (dBFS)", desc = "Signal power threshold in dBFS below which audio is muted")
    public double squelchThreshold = -65.0;

    @DisplayInfo(label = "CW BFO Pitch (Hz)", desc = "Beat Frequency Oscillator pitch for CW Morse code reception (typically 600-800 Hz)")
    public double cwBfoToneHz = 700.0;

    @DisplayInfo(label = "Receiver Latitude", desc = "Reference latitude of the ground station receiver (optional, enables instant 1-frame ADS-B local CPR decoding)")
    public double receiverLat = Double.NaN;

    @DisplayInfo(label = "Receiver Longitude", desc = "Reference longitude of the ground station receiver (optional, enables instant 1-frame ADS-B local CPR decoding)")
    public double receiverLon = Double.NaN;
}
