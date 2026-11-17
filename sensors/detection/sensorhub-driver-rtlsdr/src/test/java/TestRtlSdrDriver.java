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
import org.sensorhub.impl.sensor.rtlsdr.dsp.RtlSdrAudioRecorder;
import org.sensorhub.impl.sensor.rtlsdr.lib.RtlSdrDevice;
import org.sensorhub.impl.sensor.rtlsdr.outputs.RtlSdrOutputSpectrum;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

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
        assertEquals(5, driver.getObservationOutputs().size());

        IStreamingDataInterface spectrumOutput = driver.getObservationOutputs().get("rtlsdr_spectrum");
        assertNotNull("Spectrum output should be registered", spectrumOutput);

        IStreamingDataInterface settingsOutput = driver.getObservationOutputs().get("rtlsdr_settings");
        assertNotNull("Settings output should be registered", settingsOutput);

        IStreamingDataInterface audioOutput = driver.getObservationOutputs().get("audioOutput");
        assertNotNull("Audio output should be registered", audioOutput);

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
        demod.addListener((pcm, numSamples, b64, channels) -> chunkCount[0]++);

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
    public void testWfmStereoDemodulation() {
        org.sensorhub.impl.sensor.rtlsdr.dsp.RtlSdrAudioDemodulator demod = new org.sensorhub.impl.sensor.rtlsdr.dsp.RtlSdrAudioDemodulator();
        final int[] stereoChunks = new int[1];
        final double[] leftEnergy = new double[1];
        final double[] rightEnergy = new double[1];

        demod.addListener((pcm, numSamples, b64, channels) -> {
            if (channels == 2) {
                stereoChunks[0]++;
                for (int i = 0; i < numSamples; i++) {
                    short l = (short) ((pcm[i * 4] & 0xFF) | (pcm[i * 4 + 1] << 8));
                    short r = (short) ((pcm[i * 4 + 2] & 0xFF) | (pcm[i * 4 + 3] << 8));
                    if (stereoChunks[0] > 2) {
                        leftEnergy[0] += (double) l * l;
                        rightEnergy[0] += (double) r * r;
                    }
                }
            }
        });

        // Generate 0.3 seconds of synthetic FM Stereo MPX signal at 2.048 MSPS:
        // Left channel has 1 kHz tone, Right channel is silent.
        int sampleRate = 2048000;
        int numSamples = (int) (sampleRate * 0.3);
        byte[] iqData = new byte[numSamples * 2];
        double phase = 0.0;
        double kDev = 2.0 * Math.PI * 75000.0 / sampleRate;

        for (int n = 0; n < numSamples; n++) {
            double t = (double) n / sampleRate;
            double leftAudio = 0.4 * Math.cos(2.0 * Math.PI * 1000.0 * t);
            double rightAudio = 0.0; // silent right channel

            double m = leftAudio + rightAudio;
            double s = leftAudio - rightAudio;
            double pilot = 0.10 * Math.sin(2.0 * Math.PI * 19000.0 * t);
            double subcarrier = s * Math.sin(2.0 * Math.PI * 38000.0 * t);

            double mpx = m + pilot + subcarrier;
            phase += kDev * mpx;

            iqData[n * 2] = (byte) Math.round(127.5 + 100.0 * Math.cos(phase));
            iqData[n * 2 + 1] = (byte) Math.round(127.5 + 100.0 * Math.sin(phase));
        }

        demod.setMode(RtlSdrConfig.AudioDemodMode.WFM_STEREO);
        demod.processSamples(iqData, iqData.length, sampleRate);

        assertTrue("Stereo chunks must be produced", stereoChunks[0] > 0);
        assertTrue("Stereo pilot PLL must lock", demod.isPilotLocked());
        assertTrue("Left channel must have much stronger signal than Right (stereo separation)",
                leftEnergy[0] > rightEnergy[0] * 3.0);
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
            if (RtlSdrDevice.getDeviceCount() <= 0) return false;
            RtlSdrDevice testDev = new RtlSdrDevice();
            try {
                testDev.open(0);
                testDev.close();
                return true;
            } catch (Throwable t) {
                System.out.println("RTL-SDR device present but busy or unavailable: " + t.getMessage());
                return false;
            }
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

    @Test
    public void testAudioRecorder() throws Exception {
        RtlSdrAudioRecorder recorder = new RtlSdrAudioRecorder();
        assertFalse(recorder.isRecording());

        String targetDir = "build/test-recordings";
        String filePath = recorder.startRecording(targetDir, 101.1, "WFM_STEREO");
        assertTrue(recorder.isRecording());
        assertNotNull(filePath);
        assertEquals(filePath, recorder.getCurrentFilePath());

        File recFile = new File(filePath);
        assertTrue(recFile.exists());

        // Generate synthetic stereo audio frame: 480 stereo samples (10ms of audio)
        // 480 * 2 channels * 2 bytes/sample = 1920 bytes
        int numSamples = 480;
        byte[] pcmBytes = new byte[numSamples * 4];
        for (int i = 0; i < numSamples; i++) {
            short left = (short) (10000 * Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0));
            short right = (short) (10000 * Math.cos(2.0 * Math.PI * 880.0 * i / 48000.0));
            pcmBytes[i * 4] = (byte) (left & 0xFF);
            pcmBytes[i * 4 + 1] = (byte) ((left >> 8) & 0xFF);
            pcmBytes[i * 4 + 2] = (byte) (right & 0xFF);
            pcmBytes[i * 4 + 3] = (byte) ((right >> 8) & 0xFF);
        }

        // Deliver frame to recorder
        recorder.onAudioChunk(pcmBytes, numSamples, null, 2);

        String stoppedPath = recorder.stopRecording();
        assertFalse(recorder.isRecording());
        assertEquals(filePath, stoppedPath);

        // Verify WAV header contents
        assertEquals(44 + pcmBytes.length, recFile.length());
        byte[] header = new byte[44];
        try (FileInputStream fis = new FileInputStream(recFile)) {
            int read = fis.read(header);
            assertEquals(44, read);
        }

        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        // RIFF
        byte[] riffTag = new byte[4];
        bb.get(riffTag);
        assertEquals("RIFF", new String(riffTag, StandardCharsets.US_ASCII));
        int riffLength = bb.getInt();
        assertEquals(36 + pcmBytes.length, riffLength);

        // WAVE
        byte[] waveTag = new byte[4];
        bb.get(waveTag);
        assertEquals("WAVE", new String(waveTag, StandardCharsets.US_ASCII));

        // fmt 
        byte[] fmtTag = new byte[4];
        bb.get(fmtTag);
        assertEquals("fmt ", new String(fmtTag, StandardCharsets.US_ASCII));
        assertEquals(16, bb.getInt()); // Subchunk1Size
        assertEquals(1, bb.getShort()); // AudioFormat (1 = PCM)
        assertEquals(2, bb.getShort()); // NumChannels (2 = Stereo)
        assertEquals(48000, bb.getInt()); // SampleRate
        assertEquals(48000 * 2 * 2, bb.getInt()); // ByteRate
        assertEquals(4, bb.getShort()); // BlockAlign (channels * 2)
        assertEquals(16, bb.getShort()); // BitsPerSample

        // data
        byte[] dataTag = new byte[4];
        bb.get(dataTag);
        assertEquals("data", new String(dataTag, StandardCharsets.US_ASCII));
        assertEquals(pcmBytes.length, bb.getInt());

        // Clean up file
        recFile.delete();
        if (recFile.getParentFile() != null) {
            recFile.getParentFile().delete();
        }
    }

    @Test
    public void testDriverAudioRecordingToggle() throws Exception {
        RtlSdrConfig config = new RtlSdrConfig();
        config.audioRecordingsDir = "build/test-recordings-driver";
        config.centerFrequency = 98.5;
        config.audioMode = RtlSdrConfig.AudioDemodMode.WFM;

        RtlSdrDriver driver = new RtlSdrDriver();
        driver.setConfiguration(config);
        driver.init();

        assertFalse(driver.isRecordingAudio());
        String path = driver.setAudioRecording(true);
        assertTrue(driver.isRecordingAudio());
        assertNotNull(path);

        File f = new File(path);
        assertTrue(f.exists());

        String stoppedPath = driver.setAudioRecording(false);
        assertFalse(driver.isRecordingAudio());
        assertEquals(path, stoppedPath);

        f.delete();
        if (f.getParentFile() != null) {
            f.getParentFile().delete();
        }
    }

    @Test
    public void testDriverSensorDescription() throws Exception {
        RtlSdrConfig config = new RtlSdrConfig();
        config.serialNumber = "TEST-SN-12345";
        RtlSdrDriver driver = new RtlSdrDriver();
        driver.setConfiguration(config);
        driver.init();

        net.opengis.sensorml.v20.AbstractProcess desc = driver.getCurrentDescription();
        assertNotNull("Sensor description should not be null", desc);
        assertTrue(desc.isSetDescription());
        assertTrue(desc.getDescription().contains("RTL-SDR"));
        assertNotNull(desc.getIdentificationList());
        assertTrue(desc.getIdentificationList().size() > 0);
        assertNotNull(desc.getClassificationList());
        assertTrue(desc.getClassificationList().size() > 0);
    }

    @Test
    public void testAudioOutputStreaming() throws Exception {
        RtlSdrConfig config = new RtlSdrConfig();
        config.audioMode = RtlSdrConfig.AudioDemodMode.WFM;
        RtlSdrDriver driver = new RtlSdrDriver();
        driver.setConfiguration(config);
        driver.init();

        org.sensorhub.impl.sensor.rtlsdr.outputs.RtlSdrOutputAudio audioOutput = driver.getOutputAudio();
        assertNotNull(audioOutput);
        assertNotNull(audioOutput.getRecordDescription());
        assertNotNull(audioOutput.getRecommendedEncoding());
        assertEquals("audioOutput", audioOutput.getName());

        final boolean[] receivedEvent = new boolean[1];
        audioOutput.registerListener((event) -> {
            if (event instanceof org.sensorhub.api.data.DataEvent) {
                receivedEvent[0] = true;
            }
        });

        // Synthetic 48 kHz mono frame: 240 samples = 480 bytes
        int numSamples = 240;
        byte[] pcmBytes = new byte[numSamples * 2];
        for (int i = 0; i < numSamples; i++) {
            short s = (short) (8000 * Math.sin(2.0 * Math.PI * 1000.0 * i / 48000.0));
            pcmBytes[i * 2] = (byte) (s & 0xFF);
            pcmBytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }

        audioOutput.onAudioChunk(pcmBytes, numSamples, null, 1);
        // Driver is not started, so onAudioChunk should safely drop or ignore without throwing
        assertFalse(receivedEvent[0]);

        // Now test audioOutput record structure
        assertNotNull(audioOutput.getRecordDescription().getComponent("samples"));
        assertNotNull(audioOutput.getRecordDescription().getComponent("sampleRate"));
        assertNotNull(audioOutput.getRecordDescription().getComponent("sampleTime"));
    }
}
