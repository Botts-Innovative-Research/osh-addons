package org.sensorhub.impl.comm.reticulum;

public class ReticulumNetworkLxstStreamMetadata
{
    public final boolean enabled;
    public final String streamName;
    public final String metadataPath;
    public final double pollPeriodSeconds;

    public ReticulumNetworkLxstStreamMetadata(boolean enabled, String streamName, String metadataPath, double pollPeriodSeconds)
    {
        this.enabled = enabled;
        this.streamName = streamName;
        this.metadataPath = metadataPath;
        this.pollPeriodSeconds = pollPeriodSeconds;
    }

    public static ReticulumNetworkLxstStreamMetadata fromConfig(ReticulumNetworkConfig config)
    {
        return new ReticulumNetworkLxstStreamMetadata(
            config.enableLxst,
            config.lxstStreamName,
            config.lxstMetadataPath,
            config.lxstPollPeriodSeconds);
    }
}
