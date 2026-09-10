package org.sensorhub.impl.comm.reticulum;

import static org.junit.Assert.*;
import org.junit.Test;

public class ReticulumNetworkProviderTest
{
    @Test
    public void scenarioReticulumSimulatorPublishesFixtureStatus()
    {
        String scenario = "SCENARIO-RETICULUM no-hardware fixture runtime";
        ReticulumNetworkNoHardwareSimulator simulator = new ReticulumNetworkNoHardwareSimulator();
        assertTrue(scenario, simulator.nextStatus().online);
        assertEquals("fixture-loopback", simulator.nextStatus().interfaceName);
    }

    @Test
    public void scenarioReticulumLxmfCommandRequiresDestinationHash()
    {
        ReticulumNetworkLxmfCommand command = new ReticulumNetworkLxmfCommand();
        assertTrue(command.validate("", "hello").contains("Destination hash"));
        assertTrue(command.validate("abc123", "hello").contains("LXMF"));
    }
}
