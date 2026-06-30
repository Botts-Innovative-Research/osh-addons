package org.sensorhub.impl.sensor.rtmpcam.helpers;

import org.sensorhub.impl.sensor.rtmpcam.RtmpListener;

import java.net.URL;
import java.util.*;

public class RtmpClientSet implements Set<RtmpListener> {
    private final Map<String, RtmpListener> stringAndClient = new HashMap<>();
    private final Map<RtmpListener, String> clientAndString = new HashMap<>();

    @Override
    public int size() {
        return stringAndClient.size();
    }

    @Override
    public boolean isEmpty() {
        return stringAndClient.isEmpty();
    }

    @Override
    public boolean contains(Object item) {
        if (item instanceof RtmpListener client) {
            return clientAndString.containsKey(client);
        } else if (item instanceof String string) {
            return stringAndClient.containsKey(string);
        } else if (item instanceof URL url) {
            return stringAndClient.containsKey(url.toString());
        } else {
            return false;
        }
    }

    @Override
    public Iterator<RtmpListener> iterator() {
        return null;
    }

    @Override
    public Object[] toArray() {
        return null;
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return null;
    }

    @Override
    public boolean add(RtmpListener rtmpListener) {
        String url = rtmpListener.getUrl().toString();
        if (rtmpListener == null || url == null || contains(rtmpListener) || contains(url)) {
            return false;
        }
        stringAndClient.put(url, rtmpListener);
        clientAndString.put(rtmpListener, url);
        return true;
    }

    @Override
    public boolean remove(Object item) {
        return (item instanceof RtmpListener client)
                && (stringAndClient.remove(clientAndString.remove(client)) != null);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return false;
    }

    @Override
    public boolean addAll(Collection<? extends RtmpListener> c) {
        return false;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return false;
    }


    @Override
    public void clear() {

    }
}
