package org.sensorhub.impl.service.federation.environment;

import com.google.gson.annotations.SerializedName;

public class NodeEnvData {

    private String name;
    private String protocol;
    private String address;
    private int port;

    @SerializedName("is_commander")
    private boolean isCommander;

    @SerializedName("sensorhub_root")
    private String sensorhubRoot;

    @SerializedName("api_root")
    private String apiRoot;

    private AuthData auth;

    @SerializedName("mqtt_port")
    private int mqttPort;

    public NodeEnvData() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isCommander() {
        return isCommander;
    }

    public void setCommander(boolean commander) {
        isCommander = commander;
    }

    public String getSensorhubRoot() {
        return sensorhubRoot;
    }

    public void setSensorhubRoot(String sensorhubRoot) {
        this.sensorhubRoot = sensorhubRoot;
    }

    public String getApiRoot() {
        return apiRoot;
    }

    public void setApiRoot(String apiRoot) {
        this.apiRoot = apiRoot;
    }

    public AuthData getAuth() {
        return auth;
    }

    public void setAuth(AuthData auth) {
        this.auth = auth;
    }

    public int getMqttPort() {
        return mqttPort;
    }

    public void setMqttPort(int mqttPort) {
        this.mqttPort = mqttPort;
    }

    public String getBaseUrl() {
        return protocol + "://" + address + ":" + port + apiRoot;
    }
}