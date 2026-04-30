package org.sensorhub.impl.sensor.rtmp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class RtmpUrlArbiter {
    private final Map<String, String> urls = new HashMap<>();

    // If successful, returns null, otherwise returns the moduleUid of the existing connection
    public synchronized String addConnection(String url, String moduleUid) {
        if (urls.containsKey(url)) {
            return urls.get(url);
        } else {
            urls.put(url, moduleUid);
            return null;
        }
    }

    public synchronized void removeConnection(String url) {
        urls.remove(url);
    }
}
