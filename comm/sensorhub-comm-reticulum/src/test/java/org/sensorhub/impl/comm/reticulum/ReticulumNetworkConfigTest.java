package org.sensorhub.impl.comm.reticulum;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import org.junit.Test;
import org.sensorhub.api.config.DisplayInfo;

public class ReticulumNetworkConfigTest
{
    @Test
    public void scenarioReticulumConfigExposesAdminPanelOptions() throws Exception
    {
        String scenario = "SCENARIO-RETICULUM-CONFIG admin panel RNS LXMF LXST options";
        ReticulumNetworkConfig config = new ReticulumNetworkConfig();
        assertEquals(scenario, "~/.reticulum/config", config.rnsConfigPath);
        assertEquals("~/.reticulum", config.rnsStoragePath);
        assertEquals(ReticulumNetworkConfig.InterfaceMode.AUTO, config.interfaceMode);
        assertEquals(ReticulumNetworkConfig.LxmfDeliveryMethod.DIRECT, config.lxmfDeliveryMethod);
        assertTrue(config.enableLxmf);
        assertTrue(config.enableLxst);
        assertEquals("reticulumNetworkStatus", config.lxstStreamName);
        Field field = ReticulumNetworkConfig.class.getField("rnsConfigPath");
        assertNotNull(field.getAnnotation(DisplayInfo.class));
    }

    @Test
    public void scenarioReticulumSensorConfigCarriesRunnableModuleOptions()
    {
        ReticulumNetworkSensorConfig config = new ReticulumNetworkSensorConfig();
        assertEquals("~/.reticulum/config", config.rnsConfigPath);
        assertEquals("127.0.0.1", config.tcpHost);
        assertEquals(7822, config.tcpPort);
        assertEquals(4242, config.udpBindPort);
        assertEquals(115200, config.serialBaudRate);
        assertTrue(config.enableLxmf);
        assertTrue(config.enableLxst);
    }

    @Test
    public void scenarioReticulumEmbeddedRuntimeIsSelfSufficient()
    {
        String scenario = "SCENARIO-RETICULUM-EMBEDDED self sufficient embedded runtime";
        ReticulumNetworkEmbeddedRuntime runtime = new ReticulumNetworkEmbeddedRuntime();
        assertTrue(scenario, runtime.isSelfSufficient());
        assertEquals("NO_EXTERNAL_PROCESS", ReticulumNetworkEmbeddedRuntime.PROCESS_POLICY);
        assertTrue(runtime.bundledProtocols().contains("RNS"));
        assertTrue(runtime.bundledProtocols().contains("LXMF"));
        assertTrue(runtime.bundledProtocols().contains("LXST"));
    }
}
