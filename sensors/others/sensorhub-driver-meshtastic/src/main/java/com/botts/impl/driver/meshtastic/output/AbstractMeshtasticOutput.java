package com.botts.impl.driver.meshtastic.output;

import com.botts.impl.driver.meshtastic.MeshtasticSensor;
import org.meshtastic.proto.MeshProtos;
import org.sensorhub.impl.sensor.VarRateSensorOutput;
import org.vast.swe.SWEHelper;

public abstract class AbstractMeshtasticOutput extends VarRateSensorOutput<MeshtasticSensor> {

    protected SWEHelper fac;

    public AbstractMeshtasticOutput(String name, MeshtasticSensor parentSensor) {
        super(name, parentSensor, 1);
        fac = new SWEHelper();
    }

    public abstract void onMessage(MeshProtos.FromRadio msg);

    public abstract boolean canHandle(MeshProtos.FromRadio msg);

}
