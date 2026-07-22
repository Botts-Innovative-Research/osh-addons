/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
The Initial Developer is Botts Innovative Research Inc.. Portions created by the Initial
Developer are Copyright (C) 2014 the Initial Developer. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor.esprit;

import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.sensor.PositionConfig;
import org.sensorhub.api.sensor.SensorConfig;
import org.sensorhub.api.sensor.PositionConfig.EulerOrientation;
import org.sensorhub.api.sensor.PositionConfig.LLALocation;
import org.sensorhub.impl.comm.HTTPConfig;
import org.sensorhub.impl.comm.RobustIPConnectionConfig;
import org.sensorhub.impl.sensor.rtpcam.RTSPConfig;
import org.sensorhub.impl.sensor.videocam.ptz.PtzConfig;


/**
 * <p>
 * Implementation of sensor interface for generic Esprit Cameras using IP
 * protocol. This particular class stores configuration parameters.
 * </p>
 * 
 * @author Mike Botts
 * @since October 30, 2014
 */
public class EspritCameraConfig extends SensorConfig {

    @DisplayInfo(label="UID Extension", desc="ID to be attached to tail of this system's UID. Driver must be reinitialized to be configured after start")
    public String uidExtension = "";

    @DisplayInfo(label="HTTP", desc="HTTP configuration")
    public HTTPConfig http = new HTTPConfig();
    
    /**
     * RTP/RTSP configuration (Remote host is obtained from HTTP configuration)
     *
     * <p> {@code localUdpPort} of {@link RTSPConfig} is no longer honored by
     * this driver. FFmpeg chooses its own client ports during RTSP SETUP (or
     * uses TCP interleaved via {@code -rtsp_transport tcp}). The field is
     * retained only for backward-compatibility </p>
     */
    
    @DisplayInfo(label="Connection Options")
    public RobustIPConnectionConfig connection = new RobustIPConnectionConfig();

    @DisplayInfo(label="Resolution")
    public VideoResolution resolution = VideoResolution.HIGH;

    @DisplayInfo(label = "RTSP URL Override")
    public String rtspUrlOverride = null;
    
    @DisplayInfo(label="PTZ", desc="Pan-Tilt-Zoom configuration")
    public PtzConfig ptz = new PtzConfig();
    
    @DisplayInfo(desc="Camera geographic position")
    public PositionConfig position = new PositionConfig();



    @Override
    public LLALocation getLocation()
    {
        return position.location;
    }

    @Override
    public EulerOrientation getOrientation()
    {
        return position.orientation;
    }



    public static enum VideoResolution {
        HIGH("defaultPrimary"), MEDIUM("defaultSecondary"), LOW("defaultTertiary");

        private final String apiString;

        public String getApiString() {
            return apiString;
        }

        VideoResolution(String apiString) {
            this.apiString = apiString;
        }
    }
}
