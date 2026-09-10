package org.sensorhub.impl.comm.reticulum;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
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
        throws Exception
    {
        String scenario = "SCENARIO-RETICULUM-EMBEDDED self sufficient embedded runtime";
        ReticulumNetworkEmbeddedRuntime runtime = new ReticulumNetworkEmbeddedRuntime();
        assertTrue(scenario, runtime.isSelfSufficient());
        assertEquals("NO_EXTERNAL_PROCESS", ReticulumNetworkEmbeddedRuntime.PROCESS_POLICY);
        assertTrue(runtime.bundledProtocols().contains("RNS"));
        assertTrue(runtime.bundledProtocols().contains("LXMF"));
        assertTrue(runtime.bundledProtocols().contains("LXST"));
        assertTrue(runtime.hasVendoredResource("reticulum/vendor/Reticulum/RNS/Reticulum.py"));
        assertTrue(runtime.hasVendoredResource("reticulum/vendor/LXMF/LXMF/LXMRouter.py"));
        assertTrue(runtime.hasVendoredResource("reticulum/vendor/lxst/LXST/Network.py"));
        assertTrue("SCENARIO-RETICULUM-RUNTIME-BUNDLE-RECIPE packaging doc", runtime.hasVendoredResource("reticulum/PYTHON-INTERPRETER-PACKAGING.md"));
        assertTrue("SCENARIO-RETICULUM-RUNTIME-BUNDLE-RECIPE runtime matrix", runtime.hasVendoredResource("reticulum/RETICULUM-PACKAGED-RUNTIME-MATRIX.json"));
        assertTrue("SCENARIO-RETICULUM-RUNTIME-BUNDLE-RECIPE runtime lock", runtime.hasVendoredResource("reticulum/RETICULUM-RUNTIME-BUNDLE-LOCK.json"));
        assertTrue("SCENARIO-RETICULUM-RUNTIME-BUNDLE-RECIPE wheelhouse availability", runtime.hasVendoredResource("reticulum/RETICULUM-WHEELHOUSE-AVAILABILITY.json"));
        assertTrue("SCENARIO-RETICULUM-RUNTIME-BUNDLE-RECIPE build script", runtime.hasVendoredResource("reticulum/runtime-build/build-reticulum-runtime-bundle.sh"));
        assertTrue(runtime.resourceIndex().contains("reticulum/vendor/Reticulum/RNS/Reticulum.py"));
        Path stagedRoot = runtime.stageVendoredRuntime(Files.createTempDirectory("reticulum-runtime-test"));
        assertTrue(Files.isRegularFile(stagedRoot.resolve("reticulum/vendor/Reticulum/RNS/Reticulum.py")));
        assertTrue(Files.isRegularFile(stagedRoot.resolve("reticulum/vendor/LXMF/LXMF/LXMRouter.py")));
        assertTrue(Files.isRegularFile(stagedRoot.resolve("reticulum/vendor/lxst/LXST/Network.py")));
        assertTrue(runtime.reticulumPythonPath(stagedRoot).contains("vendor"));
        ReticulumNetworkEmbeddedRuntime.ImportProbeResult probe = runtime.runEmbeddedImportSmoke(stagedRoot, "python3");
        assertEquals("SCENARIO-RETICULUM-EMBEDDED-IMPORT staged vendored RNS LXMF import", 0, probe.exitCode);
        assertTrue(probe.stdout, probe.stdout.contains("\"RNS\": {\"ok\": true, \"version\": \"1.5.2\""));
        assertTrue(probe.stdout, probe.stdout.contains("\"LXMF\": {\"ok\": true, \"version\": \"1.1.0\""));
        assertTrue(probe.stdout, probe.stdout.contains("\"LXST\": {\"error\": \"ModuleNotFoundError\""));
        ReticulumNetworkEmbeddedRuntime.ImportProbeResult protocol = runtime.runEmbeddedProtocolSmoke(stagedRoot, "python3");
        assertEquals("SCENARIO-RETICULUM-EMBEDDED-PROTOCOL staged vendored RNS LXMF protocol smoke", 0, protocol.exitCode);
        assertTrue(protocol.stdout, protocol.stdout.contains("\"RNS_PACKET\": {\"hashLen\": 16, \"ok\": true"));
        assertTrue(protocol.stdout, protocol.stdout.contains("\"LXMF_MESSAGE\": {\"content\": \"osh-body\", \"ok\": true"));
        assertTrue(protocol.stdout, protocol.stdout.contains("\"packedLen\":"));
        assertTrue("SCENARIO-RETICULUM-PACKAGED-RUNTIME packagedPythonRuntime exists", runtime.packagedRuntimeAvailable(stagedRoot));
        assertTrue(runtime.packagedPythonExecutable(stagedRoot).toString().contains("reticulum"));
        ReticulumNetworkEmbeddedRuntime.ImportProbeResult packaged = runtime.runPackagedRuntimeSmoke(stagedRoot);
        assertEquals("SCENARIO-RETICULUM-PACKAGED-RUNTIME-PASS packaged runtime smoke", 0, packaged.exitCode);
        assertTrue(packaged.stdout, packaged.stdout.contains("\"LXST\": {\"ok\": true"));
        assertTrue(packaged.stdout, packaged.stdout.contains("\"numpy\": {\"ok\": true, \"version\": \"2.3.4\""));
        assertTrue(packaged.stdout, packaged.stdout.contains("\"PACKAGED_PROTOCOL\": {\"messagePackedLen\":"));
    }
}
