package org.sensorhub.impl.comm.reticulum;

public class ReticulumNetworkNoHardwareSimulator
{
    public static final String MODE = "no-hardware Reticulum fixture replay";

    public ReticulumNetworkRnsStatusFrame nextStatus()
    {
        return ReticulumNetworkRnsStatusFrame.fromFixture("fixture-loopback,true,1000000,1,0,1");
    }
}
