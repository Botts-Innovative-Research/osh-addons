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

    public enum InterfaceMode
    {
        AUTO,
        TCP_CLIENT,
        TCP_SERVER,
        UDP_INTERFACE,
        SERIAL_RNODE,
        CUSTOM_CONFIG
    }

    public enum LxmfDeliveryMethod
    {
        DIRECT,
        OPPORTUNISTIC,
        PROPAGATED
    }

    @DisplayInfo(label="Runtime mode", desc="Reticulum runtime mode. Defaults to no-hardware simulator for repeatable tests.")
    public RuntimeMode runtimeMode = RuntimeMode.NO_HARDWARE_SIMULATOR;

    @DisplayInfo(label="RNS config path", desc="Reticulum configuration file path, normally ~/.reticulum/config.")
    public String rnsConfigPath = "~/.reticulum/config";

    @DisplayInfo(label="RNS storage path", desc="Reticulum storage directory, normally ~/.reticulum.")
    public String rnsStoragePath = "~/.reticulum";

    @DisplayInfo(label="Identity path", desc="Optional Reticulum identity path for process bridge or replay identity binding.")
    public String identityPath = "";

    @DisplayInfo(label="Interface mode", desc="Reticulum interface mode represented in the OSH admin panel.")
    public InterfaceMode interfaceMode = InterfaceMode.AUTO;

    @DisplayInfo(label="Interface name", desc="Reticulum interface name reported through CS API datastream observations.")
    public String interfaceName = "fixture-loopback";

    @DisplayInfo(label="TCP host", desc="TCP host for TCP client or server Reticulum interfaces.")
    public String tcpHost = "127.0.0.1";

    @DisplayInfo(label="TCP port", desc="TCP port for TCP client or server Reticulum interfaces.")
    public int tcpPort = 7822;

    @DisplayInfo(label="UDP bind host", desc="UDP bind host for Reticulum UDP interfaces.")
    public String udpBindHost = "0.0.0.0";

    @DisplayInfo(label="UDP bind port", desc="UDP bind port for Reticulum UDP interfaces.")
    public int udpBindPort = 4242;

    @DisplayInfo(label="Serial port", desc="Serial device path or COM port for RNode-style Reticulum interfaces.")
    public String serialPort = "";

    @DisplayInfo(label="Serial baud rate", desc="Serial baud rate for RNode-style Reticulum interfaces.")
    public int serialBaudRate = 115200;

    @DisplayInfo(label="Fixture path", desc="Optional fixture path for RNS/LXMF/LXST replay.")
    public String fixturePath = "";

    @DisplayInfo(label="Replay path", desc="Optional replay transcript path for deterministic no-hardware runs.")
    public String replayPath = "";

    @DisplayInfo(label="Process bridge command", desc="Optional process bridge command for live rnsd integration.")
    public String processBridgeCommand = "rnsd";

    @DisplayInfo(label="Status poll period", desc="RNS status poll period in seconds.")
    public double statusPollPeriodSeconds = 1.0;

    @DisplayInfo(label="Enable RNS status", desc="Expose RNS interface status as a Connected Systems API datastream.")
    public boolean enableRnsStatus = true;

    @DisplayInfo(label="Enable LXMF", desc="Expose LXMF outbound messages as a Connected Systems API control stream.")
    public boolean enableLxmf = true;

    @DisplayInfo(label="LXMF delivery method", desc="LXMF delivery mode: direct, opportunistic, or propagated.")
    public LxmfDeliveryMethod lxmfDeliveryMethod = LxmfDeliveryMethod.DIRECT;

    @DisplayInfo(label="LXMF propagation node", desc="Optional LXMF propagation node destination/hash for propagated delivery.")
    public String lxmfPropagationNode = "";

    @DisplayInfo(label="LXMF storage path", desc="Optional LXMF router storage path.")
    public String lxmfStoragePath = "";

    @DisplayInfo(label="Require LXMF authentication", desc="Require authenticated LXMF message handling where supported by the bridge.")
    public boolean lxmfRequireAuthentication = false;

    @DisplayInfo(label="Retain synced LXMF messages", desc="Retain LXMF messages synchronized from a propagation node.")
    public boolean lxmfRetainSyncedMessages = false;

    @DisplayInfo(label="LXMF stamp cost", desc="Optional LXMF stamp cost used for anti-spam enforcement.")
    public int lxmfStampCost = 0;

    @DisplayInfo(label="Enable LXST", desc="Expose LXST stream metadata/status in the Reticulum network datastream.")
    public boolean enableLxst = true;

    @DisplayInfo(label="LXST stream name", desc="LXST stream name exposed through OSH.")
    public String lxstStreamName = "reticulumNetworkStatus";

    @DisplayInfo(label="LXST metadata path", desc="Optional path to LXST stream metadata.")
    public String lxstMetadataPath = "";

    @DisplayInfo(label="LXST poll period", desc="LXST stream metadata poll period in seconds.")
    public double lxstPollPeriodSeconds = 5.0;

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
        public String rnsConfigPath = "~/.reticulum/config";
        public String rnsStoragePath = "~/.reticulum";
        public String identityPath = "";
        public boolean enableLxmf = true;
        public boolean enableLxst = true;
    }
}
