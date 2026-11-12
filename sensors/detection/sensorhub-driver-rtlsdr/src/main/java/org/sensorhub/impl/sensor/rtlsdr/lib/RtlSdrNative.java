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

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.File;

/**
 * JNA wrapper interface for librtlsdr C library functions.
 */
public interface RtlSdrNative extends Library {
    RtlSdrNative INSTANCE = loadLibrary();

    private static RtlSdrNative loadLibrary() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // First attempt to unpack and load self-contained DLLs bundled in JAR resources
            try {
                File libusb = Native.extractFromResourcePath("/win32-x86-64/libusb-1.0.dll");
                File rtlsdr = Native.extractFromResourcePath("/win32-x86-64/rtlsdr.dll");
                if (libusb != null && libusb.exists() && rtlsdr != null && rtlsdr.exists()) {
                    File dir = libusb.getParentFile();
                    NativeLibrary.addSearchPath("libusb-1.0", dir.getAbsolutePath());
                    NativeLibrary.addSearchPath("rtlsdr", dir.getAbsolutePath());
                    try {
                        NativeLibrary.getInstance("kernel32").getFunction("SetDllDirectoryA").invokeVoid(new Object[]{dir.getAbsolutePath()});
                    } catch (Throwable ignored) {
                    }
                    try {
                        System.load(libusb.getAbsolutePath());
                    } catch (Throwable t) {
                        NativeLibrary.getInstance("libusb-1.0");
                    }
                    return Native.load(rtlsdr.getAbsolutePath(), RtlSdrNative.class);
                }
            } catch (Throwable ignored) {
            }

            // Fallback: search common host installation paths
            String[] winPaths = new String[] {
                    "C:\\Program Files\\CubicSDR",
                    "C:\\Program Files (x86)\\CubicSDR",
                    "C:\\Program Files\\PothosSDR\\bin",
                    "C:\\rtl-sdr"
            };
            for (String p : winPaths) {
                if (new File(p, "rtlsdr.dll").exists()) {
                    NativeLibrary.addSearchPath("rtlsdr", p);
                    NativeLibrary.addSearchPath("libusb-1.0", p);
                    try {
                        NativeLibrary.getInstance("kernel32").getFunction("SetDllDirectoryA").invokeVoid(new Object[]{p});
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return Native.load("rtlsdr", RtlSdrNative.class);
    }

    /**
     * Callback interface invoked by rtlsdr_read_async when sample buffers arrive.
     */
    interface RtlSdrReadAsyncCallback extends Callback {
        void invoke(Pointer buf, int len, Pointer ctx);
    }

    // Device enumeration and information
    int rtlsdr_get_device_count();

    String rtlsdr_get_device_name(int index);

    int rtlsdr_get_device_usb_strings(int index, byte[] manufact, byte[] product, byte[] serial);

    int rtlsdr_get_index_by_serial(String serial);

    // Lifecycle
    int rtlsdr_open(PointerByReference dev, int index);

    int rtlsdr_close(Pointer dev);

    // Configuration
    int rtlsdr_set_center_freq(Pointer dev, int freq);

    int rtlsdr_get_center_freq(Pointer dev);

    int rtlsdr_set_sample_rate(Pointer dev, int rate);

    int rtlsdr_get_sample_rate(Pointer dev);

    int rtlsdr_set_tuner_gain_mode(Pointer dev, int manual);

    int rtlsdr_set_tuner_gain(Pointer dev, int gain);

    int rtlsdr_get_tuner_gain(Pointer dev);

    int rtlsdr_get_tuner_gains(Pointer dev, int[] gains);

    int rtlsdr_set_freq_correction(Pointer dev, int ppm);

    int rtlsdr_get_freq_correction(Pointer dev);

    int rtlsdr_set_direct_sampling(Pointer dev, int on);

    int rtlsdr_set_offset_tuning(Pointer dev, int on);

    int rtlsdr_set_agc_mode(Pointer dev, int on);

    int rtlsdr_set_bias_tee(Pointer dev, int on);

    // Streaming
    int rtlsdr_reset_buffer(Pointer dev);

    int rtlsdr_read_sync(Pointer dev, byte[] buf, int len, IntByReference n_read);

    int rtlsdr_read_async(Pointer dev, RtlSdrReadAsyncCallback cb, Pointer ctx, int buf_num, int buf_len);

    int rtlsdr_cancel_async(Pointer dev);
}
