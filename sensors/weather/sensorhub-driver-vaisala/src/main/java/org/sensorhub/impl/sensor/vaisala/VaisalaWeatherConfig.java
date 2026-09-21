package org.sensorhub.impl.sensor.vaisala;

import org.sensorhub.api.comm.CommProviderConfig;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.sensor.PositionConfig;
import org.sensorhub.api.sensor.SensorConfig;
import org.sensorhub.impl.comm.RobustIPConnectionConfig;
import org.sensorhub.impl.module.RobustConnection;
import org.sensorhub.impl.module.RobustConnectionConfig;

public class VaisalaWeatherConfig extends SensorConfig
{
	public String serialNumber = "aaa0001bb";
	
    @DisplayInfo(label="Communication Settings", desc="Settings for selected communication port")
    public CommProviderConfig<?> commSettings;

    @DisplayInfo(label="Command Timeout", desc="Maximum wait for a command reply in milliseconds")
    public long commandTimeoutMillis = 3000;

    @DisplayInfo(label="Connection Options")
    public RobustConnectionConfig connection = new RobustConnectionConfig();

    @DisplayInfo(desc="Station Geographic Position")
    public PositionConfig position = new PositionConfig();
    
    public VaisalaWeatherConfig()
    {
        this.moduleClass = VaisalaWeatherSensor.class.getCanonicalName();
        
//        UARTConfig serialConf = new UARTConfig();
//        serialConf.moduleClass = "org.sensorhub.impl.comm.rxtx.RxtxSerialCommProvider";
//        serialConf.portName = "/dev/ttyUSB0";
//        serialConf.baudRate = 19200;
//        this.commSettings = serialConf;
    }

    @Override
    public PositionConfig.LLALocation getLocation()
    {
        return position.location;
    }


    @Override
    public PositionConfig.EulerOrientation getOrientation()
    {
        return position.orientation;
    }
}
