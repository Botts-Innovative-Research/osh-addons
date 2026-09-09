package org.sensorhub.impl.comm.reticulum;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.impl.sensor.AbstractSensorControl;
import org.vast.swe.SWEHelper;

public class ReticulumNetworkSensorControl extends AbstractSensorControl<ReticulumNetworkSensor>
{
    protected static final String NAME = "lxmfOutboundMessage";
    protected final DataComponent commandDescription;

    public ReticulumNetworkSensorControl(ReticulumNetworkSensor parentSensor)
    {
        super(NAME, parentSensor);
        SWEHelper fac = new SWEHelper();
        commandDescription = fac.createRecord()
            .name(NAME)
            .definition("https://reticulum.network/ontology/control/lxmf-outbound-message")
            .label("LXMF outbound message")
            .addField("destinationHash", fac.createText()
                .label("Destination hash")
                .description("Reticulum Destination hash for the LXMF recipient"))
            .addField("message", fac.createText()
                .label("LXMF message")
                .description("Outbound LXMF message payload"))
            .build();
    }

    @Override
    protected boolean execCommand(DataBlock cmdData)
    {
        String destinationHash = cmdData.getStringValue(0);
        String message = cmdData.getStringValue(1);
        return destinationHash != null && !destinationHash.trim().isEmpty()
            && message != null && !message.trim().isEmpty();
    }

    @Override
    public DataComponent getCommandDescription()
    {
        return commandDescription;
    }
}
