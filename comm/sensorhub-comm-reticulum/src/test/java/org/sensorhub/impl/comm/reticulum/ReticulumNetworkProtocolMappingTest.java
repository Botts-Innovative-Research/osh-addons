package org.sensorhub.impl.comm.reticulum;

import static org.junit.Assert.*;
import org.junit.Test;

public class ReticulumNetworkProtocolMappingTest
{
    @Test
    public void scenarioReticulumRnsFixtureMapsToStatusFrame()
    {
        String scenario = "SCENARIO-RETICULUM-RNS fixture parser";
        ReticulumNetworkRnsStatusFrame frame = ReticulumNetworkRnsStatusFrame.fromFixture("tcp0,true,2400,3,2,1");
        assertEquals(scenario, "tcp0", frame.interfaceName);
        assertTrue(frame.online);
        assertEquals(2400L, frame.bitrateBps);
        assertEquals(3, frame.peers);
        assertEquals(2, frame.lxmfQueued);
        assertEquals(1, frame.lxstStreams);
        assertTrue(frame.toObservationCsv().contains("tcp0,true,2400"));
    }

    @Test
    public void scenarioReticulumLxmfPropagationRequiresNode()
    {
        String scenario = "SCENARIO-RETICULUM-LXMF propagation validation";
        ReticulumNetworkLxmfCommand command = new ReticulumNetworkLxmfCommand();
        assertTrue(scenario, command.validate("abc", "hello", ReticulumNetworkConfig.LxmfDeliveryMethod.PROPAGATED, "").contains("propagation node"));
        assertTrue(command.validate("abc", "hello", ReticulumNetworkConfig.LxmfDeliveryMethod.PROPAGATED, "node123").contains("PROPAGATED"));
    }

    @Test
    public void scenarioReticulumLxstMetadataMapsFromConfig()
    {
        String scenario = "SCENARIO-RETICULUM-LXST metadata mapping";
        ReticulumNetworkConfig config = new ReticulumNetworkConfig();
        config.lxstStreamName = "meshStatus";
        config.lxstMetadataPath = "/tmp/lxst.json";
        config.lxstPollPeriodSeconds = 7.5;
        ReticulumNetworkLxstStreamMetadata metadata = ReticulumNetworkLxstStreamMetadata.fromConfig(config);
        assertEquals(scenario, "meshStatus", metadata.streamName);
        assertEquals("/tmp/lxst.json", metadata.metadataPath);
        assertEquals(7.5, metadata.pollPeriodSeconds, 0.001);
    }
}
