/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
import org.junit.Test;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.impl.sensor.rtlsdr.Descriptor;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrConfig;
import org.sensorhub.impl.sensor.rtlsdr.RtlSdrDriver;
import org.sensorhub.impl.sensor.rtlsdr.lib.RtlSdrDevice;
import org.sensorhub.impl.sensor.rtlsdr.outputs.RtlSdrOutputSpectrum;

import static org.junit.Assert.*;

public class TestRtlSdrDriver {

    @Test
    public void testDescriptor() {
        Descriptor descriptor = new Descriptor();
        assertEquals(RtlSdrDriver.class, descriptor.getModuleClass());
        assertEquals(RtlSdrConfig.class, descriptor.getModuleConfigClass());
    }

    @Test
    public void testConfigDefaults() {
        RtlSdrConfig config = new RtlSdrConfig();
        assertEquals("rtlsdr001", config.serialNumber);
        assertEquals(0, config.deviceIndex);
        assertEquals(98.0, config.centerFrequency, 0.001);
        assertEquals(2048000, config.sampleRate);
        assertTrue(config.autoGain);
        assertEquals(1024, config.fftSize);
        assertEquals(RtlSdrConfig.OperatingMode.SPECTRUM_AUDIO, config.operatingMode);
        assertEquals(RtlSdrConfig.AudioDemodMode.OFF, config.audioMode);
    }

    @Test
    public void testDriverInit() throws Exception {
        RtlSdrConfig config = new RtlSdrConfig();
        RtlSdrDriver driver = new RtlSdrDriver();
        driver.setConfiguration(config);
        driver.init();

        // Verify outputs are registered
        assertNotNull(driver.getObservationOutputs());
        assertEquals(4, driver.getObservationOutputs().size());

        IStreamingDataInterface spectrumOutput = driver.getObservationOutputs().get("rtlsdr_spectrum");
        assertNotNull("Spectrum output should be registered", spectrumOutput);

        IStreamingDataInterface settingsOutput = driver.getObservationOutputs().get("rtlsdr_settings");
        assertNotNull("Settings output should be registered", settingsOutput);

        IStreamingDataInterface adsbOutput = driver.getObservationOutputs().get("adsbOutput");
        assertNotNull("ADS-B output should be registered", adsbOutput);

        IStreamingDataInterface aisOutput = driver.getObservationOutputs().get("aisOutput");
        assertNotNull("AIS output should be registered", aisOutput);

        // Verify control input is registered
        assertNotNull(driver.getCommandInputs());
        assertTrue(driver.getCommandInputs().containsKey("receiverControl"));
    }

    @Test
    public void testAudioDemodulatorModes() {
        org.sensorhub.impl.sensor.rtlsdr.dsp.RtlSdrAudioDemodulator demod = new org.sensorhub.impl.sensor.rtlsdr.dsp.RtlSdrAudioDemodulator();
        final int[] chunkCount = new int[1];
        demod.addListener((pcm, numSamples, b64) -> chunkCount[0]++);

        byte[] syntheticIq = new byte[48000 * 2]; // 1 second of samples
        for (int i = 0; i < 48000; i++) {
            double angle = 2.0 * Math.PI * 1000.0 * i / 48000.0;
            syntheticIq[i * 2] = (byte) Math.round(127.5 + 80.0 * Math.cos(angle));
            syntheticIq[i * 2 + 1] = (byte) Math.round(127.5 + 80.0 * Math.sin(angle));
        }

        // Test USB
        demod.setMode(RtlSdrConfig.AudioDemodMode.USB);
        demod.processSamples(syntheticIq, syntheticIq.length, 48000);

        // Test LSB
        demod.setMode(RtlSdrConfig.AudioDemodMode.LSB);
        demod.processSamples(syntheticIq, syntheticIq.length, 48000);

        // Test CW
        demod.setMode(RtlSdrConfig.AudioDemodMode.CW);
        demod.setCwBfoPitchHz(700.0f);
        demod.processSamples(syntheticIq, syntheticIq.length, 48000);

        assertTrue("Should have emitted audio chunks", chunkCount[0] > 0);
    }

