package org.sensorhub.impl.comm.reticulum;

import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.sensor.SensorConfig;

public class ReticulumNetworkSensorConfig extends SensorConfig
{
    @DisplayInfo(label="Sampling period", desc="RNS status sampling period in seconds.")
    public double samplingPeriodSeconds = 1.0;

    @DisplayInfo(label="Interface name", desc="Reticulum interface name for fixture or process bridge status.")
    public String interfaceName = "fixture-loopback";

    @DisplayInfo(label="RNS config path", desc="Reticulum configuration file path, normally ~/.reticulum/config.")
    public String rnsConfigPath = "~/.reticulum/config";

    @DisplayInfo(label="RNS storage path", desc="Reticulum storage directory, normally ~/.reticulum.")
    public String rnsStoragePath = "~/.reticulum";

    @DisplayInfo(label="Identity path", desc="Optional Reticulum identity path.")
    public String identityPath = "";

    @DisplayInfo(label="Interface mode", desc="Reticulum interface mode for live bridge operation.")
    public ReticulumNetworkConfig.InterfaceMode interfaceMode = ReticulumNetworkConfig.InterfaceMode.AUTO;

    @DisplayInfo(label="TCP host", desc="TCP host for TCP Reticulum interfaces.")
    public String tcpHost = "127.0.0.1";

    @DisplayInfo(label="TCP port", desc="TCP port for TCP Reticulum interfaces.")
    public int tcpPort = 7822;

    @DisplayInfo(label="UDP bind host", desc="UDP bind host for UDP Reticulum interfaces.")
    public String udpBindHost = "0.0.0.0";

    @DisplayInfo(label="UDP bind port", desc="UDP bind port for UDP Reticulum interfaces.")
    public int udpBindPort = 4242;

    @DisplayInfo(label="Serial port", desc="Serial device or COM port for RNode-style Reticulum interfaces.")
    public String serialPort = "";

    @DisplayInfo(label="Serial baud rate", desc="Serial baud rate for RNode-style Reticulum interfaces.")
    public int serialBaudRate = 115200;

    @DisplayInfo(label="Enable LXMF", desc="Expose LXMF outbound messages as a control stream.")
    public boolean enableLxmf = true;

    @DisplayInfo(label="LXMF delivery method", desc="LXMF delivery method used by the control stream.")
    public ReticulumNetworkConfig.LxmfDeliveryMethod lxmfDeliveryMethod = ReticulumNetworkConfig.LxmfDeliveryMethod.DIRECT;

    @DisplayInfo(label="LXMF propagation node", desc="Optional propagation node for propagated LXMF delivery.")
    public String lxmfPropagationNode = "";

    @DisplayInfo(label="LXMF storage path", desc="Optional LXMF storage path.")
    public String lxmfStoragePath = "";

    @DisplayInfo(label="Require LXMF authentication", desc="Require authenticated LXMF handling where supported.")
    public boolean lxmfRequireAuthentication = false;

    @DisplayInfo(label="Retain synced LXMF messages", desc="Retain LXMF messages synchronized from a propagation node.")
    public boolean lxmfRetainSyncedMessages = false;

    @DisplayInfo(label="LXMF stamp cost", desc="Optional LXMF stamp cost.")
    public int lxmfStampCost = 0;

    @DisplayInfo(label="Enable LXST", desc="Expose LXST stream metadata in status observations.")
    public boolean enableLxst = true;

    @DisplayInfo(label="LXST stream name", desc="LXST stream name exposed by OSH.")
    public String lxstStreamName = "reticulumNetworkStatus";

    @DisplayInfo(label="LXST metadata path", desc="Optional path to LXST metadata.")
    public String lxstMetadataPath = "";

    @DisplayInfo(label="LXST poll period", desc="LXST metadata poll period in seconds.")
    public double lxstPollPeriodSeconds = 5.0;
}
