package org.sensorhub.impl.comm.reticulum;

import org.sensorhub.api.comm.CommProviderConfig;
import org.sensorhub.api.config.DisplayInfo;

public class ReticulumNetworkConfig extends CommProviderConfig<ReticulumNetworkConfig.ProtocolOptions>
{
    public enum RuntimeMode
    {
        NO_HARDWARE_SIMULATOR,
        FIXTURE_REPLAY,
        PROCESS_BRIDGE
    }

    @DisplayInfo(desc="Reticulum runtime mode. Defaults to no-hardware simulator for repeatable tests.")
    public RuntimeMode runtimeMode = RuntimeMode.NO_HARDWARE_SIMULATOR;

    @DisplayInfo(desc="Optional fixture path for RNS/LXMF/LXST replay")
    public String fixturePath = "";

    @DisplayInfo(desc="Optional process bridge command for live rnsd integration")
    public String processBridgeCommand = "";

    @DisplayInfo(desc="Reticulum interface name reported through CS API datastream observations")
    public String interfaceName = "fixture-loopback";

    public ReticulumNetworkConfig()
    {
        this.name = "Reticulum Network";
        this.description = "Reticulum network communication provider with deterministic fixture replay.";
        this.protocol = new ProtocolOptions();
    }

    public boolean usesNoHardwareRuntime()
    {
        return runtimeMode == RuntimeMode.NO_HARDWARE_SIMULATOR || runtimeMode == RuntimeMode.FIXTURE_REPLAY;
    }

    public static class ProtocolOptions
    {
        public String rnsIdentityPath = "";
        public boolean enableLxmf = true;
        public boolean enableLxst = true;
    }
}
