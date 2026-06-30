package org.sensorhub.impl.sensor.rtmpcam.helpers;

import java.net.MalformedURLException;
import java.net.URL;

public class RtmpStringParser {
    public static boolean isRtmp(String str) {
        return str.startsWith("rtmp");
    }

    public static String getUsername(String str) {
        URL url;
        try {
            url = new URL(str);
        } catch (MalformedURLException e) {
            return null;
        }

        if (url.getUserInfo() == null) {
            return null;
        } else {
            return url.getUserInfo().split(":")[0];
        }
    }

    public static String getPassword(String str) {
        URL url;
        try {
            url = new URL(str);
        } catch (MalformedURLException e) {
            return null;
        }
        if (url.getUserInfo() == null) {
            return null;
        } else {
            return url.getUserInfo().split(":")[1];
        }
    }

    public static String getPath(String str) {
        URL url;
        try {
            url = new URL(str);
        } catch (MalformedURLException e) {
            return null;
        }
        return url.getPath();
    }
}
