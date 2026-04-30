package org.sensorhub.impl.sensor.rtmp;

import java.util.HashSet;
import java.util.Set;

public class RtmpUrlArbiter {
    private final Set<String> urls = new HashSet<>();

    public synchronized boolean addConnection(String url) {
        return urls.add(url);
    }

    public synchronized void removeConnection(String url) {
        urls.remove(url);
    }
}
