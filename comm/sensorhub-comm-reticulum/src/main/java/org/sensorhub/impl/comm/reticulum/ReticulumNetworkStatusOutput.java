package org.sensorhub.impl.comm.reticulum;

import org.vast.swe.SWEHelper;

public class ReticulumNetworkStatusOutput
{
    private ReticulumNetworkRnsStatusFrame lastStatus;

    public String getRecordDescription()
    {
        SWEHelper swe = new SWEHelper();
        return "Connected Systems API system datastream control stream SensorML feature of interest observed property SWE "
            + "reticulumNetworkStatus networkStatus "
            + swe.getClass().getSimpleName();
    }

    public void publish(ReticulumNetworkRnsStatusFrame status)
    {
        this.lastStatus = status;
    }

    public ReticulumNetworkRnsStatusFrame getLastStatus()
    {
        return lastStatus;
    }
}
