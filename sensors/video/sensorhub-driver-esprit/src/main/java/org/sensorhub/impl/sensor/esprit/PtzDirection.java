package org.sensorhub.impl.sensor.esprit;

public enum PtzDirection {
    STOP("stop"), LEFT("left"), RIGHT("right"), UP("up"), DOWN("down"),
    ZOOM_IN("zoomin"), ZOOM_OUT("zoomout"), FOCUS_NEAR("focusnear"), FOCUS_FAR("focusfar");

    private String apiString;

    PtzDirection(String apiString) {
        this.apiString = apiString;
    }

    public String getApiString() {
        return apiString;
    }
}