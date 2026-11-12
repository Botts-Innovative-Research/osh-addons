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

public class RtlSdrConstants {
    // RF Receiver Properties / Control Keys
    public static final String CENTER_FREQ = "center_freq";
    public static final String GAIN = "gain";
    public static final String AUTO_GAIN = "auto_gain";
    public static final String SAMPLE_RATE = "sample_rate";
    public static final String AGC_MODE = "agc_mode";
    public static final String PPM_CORRECTION = "ppm_correction";
    public static final String DIRECT_SAMPLING = "direct_sampling";

    // Supported Gain Values for RTL-SDR tuners (dB)
    public static final String[] GAIN_VALUES_DB = new String[] {
            "0.0", "0.9", "1.4", "2.7", "3.7", "7.7", "8.7", "12.5",
            "14.4", "15.7", "16.6", "19.7", "20.7", "22.9", "25.4",
            "28.0", "29.7", "32.8", "33.8", "36.4", "37.2", "38.6",
            "40.2", "42.1", "43.4", "43.9", "44.5", "48.0", "49.6"
    };
}
