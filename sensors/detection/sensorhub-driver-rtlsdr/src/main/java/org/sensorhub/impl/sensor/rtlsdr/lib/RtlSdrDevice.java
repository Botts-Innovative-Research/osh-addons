/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.rtlsdr.lib;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe Java controller for an RTL2832-based USB SDR device.
 * Bridges application logic to native librtlsdr via {@link RtlSdrNative}.
 */
public class RtlSdrDevice {
    private static final Logger logger = LoggerFactory.getLogger(RtlSdrDevice.class);

    public static final int DEFAULT_BUF_NUM = 15;
    public static final int DEFAULT_BUF_LEN = 16 * 16384; // 262,144 bytes standard buffer (128k IQ samples)

    private final RtlSdrNative nativeLib;
    private Pointer devPointer = null;
    private int currentDeviceIndex = -1;
    private Thread asyncThread = null;
    private volatile boolean streaming = false;

    // Strong reference to JNA callback to prevent garbage collection while async streaming
    private RtlSdrNative.RtlSdrReadAsyncCallback asyncCallback = null;
    private final AtomicLong totalBytesReceived = new AtomicLong();

    private final List<SampleListener> listeners = new CopyOnWriteArrayList<>();

    public interface SampleListener {
        void onSamples(byte[] buffer, int len);
    }

    public RtlSdrDevice() {
        this.nativeLib = RtlSdrNative.INSTANCE;
    }

    public static int getDeviceCount() {
        return RtlSdrNative.INSTANCE.rtlsdr_get_device_count();
    }

    public static String getDeviceName(int index) {
        return RtlSdrNative.INSTANCE.rtlsdr_get_device_name(index);
    }

