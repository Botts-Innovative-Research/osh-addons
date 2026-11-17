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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * On-demand audio recorder for demodulated RTL-SDR audio streams.
 * Records canonical 16-bit PCM WAV files (48 kHz mono or stereo).
 */
public class RtlSdrAudioRecorder implements RtlSdrAudioDemodulator.AudioFrameListener {
    private static final Logger log = LoggerFactory.getLogger(RtlSdrAudioRecorder.class);

    private static final int SAMPLE_RATE = 48000;
    private static final int BITS_PER_SAMPLE = 16;

    private volatile boolean recording = false;
    private File currentFile;
    private FileOutputStream fileOut;
    private int totalAudioBytes = 0;
    private int recordingChannels = 1;
    private long recordingStartTime = 0;

    public interface RecordingStatusListener {
        void onRecordingStatusChanged(boolean isRecording, String filePath, long durationMs);
    }

    private RecordingStatusListener statusListener;

    public void setStatusListener(RecordingStatusListener listener) {
        this.statusListener = listener;
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    public synchronized long getRecordingDurationMs() {
        if (!recording) return 0;
        return System.currentTimeMillis() - recordingStartTime;
    }

    public synchronized String getCurrentFilePath() {
        return currentFile != null ? currentFile.getAbsolutePath() : null;
    }

    /**
     * Starts recording to a timestamped WAV file in targetDir.
     *
     * @param targetDir Directory where recording is stored
     * @param freqMHz Current tuned frequency in MHz
     * @param modeName Current audio demodulation mode name
     * @return Absolute path of created WAV file
     * @throws IOException If file creation fails
     */
    public synchronized String startRecording(String targetDir, double freqMHz, String modeName) throws IOException {
        if (recording) {
            stopRecording();
        }

        File dir = new File(targetDir != null && !targetDir.isBlank() ? targetDir : "recordings/rtlsdr");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create recordings directory: " + dir.getAbsolutePath());
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String cleanMode = (modeName != null ? modeName : "AUDIO").toUpperCase();
        String filename = String.format(Locale.US, "rtlsdr_%.3fMHz_%s_%s.wav", freqMHz, cleanMode, timestamp);
        currentFile = new File(dir, filename);

        fileOut = new FileOutputStream(currentFile);
        totalAudioBytes = 0;
        recordingStartTime = System.currentTimeMillis();

        // Write placeholder 44-byte WAV header (will finalize chunk sizes on stop)
        byte[] placeholderHeader = new byte[44];
        fileOut.write(placeholderHeader);
        fileOut.flush();

        recording = true;
        log.info("Started audio recording: {}", currentFile.getAbsolutePath());

        if (statusListener != null) {
            statusListener.onRecordingStatusChanged(true, currentFile.getAbsolutePath(), 0);
        }

        return currentFile.getAbsolutePath();
    }

    /**
     * Stops current recording and writes final WAV header chunk sizes.
     *
     * @return Path to recorded file, or null if was not recording
     */
    public synchronized String stopRecording() {
        if (!recording) {
            return null;
        }

        recording = false;
        long durationMs = System.currentTimeMillis() - recordingStartTime;
        String recordedPath = currentFile != null ? currentFile.getAbsolutePath() : null;

        try {
            if (fileOut != null) {
                fileOut.flush();
                fileOut.close();
                fileOut = null;
            }

            if (currentFile != null && currentFile.exists()) {
                writeWavHeader(currentFile, totalAudioBytes, recordingChannels, SAMPLE_RATE);
                log.info("Completed audio recording ({} bytes, {} ms): {}", totalAudioBytes, durationMs, recordedPath);
            }
        } catch (IOException e) {
            log.error("Error finalizing WAV header for {}: {}", recordedPath, e.getMessage());
        }

        if (statusListener != null) {
            statusListener.onRecordingStatusChanged(false, recordedPath, durationMs);
        }

        return recordedPath;
    }

    @Override
    public synchronized void onAudioChunk(byte[] pcm16Data, int numSamples, String base64Pcm, int channels) {
        if (!recording || fileOut == null || pcm16Data == null || pcm16Data.length == 0) {
            return;
        }

        try {
            this.recordingChannels = channels;
            fileOut.write(pcm16Data);
            totalAudioBytes += pcm16Data.length;
        } catch (IOException e) {
            log.error("Error writing audio chunk to {}: {}", currentFile != null ? currentFile.getName() : "recording", e.getMessage());
            stopRecording();
        }
    }

    /**
     * Rewrites standard 44-byte canonical RIFF/WAVE header with exact byte sizes.
     */
    private void writeWavHeader(File file, int audioDataBytes, int channels, int sampleRate) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(0);

            ByteBuffer buf = ByteBuffer.allocate(44);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            int byteRate = sampleRate * channels * (BITS_PER_SAMPLE / 8);
            short blockAlign = (short) (channels * (BITS_PER_SAMPLE / 8));
            int riffChunkSize = 36 + audioDataBytes;

            // RIFF header
            buf.put(new byte[]{'R', 'I', 'F', 'F'});
            buf.putInt(riffChunkSize);
            buf.put(new byte[]{'W', 'A', 'V', 'E'});

            // fmt subchunk
            buf.put(new byte[]{'f', 'm', 't', ' '});
            buf.putInt(16); // Subchunk1Size for PCM
            buf.putShort((short) 1); // AudioFormat: 1 = PCM
            buf.putShort((short) channels);
            buf.putInt(sampleRate);
            buf.putInt(byteRate);
            buf.putShort(blockAlign);
            buf.putShort((short) BITS_PER_SAMPLE);

            // data subchunk
            buf.put(new byte[]{'d', 'a', 't', 'a'});
            buf.putInt(audioDataBytes);

            raf.write(buf.array());
        }
    }
}
