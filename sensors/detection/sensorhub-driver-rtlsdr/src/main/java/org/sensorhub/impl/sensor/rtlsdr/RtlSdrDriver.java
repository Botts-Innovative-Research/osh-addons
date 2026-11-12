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

import org.sensorhub.api.command.CommandException;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.sensorhub.impl.sensor.rtlsdr.controls.RtlSdrControlReceiver;
import org.sensorhub.impl.sensor.rtlsdr.dsp.RtlSdrAudioDemodulator;
import org.sensorhub.impl.sensor.rtlsdr.lib.RtlSdrDevice;
import org.sensorhub.impl.sensor.rtlsdr.outputs.RtlSdrOutputAdsb;
import org.sensorhub.impl.sensor.rtlsdr.outputs.RtlSdrOutputAis;
import org.sensorhub.impl.sensor.rtlsdr.outputs.RtlSdrOutputSettings;
import org.sensorhub.impl.sensor.rtlsdr.outputs.RtlSdrOutputSpectrum;
import org.vast.ogc.om.MovingFeature;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Driver implementation for the RTL-SDR software-defined radio receiver.
 * Controls a single RTL2832-based USB dongle via JNA bindings to librtlsdr.
 * Supports Spectrum & Audio monitoring, 1090 MHz ADS-B, and Marine AIS modes.
 */
public class RtlSdrDriver extends AbstractSensorModule<RtlSdrConfig> {
    static final String UID_PREFIX = "urn:osh:sensor:rtlsdr:";
    static final String XML_PREFIX = "rtlSdr";

    private RtlSdrDevice device;
    private RtlSdrControlReceiver controlReceiver;
    private RtlSdrOutputSettings outputSettings;
    private RtlSdrOutputSpectrum outputSpectrum;
    private RtlSdrOutputAdsb outputAdsb;
    private RtlSdrOutputAis outputAis;
    private final RtlSdrAudioDemodulator audioDemodulator = new RtlSdrAudioDemodulator();
    private ScheduledExecutorService keepAliveExecutor;

    @Override
    public void doInit() throws SensorHubException {
        super.doInit();

        if (config.operatingMode == null) {
            config.operatingMode = RtlSdrConfig.OperatingMode.SPECTRUM_AUDIO;
        }
        if (config.audioMode == null) {
            config.audioMode = RtlSdrConfig.AudioDemodMode.OFF;
        }

        generateUniqueID(UID_PREFIX, config.serialNumber);
        generateXmlID(XML_PREFIX, config.serialNumber);

        // Controls
        controlReceiver = new RtlSdrControlReceiver(this);
        addControlInput(controlReceiver);
        controlReceiver.doInit();

        // Outputs
        outputSettings = new RtlSdrOutputSettings(this);
        addOutput(outputSettings, false);
        outputSettings.doInit();

        outputSpectrum = new RtlSdrOutputSpectrum(this);
        addOutput(outputSpectrum, false);
        outputSpectrum.doInit();

        outputAdsb = new RtlSdrOutputAdsb(this);
        addOutput(outputAdsb, false);
        outputAdsb.doInit();
        if (config != null && outputAdsb.getDecoder() != null) {
            outputAdsb.getDecoder().setReceiverLocation(config.receiverLat, config.receiverLon);
        }

        outputAis = new RtlSdrOutputAis(this);
        addOutput(outputAis, false);
        outputAis.doInit();
    }

    @Override
    public synchronized void updateConfig(RtlSdrConfig newConfig) throws SensorHubException {
        if (isStarted() && device != null && device.isOpen() && newConfig.deviceIndex == config.deviceIndex) {
            this.config = newConfig;
            try {
                configureDevice();
                audioDemodulator.setMode(config.audioMode);
                audioDemodulator.setVolume(config.audioVolume / 100.0f);
                audioDemodulator.setSquelchThresholdDb((float) config.squelchThreshold);
                audioDemodulator.setCwBfoPitchHz((float) config.cwBfoToneHz);
                if (outputAdsb != null && outputAdsb.getDecoder() != null) {
                    outputAdsb.getDecoder().setReceiverLocation(config.receiverLat, config.receiverLon);
                }
                publishCurrentSettings();
            } catch (IOException e) {
                throw new SensorHubException("Failed to reconfigure RTL-SDR: " + e.getMessage(), e);
            }
            if (eventHandler != null) {
                eventHandler.publish(new ModuleEvent(this, ModuleEvent.Type.CONFIG_CHANGED));
            }
            return;
        }

        super.updateConfig(newConfig);
    }