    public synchronized void open(int index) throws IOException {
        if (devPointer != null) {
            if (currentDeviceIndex == index) {
                logger.debug("RTL-SDR device index {} is already open, reusing handle", index);
                return;
            }
            close();
        }

        PointerByReference devRef = new PointerByReference();
        int rc = -1;

        // Retry up to 5 times with backoff in case the OS USB stack is still releasing the WinUSB interface
        for (int attempt = 1; attempt <= 5; attempt++) {
            rc = nativeLib.rtlsdr_open(devRef, index);
            if (rc == 0) {
                break;
            }
            logger.warn("rtlsdr_open attempt {} returned {}, retrying in 250ms...", attempt, rc);
            if (attempt < 5) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (rc < 0) {
            throw new IOException("Failed to open RTL-SDR device index " + index + " (code: " + rc + ")");
        }
        devPointer = devRef.getValue();
        currentDeviceIndex = index;
        logger.info("Successfully opened RTL-SDR device index: {}", index);
    }

    public synchronized void close() {
        if (streaming || asyncThread != null) {
            stopStreaming();
        }
        if (devPointer != null) {
            try {
                // Ensure native async read loop has fully returned before closing device
                if (asyncThread != null) {
                    try {
                        asyncThread.join(5000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    asyncThread = null;
                }
                // Settle delay before native close to ensure cancelled async URB completion routines have completely finished
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                int rc = nativeLib.rtlsdr_close(devPointer);
                if (rc < 0) {
                    logger.warn("RTL-SDR device close returned code: {}", rc);
                } else {
                    logger.info("Closed RTL-SDR device");
                }
            } catch (Throwable t) {
                logger.warn("Error closing RTL-SDR device: {}", t.getMessage());
            } finally {
                devPointer = null;
                currentDeviceIndex = -1;
                asyncCallback = null;
            }
            try {
                // Post-close delay for Windows OS USB stack to release WinUSB interface
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized boolean isOpen() {
        return devPointer != null;
    }

    public synchronized int getCurrentDeviceIndex() {
        return currentDeviceIndex;
    }

    public synchronized void setCenterFreq(long freqHz) throws IOException {
        ensureOpen();
        int rc = nativeLib.rtlsdr_set_center_freq(devPointer, (int) freqHz);
        if (rc < 0) {
            throw new IOException("Failed to set center frequency (code: " + rc + ")");
        }
    }

    public synchronized long getCenterFreq() throws IOException {
        ensureOpen();
        int freq = nativeLib.rtlsdr_get_center_freq(devPointer);
        if (freq == 0) {
            throw new IOException("Failed to get center frequency");
        }
        return Integer.toUnsignedLong(freq);
    }

    public synchronized void setSampleRate(int rateHz) throws IOException {
        ensureOpen();
        int rc = nativeLib.rtlsdr_set_sample_rate(devPointer, rateHz);
        if (rc < 0) {
            throw new IOException("Failed to set sample rate (code: " + rc + ")");
        }
    }

    public synchronized int getSampleRate() throws IOException {
        ensureOpen();
        int rate = nativeLib.rtlsdr_get_sample_rate(devPointer);
        if (rate == 0) {
            throw new IOException("Failed to get sample rate");
        }
        return rate;
    }

    public synchronized void setTunerGainMode(boolean manual) throws IOException {
        ensureOpen();
        int rc = nativeLib.rtlsdr_set_tuner_gain_mode(devPointer, manual ? 1 : 0);
        if (rc < 0) {
            throw new IOException("Failed to set tuner gain mode (code: " + rc + ")");
        }
    }

    public synchronized void setTunerGain(int gainTenthsDb) throws IOException {
        ensureOpen();
        int rc = nativeLib.rtlsdr_set_tuner_gain(devPointer, gainTenthsDb);
        if (rc < 0) {
            throw new IOException("Failed to set tuner gain (code: " + rc + ")");
        }
    }

    public synchronized int getTunerGain() throws IOException {
        ensureOpen();
        return nativeLib.rtlsdr_get_tuner_gain(devPointer);
    }

    public synchronized int[] getTunerGains() throws IOException {
        ensureOpen();
        int count = nativeLib.rtlsdr_get_tuner_gains(devPointer, null);
        if (count <= 0) {
            return new int[0];
        }
        int[] gains = new int[count];
        nativeLib.rtlsdr_get_tuner_gains(devPointer, gains);
        return gains;
    }

    public synchronized void setAgcMode(boolean digitalAgc) throws IOException {
        ensureOpen();
        int rc = nativeLib.rtlsdr_set_agc_mode(devPointer, digitalAgc ? 1 : 0);
        if (rc < 0) {
            throw new IOException("Failed to set digital AGC mode (code: " + rc + ")");
        }
    }

    public synchronized void setFreqCorrection(int ppm) throws IOException {
        ensureOpen();
        int rc = nativeLib.rtlsdr_set_freq_correction(devPointer, ppm);
        if (rc < 0) {
            throw new IOException("Failed to set frequency correction (code: " + rc + ")");
        }
    }

    public synchronized int getFreqCorrection() throws IOException {
        ensureOpen();
        return nativeLib.rtlsdr_get_freq_correction(devPointer);
    }

    public synchronized void setDirectSampling(int mode) throws IOException {
        ensureOpen();
        int rc = nativeLib.rtlsdr_set_direct_sampling(devPointer, mode);
        if (rc < 0) {
            throw new IOException("Failed to set direct sampling mode (code: " + rc + ")");
        }
    }

    public synchronized void setBiasTee(boolean enable) throws IOException {
        ensureOpen();
        int rc = nativeLib.rtlsdr_set_bias_tee(devPointer, enable ? 1 : 0);
        if (rc < 0) {
            throw new IOException("Failed to set bias tee (code: " + rc + ")");
        }
    }

    public void addSampleListener(SampleListener listener) {
        listeners.add(listener);
    }

    public void removeSampleListener(SampleListener listener) {
        listeners.remove(listener);
    }

    public void clearSampleListeners() {
        listeners.clear();
    }

    public synchronized void startStreaming() throws IOException {
        startStreaming(DEFAULT_BUF_NUM, DEFAULT_BUF_LEN);
    }

    public synchronized void startStreaming(int bufNum, int bufLen) throws IOException {
        ensureOpen();
        if (streaming) {
            return;
        }

        nativeLib.rtlsdr_reset_buffer(devPointer);
        streaming = true;
        totalBytesReceived.set(0);

        this.asyncCallback = (buf, len, ctx) -> {
            if (!streaming || buf == null || len <= 0) {
                return;
            }
            long total = totalBytesReceived.addAndGet(len);
            if (total <= len || total % (len * 20) < len) {
                logger.debug("Received RTL-SDR sample buffer: len={}, totalBytes={}", len, total);
            }

            byte[] byteArray = buf.getByteArray(0, len);
            for (SampleListener listener : listeners) {
                try {
                    listener.onSamples(byteArray, len);
                } catch (Exception e) {
                    logger.error("Error in RTL-SDR sample listener", e);
                }
            }
        };

        asyncThread = new Thread(() -> {
            logger.info("Starting RTL-SDR async read streaming (bufNum={}, bufLen={})...", bufNum, bufLen);
            try {
                int rc = nativeLib.rtlsdr_read_async(devPointer, asyncCallback, null, bufNum, bufLen);
                if (rc == -5) {
                    logger.debug("RTL-SDR async read ended normally on cancel (code -5)");
                } else {
                    logger.info("RTL-SDR async read ended with code: {}", rc);
                }
            } catch (Throwable t) {
                if (streaming) {
                    logger.error("Error during RTL-SDR async read", t);
                }
            } finally {
                streaming = false;
            }
        }, "RtlSdr-AsyncReader");
        asyncThread.setDaemon(true);
        asyncThread.start();
    }

    public synchronized void stopStreaming() {
        streaming = false;
        listeners.clear(); // Immediately stop sample dispatching so asyncThread exits quickly
        if (devPointer != null) {
            try {
                int cancelRc = nativeLib.rtlsdr_cancel_async(devPointer);
                logger.debug("rtlsdr_cancel_async returned: {}", cancelRc);
            } catch (Throwable t) {
                logger.warn("Error canceling RTL-SDR async read: {}", t.getMessage());
            }
        }
        if (asyncThread != null) {
            try {
                asyncThread.join(6000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            asyncThread = null;
        }
        try {
            // Windows WinUSB settling delay to let cancelled asynchronous URB completion routines drain
            Thread.sleep(600);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // asyncCallback is kept pinned until close() to avoid JNA thunk deallocation during teardown
    }

    public boolean isStreaming() {
        return streaming;
    }

    public synchronized int readSync(byte[] buffer, int len) throws IOException {
        ensureOpen();
        IntByReference nRead = new IntByReference();
        int rc = nativeLib.rtlsdr_read_sync(devPointer, buffer, len, nRead);
        if (rc < 0) {
            throw new IOException("Failed sync read from RTL-SDR device (code: " + rc + ")");
        }
        return nRead.getValue();
    }

    public synchronized void resetBuffer() throws IOException {
        ensureOpen();
        int rc = nativeLib.rtlsdr_reset_buffer(devPointer);
        if (rc < 0) {
            throw new IOException("Failed to reset RTL-SDR buffer (code: " + rc + ")");
        }
    }

    private void ensureOpen() throws IOException {
        if (devPointer == null) {
            throw new IOException("RTL-SDR device is not open");
        }
    }
}
