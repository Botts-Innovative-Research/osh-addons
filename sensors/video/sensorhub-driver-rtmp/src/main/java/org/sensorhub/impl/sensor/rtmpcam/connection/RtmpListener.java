package org.sensorhub.impl.sensor.rtmpcam.connection;

import org.sensorhub.impl.sensor.rtmpcam.config.ConnectionConfig;
import org.sensorhub.impl.sensor.rtmpcam.event.RtmpConnectEvent;
import org.sensorhub.impl.sensor.rtmpcam.event.RtmpDisconnectEvent;
import org.sensorhub.impl.sensor.rtmpcam.event.RtmpReconnectEvent;
import org.sensorhub.impl.sensor.rtmpcam.event.RtmpStreamEvent;

/**
 * Extend this class and register it with {@link RtmpListenerManager} to
 * receive encoded packets from a matched RTMP stream.
 */
public interface RtmpListener {

    public ConnectionConfig config();

    /**
     * Receives one encoded packet demuxed from the RTMP stream.
     *
     * @param data        raw encoded bytes (H.264 Annex B, AAC, etc.)
     * @param streamIndex FFmpeg stream index within the FLV container
     * @param pts         presentation timestamp in stream timebase units
     * @param isVideo     true for video, false for audio
     */
    public void publish(byte[] data, int streamIndex, long pts, boolean isVideo);

    /** Called once after negotiation succeeds and this listener is selected. */
    public void onConnected(RtmpConnectEvent event);

    /** Called once when the client disconnects or the pipeline faults. */
    public void onDisconnected(RtmpDisconnectEvent event);

    public void onReconnected(RtmpReconnectEvent event);

    public void onStreamInfo(RtmpStreamEvent event);
}