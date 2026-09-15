/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.gopro.config;

import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.impl.sensor.ffmpeg.config.FFMPEGConfig;

/**
 * Configuration settings for the GoPro driver exposed via the OpenSensorHub Admin panel.
 * Adds the GPMF telemetry options on top of the FFmpeg connection settings.
 */
public class GoProConfig extends FFMPEGConfig {
    public GoProConfig() {
        serialNumber = "gopro001";
    }

    /**
     * Whether to publish an output for the GPMF telemetry track when the source carries one.
     */
    @DisplayInfo(label = "Publish GPMF Telemetry", desc = "Create an output for the GoPro GPMF telemetry track when the source carries one.")
    public boolean publishGpmf = true;

    /**
     * Whether to treat data streams that carry no GoPro marker as GPMF.
     * <p>
     * GoPro MP4 recordings tag their telemetry track as {@code gpmd}, but some live streams and re-muxed
     * files expose it as an untagged private data stream. Enable this to publish those as GPMF anyway.
     * Note that GoPro MP4s also carry {@code tmcd} (timecode) and {@code fdsc} (camera info) data tracks,
     * which will then be published as GPMF as well.
     */
    @DisplayInfo(label = "Assume Untagged Data Streams Are GPMF", desc = "Treat data streams with no GoPro marker (no 'gpmd' codec tag and no 'GoPro MET' handler name) as GPMF telemetry. Needed for some live streams and re-muxed files that drop the marker.")
    public boolean assumeUntaggedDataIsGpmf = false;

    /**
     * Whether to publish a separate position output for the GPS fixes in the GPMF telemetry.
     */
    @DisplayInfo(label = "Publish GPS Position", desc = "Create a location output for the GPS fixes in the GPMF telemetry, in addition to reporting them in the telemetry output.")
    public boolean publishGps = true;

    /**
     * Whether to drop position records the receiver had no lock for.
     */
    @DisplayInfo(label = "Require GPS Fix", desc = "Only publish position records the receiver had a lock for. A GoPro reports coordinates even with no fix, and those coordinates are meaningless, so leaving this unchecked will scatter the track with bad positions. Unaffected either way, the telemetry output always reports the GPS streams as recorded.")
    public boolean requireGpsFix = true;

    /**
     * The reference surface the camera's GPS altitude readings are measured from, used only for cameras
     * that do not declare it themselves.
     */
    @DisplayInfo(label = "GPS Altitude Datum", desc = "Reference surface the camera measures altitude from, used only when the camera does not declare it. The HERO8, MAX, and newer declare it in the telemetry; the HERO7 and earlier do not and report height above the WGS84 ellipsoid. The driver publishes the camera's readings unchanged and uses this only to describe them correctly.")
    public AltitudeDatum altitudeDatum = AltitudeDatum.MEAN_SEA_LEVEL;
}