    private void configureDevice() throws IOException {
        // Apply presets based on operating mode
        if (config.operatingMode == RtlSdrConfig.OperatingMode.ADSB_RECEIVER) {
            config.centerFrequency = 1090.0;
            config.sampleRate = 2000000;
            config.audioMode = RtlSdrConfig.AudioDemodMode.OFF;
            if (outputAdsb != null && outputAdsb.getDecoder() != null) {
                outputAdsb.getDecoder().setReceiverLocation(config.receiverLat, config.receiverLon);
            }
        } else if (config.operatingMode == RtlSdrConfig.OperatingMode.AIS_RECEIVER) {
            config.centerFrequency = 162.0;
            config.sampleRate = 2048000;
            config.audioMode = RtlSdrConfig.AudioDemodMode.OFF;
        }

        // Configure sample rate first
        device.setSampleRate(config.sampleRate);

        // Direct sampling check: frequencies below 24 MHz require direct sampling mode 2 (Q-branch)
        if (config.centerFrequency < 24.0 && config.directSampling == 0) {
            device.setDirectSampling(2);
        } else if (config.directSampling != 0) {
            device.setDirectSampling(config.directSampling);
        } else {
            device.setDirectSampling(0);
        }

        // Configure center frequency (convert MHz to Hz)
        long centerFreqHz = Math.round(config.centerFrequency * 1_000_000.0);
        device.setCenterFreq(centerFreqHz);

        // Configure gain
        if (config.autoGain) {
            device.setTunerGainMode(false);
        } else {
            device.setTunerGainMode(true);
            int gainTenths = (int) Math.round(config.tunerGain * 10.0);
            device.setTunerGain(gainTenths);
        }

        // Configure AGC, PPM
        device.setAgcMode(config.agcMode);
        if (config.ppmCorrection != 0) {
            device.setFreqCorrection(config.ppmCorrection);
        }
    }

