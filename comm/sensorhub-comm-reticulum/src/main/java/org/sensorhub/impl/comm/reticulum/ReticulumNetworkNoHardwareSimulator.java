package org.sensorhub.impl.comm.reticulum;

public class ReticulumNetworkNoHardwareSimulator
{
    public static final String MODE = "no-hardware Reticulum fixture replay";

    public Status nextStatus()
    {
        return new Status("fixture-loopback", true, 1000000L, 1, 0, 0);
    }

    public static class Status
    {
        public final String interfaceName;
        public final boolean online;
        public final long bitrateBps;
        public final int peers;
        public final int lxmfQueued;
        public final int lxstStreams;

        public Status(String interfaceName, boolean online, long bitrateBps, int peers, int lxmfQueued, int lxstStreams)
        {
            this.interfaceName = interfaceName;
            this.online = online;
            this.bitrateBps = bitrateBps;
            this.peers = peers;
            this.lxmfQueued = lxmfQueued;
            this.lxstStreams = lxstStreams;
        }
    }
}