    @Test
    public void testAdsbCrcAndDecoder() {
        org.sensorhub.impl.sensor.rtlsdr.dsp.AdsbDecoder decoder = new org.sensorhub.impl.sensor.rtlsdr.dsp.AdsbDecoder();

        // Standard test Mode S Extended Squitter message (DF=17, ICAO: 40621D)
        // Hex: 8D40621D58C382D690C8AC2863A7
        byte[] rawAdsb = new byte[] {
                (byte) 0x8D, (byte) 0x40, (byte) 0x62, (byte) 0x1D,
                (byte) 0x58, (byte) 0xC3, (byte) 0x82, (byte) 0xD6,
                (byte) 0x90, (byte) 0xC8, (byte) 0xAC,
                (byte) 0x28, (byte) 0x63, (byte) 0xA7
        };

        int crc = org.sensorhub.impl.sensor.rtlsdr.dsp.AdsbDecoder.computeCrc(rawAdsb, 112);
        assertEquals("Mode S CRC-24 parity check must pass (0 remainder)", 0, crc);
    }

    @Test
    public void testAdsbCprGlobalAndRelativeDecoding() {
        // Standard test Mode S Extended Squitter message pair (DF=17, ICAO: 40621D)
        // Even: 8D40621D58C382D690C8AC2863A7
        // Odd:  8D40621D58C386435CC41269201F
        byte[] evenMsg = new byte[] {
                (byte) 0x8D, (byte) 0x40, (byte) 0x62, (byte) 0x1D,
                (byte) 0x58, (byte) 0xC3, (byte) 0x82, (byte) 0xD6,
                (byte) 0x90, (byte) 0xC8, (byte) 0xAC,
                (byte) 0x28, (byte) 0x63, (byte) 0xA7
        };
        byte[] oddMsg = new byte[] {
                (byte) 0x8D, (byte) 0x40, (byte) 0x62, (byte) 0x1D,
                (byte) 0x58, (byte) 0xC3, (byte) 0x86, (byte) 0x43,
                (byte) 0x5C, (byte) 0xC4, (byte) 0x12,
                (byte) 0x69, (byte) 0x20, (byte) 0x1F
        };

        // 1. Test Global CPR Decoding (no receiver location configured)
        org.sensorhub.impl.sensor.rtlsdr.dsp.AdsbDecoder globalDecoder = new org.sensorhub.impl.sensor.rtlsdr.dsp.AdsbDecoder();
        globalDecoder.decodeExtendedSquitter(evenMsg);
        org.sensorhub.impl.sensor.rtlsdr.dsp.AdsbDecoder.AircraftState state = globalDecoder.getAircraftMap().get("40621D");
        assertNotNull(state);
        // Only 1 frame received without reference location -> position should not yet be known (NaN)
        assertTrue("Position should be NaN until second CPR frame arrives", Double.isNaN(state.lat));

        // Now supply the odd frame
        globalDecoder.decodeExtendedSquitter(oddMsg);
        assertFalse("Latitude should be resolved after even/odd pair", Double.isNaN(state.lat));
        assertFalse("Longitude should be resolved after even/odd pair", Double.isNaN(state.lon));
        assertEquals(52.257, state.lat, 0.01);
        assertEquals(3.939, state.lon, 0.01);
        assertEquals(38000.0, state.altBaroFt, 100.0);

        // 2. Test Relative CPR Decoding with receiver reference location (instant 1-frame fix)
        org.sensorhub.impl.sensor.rtlsdr.dsp.AdsbDecoder relDecoder = new org.sensorhub.impl.sensor.rtlsdr.dsp.AdsbDecoder();
        relDecoder.setReceiverLocation(52.0, 4.0); // Receiver near the aircraft
        relDecoder.decodeExtendedSquitter(evenMsg); // Supply ONLY even frame
        org.sensorhub.impl.sensor.rtlsdr.dsp.AdsbDecoder.AircraftState relState = relDecoder.getAircraftMap().get("40621D");
        assertNotNull(relState);
        assertFalse("Relative CPR should resolve latitude on a single frame with receiver location", Double.isNaN(relState.lat));
        assertFalse("Relative CPR should resolve longitude on a single frame with receiver location", Double.isNaN(relState.lon));
        assertEquals(52.257, relState.lat, 0.01);
        assertEquals(3.919, relState.lon, 0.01);
    }

