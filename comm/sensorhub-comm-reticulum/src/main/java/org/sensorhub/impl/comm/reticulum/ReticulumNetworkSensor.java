package org.sensorhub.impl.comm.reticulum;

import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.vast.sensorML.SMLHelper;
import org.vast.swe.SWEHelper;
import net.opengis.sensorml.v20.PhysicalSystem;

public class ReticulumNetworkSensor extends AbstractSensorModule<ReticulumNetworkSensorConfig>
{
    ReticulumNetworkSensorOutput dataInterface;

    @Override
    protected void doInit() throws SensorHubException
    {
        super.doInit();
        generateUniqueID("urn:osh:network:reticulum:", null);
        generateXmlID("RETICULUM_NETWORK_", null);
        dataInterface = new ReticulumNetworkSensorOutput(this);
        addOutput(dataInterface, false);
        addControlInput(new ReticulumNetworkSensorControl(this));
        dataInterface.init();
    }

    @Override
    protected void updateSensorDescription()
    {
        synchronized (sensorDescLock)
        {
            super.updateSensorDescription();
            sensorDescription.setDescription("Reticulum RNS/LXMF/LXST no-hardware simulator mock fixture replay runtime");
            var sml = new SMLHelper();
            sml.edit((PhysicalSystem)sensorDescription)
                .addClassifier(sml.classifiers.sensorType("Reticulum network communication system"))
                .addCapabilityList("system_caps", sml.capabilities.systemCapabilities()
                    .add("status_rate", sml.capabilities.reportingFrequency(1.0))
                    .add("connected_systems_api", sml.createText()
                        .definition(SWEHelper.getDBpediaUri("Application_programming_interface"))
                        .label("Connected Systems API")));
        }
    }

    @Override
    protected void doStart() throws SensorHubException
    {
        dataInterface.start();
    }

    @Override
    protected void doStop() throws SensorHubException
    {
        if (dataInterface != null)
            dataInterface.stop();
    }

    @Override
    public void cleanup() throws SensorHubException
    {
    }

    @Override
    public boolean isConnected()
    {
        return true;
    }
}