    @Override
    public void doStart() throws SensorHubException {
        super.doStart();

        try {
            if (device == null || !device.isOpen() || device.getCurrentDeviceIndex() != config.deviceIndex) {
                if (device != null) {
                    try {
                        device.close();
                    } catch (Throwable t) {
                        getLogger().warn("Error closing existing RTL-SDR device: {}", t.getMessage());
                    } finally {
                        device = null;
                    }
                }
                device = new RtlSdrDevice();
                getLogger().info("Opening RTL-SDR device index: {}", config.deviceIndex);
                device.open(config.deviceIndex);
            } else {
                getLogger().info("Reusing open RTL-SDR device handle (index: {})", device.getCurrentDeviceIndex());
            }

            configureDevice();

            // Configure audio demodulator
            audioDemodulator.setMode(config.audioMode);
            audioDemodulator.setVolume(config.audioVolume / 100.0f);
            audioDemodulator.setSquelchThresholdDb((float) config.squelchThreshold);
            audioDemodulator.setCwBfoPitchHz((float) config.cwBfoToneHz);

            // Set up sample listener dispatching according to active OperatingMode
            device.clearSampleListeners();
            device.addSampleListener((buffer, len) -> {
                long currentFreqHz = Math.round(config.centerFrequency * 1_000_000.0);
                RtlSdrConfig.OperatingMode mode = config.operatingMode != null ? config.operatingMode : RtlSdrConfig.OperatingMode.SPECTRUM_AUDIO;
                switch (mode) {
                    case ADSB_RECEIVER:
                        if (outputAdsb != null) {
                            outputAdsb.processSamples(buffer, len, config.sampleRate);
                        }
                        if (outputSpectrum != null) {
                            outputSpectrum.processSamples(buffer, len, 1090_000_000L, config.sampleRate);
                        }
                        break;
                    case AIS_RECEIVER:
                        if (outputAis != null) {
                            outputAis.processSamples(buffer, len, config.sampleRate);
                        }
                        if (outputSpectrum != null) {
                            outputSpectrum.processSamples(buffer, len, 162_000_000L, config.sampleRate);
                        }
                        break;
                    case SPECTRUM_AUDIO:
                    default:
                        if (outputSpectrum != null) {
                            outputSpectrum.processSamples(buffer, len, currentFreqHz, config.sampleRate);
                        }
                        if (audioDemodulator != null) {
                            audioDemodulator.processSamples(buffer, len, config.sampleRate);
                        }
                        break;
                }
            });

            // Start continuous async streaming
            device.startStreaming();

            // Publish initial settings
            publishCurrentSettings();

            // Schedule periodic settings heartbeat (every 30 seconds) to prevent WebSocket idle timeout
            stopKeepAliveExecutor();
            keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "RtlSdr-Heartbeat");
                t.setDaemon(true);
                return t;
            });
            keepAliveExecutor.scheduleAtFixedRate(this::publishCurrentSettings, 30, 30, TimeUnit.SECONDS);

        } catch (Throwable t) {
            getLogger().error("Failed to start RTL-SDR device", t);
            if (device != null) {
                try {
                    device.close();
                } catch (Throwable ignored) {
                } finally {
                    device = null;
                }
            }
            throw new SensorHubException("Failed to start RTL-SDR device: " + t.getMessage(), t);
        }
    }

    @Override
    public void doStop() throws SensorHubException {
        stopKeepAliveExecutor();
        if (device != null && device.isOpen()) {
            try {
                device.stopStreaming();
            } catch (Throwable t) {
                getLogger().warn("Error stopping RTL-SDR streaming: {}", t.getMessage());
            }
        }
        publishCurrentSettings();
        super.doStop();
    }

    private void stopKeepAliveExecutor() {
        if (keepAliveExecutor != null) {
            try {
                keepAliveExecutor.shutdownNow();
            } catch (Throwable ignored) {
            } finally {
                keepAliveExecutor = null;
            }
        }
    }

    @Override
    public void cleanup() {
        stopKeepAliveExecutor();
        if (device != null) {
            try {
                device.close();
            } catch (Throwable ignored) {
            } finally {
                device = null;
            }
        }
        try {
            super.cleanup();
        } catch (SensorHubException e) {
            getLogger().warn("Error in super.cleanup(): {}", e.getMessage());
        }
    }

    @Override
    public boolean isConnected() {
        return device != null && device.isOpen();
    }

    public synchronized void setCenterFrequency(double freqMHz) throws CommandException {
        config.centerFrequency = freqMHz;
        long freqHz = Math.round(freqMHz * 1_000_000.0);
        if (device != null && device.isOpen()) {
            try {
                // Tuners like R820T cannot tune below 24 MHz; automatically engage Direct Sampling mode 2 (Q-branch)
                if (freqMHz < 24.0) {
                    device.setDirectSampling(2);
                } else if (config.directSampling == 0) {
                    device.setDirectSampling(0);
                }
                device.setCenterFreq(freqHz);
            } catch (IOException e) {
                throw new CommandException("Failed to set center frequency: " + e.getMessage(), e);
            }
        }
        publishCurrentSettings();
    }

    public synchronized void setTunerGain(double gainDb) throws CommandException {
        config.tunerGain = gainDb;
        config.autoGain = false;
        if (device != null && device.isOpen()) {
            try {
                int gainTenths = (int) Math.round(gainDb * 10.0);
                device.setTunerGain(gainTenths);
            } catch (IOException e) {
                throw new CommandException("Failed to set tuner gain: " + e.getMessage(), e);
            }
        }
        publishCurrentSettings();
    }

    public synchronized void setAutoGain(boolean autoGain) throws CommandException {
        config.autoGain = autoGain;
        if (device != null && device.isOpen()) {
            try {
                device.setTunerGainMode(!autoGain);
            } catch (IOException e) {
                throw new CommandException("Failed to set auto gain mode: " + e.getMessage(), e);
            }
        }
        publishCurrentSettings();
    }

    public synchronized void setSampleRate(int rateHz) throws CommandException {
        config.sampleRate = rateHz;
        if (device != null && device.isOpen()) {
            try {
                device.setSampleRate(rateHz);
            } catch (IOException e) {
                throw new CommandException("Failed to set sample rate: " + e.getMessage(), e);
            }
        }
        publishCurrentSettings();
    }

    public synchronized void setAgcMode(boolean agcMode) throws CommandException {
        config.agcMode = agcMode;
        if (device != null && device.isOpen()) {
            try {
                device.setAgcMode(agcMode);
            } catch (IOException e) {
                throw new CommandException("Failed to set AGC mode: " + e.getMessage(), e);
            }
        }
        publishCurrentSettings();
    }

    public RtlSdrAudioDemodulator getAudioDemodulator() {
        return audioDemodulator;
    }

    public synchronized void setOperatingMode(RtlSdrConfig.OperatingMode mode) {
        config.operatingMode = mode;
        if (device != null && device.isOpen()) {
            try {
                configureDevice();
            } catch (IOException e) {
                getLogger().error("Error configuring device for operating mode: {}", e.getMessage());
            }
        }
        publishCurrentSettings();
    }

    public synchronized void setAudioMode(RtlSdrConfig.AudioDemodMode mode) {
        config.audioMode = mode;
        audioDemodulator.setMode(mode);
    }

    public synchronized void setAudioVolume(float vol) {
        config.audioVolume = Math.round(vol * 100.0f);
        audioDemodulator.setVolume(vol);
    }

    public synchronized void setSquelchThreshold(float squelchDb) {
        config.squelchThreshold = squelchDb;
        audioDemodulator.setSquelchThresholdDb(squelchDb);
    }

    public RtlSdrOutputSpectrum getOutputSpectrum() {
        return outputSpectrum;
    }

    public RtlSdrOutputSettings getOutputSettings() {
        return outputSettings;
    }

    public RtlSdrOutputAdsb getOutputAdsb() {
        return outputAdsb;
    }

    public RtlSdrOutputAis getOutputAis() {
        return outputAis;
    }

    public String addOrUpdateAircraftFoi(String icao, String callsign) {
        String foiUID = UID_PREFIX + "aircraft:" + icao;
        synchronized (foiMap) {
            if (!foiMap.containsKey(foiUID)) {
                MovingFeature foi = new MovingFeature();
                foi.setId(icao);
                foi.setUniqueIdentifier(foiUID);
                foi.setDescription("ADS-B Aircraft " + icao);
                if (callsign != null && !callsign.isEmpty()) {
                    foi.setName(callsign);
                } else {
                    foi.setName(icao);
                }
                addFoi(foi);
                getLogger().info("Registered new aircraft FOI: {}", foiUID);
            }
        }
        return foiUID;
    }

    public String addOrUpdateVesselFoi(long mmsi, String vesselName) {
        String foiUID = UID_PREFIX + "vessel:" + mmsi;
        synchronized (foiMap) {
            if (!foiMap.containsKey(foiUID)) {
                MovingFeature foi = new MovingFeature();
                foi.setId(String.valueOf(mmsi));
                foi.setUniqueIdentifier(foiUID);
                foi.setDescription("AIS Marine Vessel " + mmsi);
                if (vesselName != null && !vesselName.isEmpty()) {
                    foi.setName(vesselName);
                } else {
                    foi.setName(String.valueOf(mmsi));
                }
                addFoi(foi);
                getLogger().info("Registered new vessel FOI: {}", foiUID);
            }
        }
        return foiUID;
    }

    public RtlSdrDevice getDevice() {
        return device;
    }

    private void publishCurrentSettings() {
        if (outputSettings != null) {
            outputSettings.publishSettings(
                    config.centerFrequency,
                    config.sampleRate,
                    config.tunerGain,
                    config.autoGain,
                    config.agcMode,
                    config.ppmCorrection,
                    device != null && device.isStreaming()
            );
        }
    }
}