    @Test
    public void testAisDecoder() {
        org.sensorhub.impl.sensor.rtlsdr.dsp.AisDecoder decoder = new org.sensorhub.impl.sensor.rtlsdr.dsp.AisDecoder();
        assertNotNull(decoder.getVesselMap());
        assertEquals(0, decoder.getVesselMap().size());
    }

    @Test
    public void testSpectrumProcessing() {
        RtlSdrDriver driver = new RtlSdrDriver();
        RtlSdrConfig config = new RtlSdrConfig();
        config.fftSize = 512;
        driver.setConfiguration(config);

        RtlSdrOutputSpectrum spectrum = new RtlSdrOutputSpectrum(driver);
        spectrum.doInit();

        // Generate synthetic silence (all 127 = 0 amplitude in unsigned 8-bit)
        byte[] dummyBuffer = new byte[1024];
        for (int i = 0; i < dummyBuffer.length; i++) {
            dummyBuffer[i] = (byte) 127;
        }

        // Processing should execute without throwing any exception
        spectrum.processSamples(dummyBuffer, dummyBuffer.length, 100_000_000L, 2048000);
        assertNotNull(spectrum.getLatestRecord());
    }

    private boolean isDeviceAvailable() {
        try {
            return RtlSdrDevice.getDeviceCount() > 0;
        } catch (Throwable t) {
            System.out.println("RTL-SDR native library or device not available: " + t.getMessage());
            return false;
        }
    }

    @Test
    public void testDeviceOnly() throws Exception {
        if (!isDeviceAvailable()) {
            System.out.println("No RTL-SDR device attached or native library missing; skipping live test.");
            return;
        }

        System.out.println("Running testDeviceOnly...");
        RtlSdrDevice dev = new RtlSdrDevice();
        dev.open(0);
        dev.setSampleRate(2048000);
        dev.setCenterFreq(100000000);
        dev.startStreaming();
        Thread.sleep(1000);
        System.out.println("Closing device in testDeviceOnly...");
        dev.close();
        System.out.println("testDeviceOnly closed dev successfully!");

        System.out.println("Opening device 2nd time in testDeviceOnly...");
        dev.open(0);
        System.out.println("2nd open in testDeviceOnly succeeded!");
        dev.close();
        System.out.println("testDeviceOnly PASSED!");
    }

    @Test
    public void testStartStopRestart() throws Exception {
        if (!isDeviceAvailable()) {
            System.out.println("No RTL-SDR device attached or native library missing; skipping live test.");
            return;
        }

        RtlSdrConfig config = new RtlSdrConfig();
        RtlSdrDriver driver = new RtlSdrDriver();
        driver.setConfiguration(config);
        driver.init();

        System.out.println("Starting driver (1st time)...");
        driver.start();
        Thread.sleep(1000);

        System.out.println("Stopping driver (1st time)...");
        driver.stop();
        System.out.println("Driver stopped. Waiting 500ms...");
        Thread.sleep(500);

        System.out.println("Starting driver (2nd time)...");
        driver.start();
        Thread.sleep(1000);

        System.out.println("Stopping driver (2nd time)...");
        driver.stop();
        System.out.println("Driver start-stop-restart test PASSED!");
    }
}
