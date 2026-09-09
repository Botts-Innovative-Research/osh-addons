package org.sensorhub.impl.comm.reticulum;

import org.sensorhub.api.sensor.SensorConfig;

public class ReticulumNetworkSensorConfig extends SensorConfig
{
    public double samplingPeriodSeconds = 1.0;
    public String interfaceName = "fixture-loopback";
}
