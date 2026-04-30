package org.sensorhub.impl.sensor.rtmp.config;

import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.sensor.PositionConfig;
import org.sensorhub.api.sensor.SensorConfig;

public class RtmpConfig extends SensorConfig {
    @DisplayInfo.Required
    @DisplayInfo(label = "Video Stream ID", desc = "Serial number or unique identifier for video stream.")
    public String serialNumber = "video001";

    @DisplayInfo.Required
    @DisplayInfo(label = "Connection", desc = "Configuration options for source of RTMP.")
    public ConnectionConfig connectionConfig = new ConnectionConfig();

    /**
     * Configuration options for the location and orientation of the sensor.
     */
    @DisplayInfo(label = "Position", desc = "Location and orientation of the sensor.")
    public PositionConfig positionConfig = new PositionConfig();

    @Override
    public PositionConfig.LLALocation getLocation() {
        return positionConfig.location;
    }

    @Override
    public PositionConfig.EulerOrientation getOrientation() {
        return positionConfig.orientation;
    }
}
