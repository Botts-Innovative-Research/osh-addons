package org.sensorhub.impl.comm.reticulum;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.vast.swe.SWEHelper;

public class ReticulumNetworkSensorOutput extends AbstractSensorOutput<ReticulumNetworkSensor>
{
    DataComponent dataStruct;
    DataEncoding dataEncoding;

    public ReticulumNetworkSensorOutput(ReticulumNetworkSensor parentSensor)
    {
        super("reticulumNetworkStatus", parentSensor);
    }

    protected void init()
    {
        SWEHelper fac = new SWEHelper();
        dataStruct = fac.createRecord()
            .name("reticulumNetworkStatus")
            .definition("https://reticulum.network/ontology/datastream/network-status")
            .addField("time", fac.createTime().asSamplingTimeIsoUTC())
            .addField("interfaceName", fac.createText().label("Reticulum interface"))
            .addField("online", fac.createBoolean().label("RNS online"))
            .addField("peers", fac.createQuantity().label("RNS peers").uomCode("1"))
            .addField("lxmfQueued", fac.createQuantity().label("LXMF queued").uomCode("1"))
            .addField("lxstStreams", fac.createQuantity().label("LXST streams").uomCode("1"))
            .build();
        dataEncoding = fac.newTextEncoding(",", "\n");
    }

    public void start()
    {
        publishFixture();
    }

    public void stop()
    {
    }

    public void publishFixture()
    {
        DataBlock dataBlock = dataStruct.createDataBlock();
        int i = 0;
        dataBlock.setDoubleValue(i++, System.currentTimeMillis() / 1000.0);
        dataBlock.setStringValue(i++, "fixture-loopback");
        dataBlock.setBooleanValue(i++, true);
        dataBlock.setIntValue(i++, 1);
        dataBlock.setIntValue(i++, 0);
        dataBlock.setIntValue(i++, 0);
        latestRecord = dataBlock;
        latestRecordTime = System.currentTimeMillis();
        eventHandler.publish(new DataEvent(latestRecordTime, this, dataBlock));
    }

    @Override
    public DataComponent getRecordDescription()
    {
        return dataStruct;
    }

    @Override
    public DataEncoding getRecommendedEncoding()
    {
        return dataEncoding;
    }

    @Override
    public double getAverageSamplingPeriod()
    {
        return 1.0;
    }
}
