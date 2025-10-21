package com.botts.impl.driver.meshtastic.output;

import com.botts.impl.driver.meshtastic.MeshtasticSensor;
import com.google.protobuf.InvalidProtocolBufferException;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.Portnums;
import org.sensorhub.api.data.DataEvent;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.helper.GeoPosHelper;

public abstract class AbstractPacketOutput extends AbstractMeshtasticOutput {

    public AbstractPacketOutput(String name, MeshtasticSensor parentSensor) {
        super(name, parentSensor);
    }

    public abstract boolean canHandlePacket(MeshProtos.MeshPacket packet);

    public abstract void onPacketMessage(MeshProtos.MeshPacket packet);

    @Override
    public void onMessage(MeshProtos.FromRadio msg) {
        onPacketMessage(msg.getPacket());
    }

    @Override
    public boolean canHandle(MeshProtos.FromRadio msg) {
        return msg.hasPacket() && canHandlePacket(msg.getPacket());
    }

}
