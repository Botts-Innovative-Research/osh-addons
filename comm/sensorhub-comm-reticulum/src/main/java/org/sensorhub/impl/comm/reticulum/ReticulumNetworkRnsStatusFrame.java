package org.sensorhub.impl.comm.reticulum;

public class ReticulumNetworkRnsStatusFrame
{
    public final String interfaceName;
    public final boolean online;
    public final long bitrateBps;
    public final int peers;
    public final int lxmfQueued;
    public final int lxstStreams;

    public ReticulumNetworkRnsStatusFrame(String interfaceName, boolean online, long bitrateBps, int peers, int lxmfQueued, int lxstStreams)
    {
        this.interfaceName = interfaceName;
        this.online = online;
        this.bitrateBps = bitrateBps;
        this.peers = peers;
        this.lxmfQueued = lxmfQueued;
        this.lxstStreams = lxstStreams;
    }

    public static ReticulumNetworkRnsStatusFrame fromFixture(String fixture)
    {
        if (fixture == null || fixture.trim().isEmpty())
            return new ReticulumNetworkRnsStatusFrame("fixture-loopback", true, 1000000L, 1, 0, 1);
        String[] parts = fixture.split(",");
        String name = parts.length > 0 && !parts[0].trim().isEmpty() ? parts[0].trim() : "fixture-loopback";
        boolean online = parts.length > 1 ? Boolean.parseBoolean(parts[1].trim()) : true;
        long bitrate = parts.length > 2 ? Long.parseLong(parts[2].trim()) : 1000000L;
        int peers = parts.length > 3 ? Integer.parseInt(parts[3].trim()) : 1;
        int queued = parts.length > 4 ? Integer.parseInt(parts[4].trim()) : 0;
        int streams = parts.length > 5 ? Integer.parseInt(parts[5].trim()) : 1;
        return new ReticulumNetworkRnsStatusFrame(name, online, bitrate, peers, queued, streams);
    }

    public String toObservationCsv()
    {
        return interfaceName + "," + online + "," + bitrateBps + "," + peers + "," + lxmfQueued + "," + lxstStreams;
    }
}
