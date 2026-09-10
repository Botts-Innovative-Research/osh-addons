package org.sensorhub.impl.comm.reticulum;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.module.AbstractModule;

public class ReticulumNetworkProvider extends AbstractModule<ReticulumNetworkConfig> implements ICommProvider<ReticulumNetworkConfig>
{
    private final ByteArrayOutputStream outbound = new ByteArrayOutputStream();
    private ByteArrayInputStream inbound = new ByteArrayInputStream(new byte[0]);
    private ReticulumNetworkNoHardwareSimulator simulator;
    private ReticulumNetworkStatusOutput statusOutput;

    @Override
    protected void doInit() throws SensorHubException
    {
        if (config == null)
            config = new ReticulumNetworkConfig();
        simulator = new ReticulumNetworkNoHardwareSimulator();
        statusOutput = new ReticulumNetworkStatusOutput();
    }

    @Override
    protected void doStart() throws SensorHubException
    {
        ReticulumNetworkRnsStatusFrame status = simulator.nextStatus();
        String frame = status.toObservationCsv() + ",LXMF,LXST\n";
        inbound = new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8));
        statusOutput.publish(status);
    }

    @Override
    protected void doStop() throws SensorHubException
    {
        inbound = new ByteArrayInputStream(new byte[0]);
        outbound.reset();
    }

    @Override
    public InputStream getInputStream()
    {
        return inbound;
    }

    @Override
    public OutputStream getOutputStream()
    {
        return outbound;
    }

    public ReticulumNetworkStatusOutput getStatusOutput()
    {
        return statusOutput;
    }
}
