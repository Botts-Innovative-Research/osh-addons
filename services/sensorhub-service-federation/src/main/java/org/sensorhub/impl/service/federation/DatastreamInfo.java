package org.sensorhub.impl.service.federation;

import java.util.concurrent.ConcurrentLinkedDeque;

import org.sensorhub.impl.service.federation.nodes.RemoteNode;

import com.google.gson.JsonObject;

public class DatastreamInfo {

    private String id;
    private String name;
    private String description;
    private JsonObject rawJson;
    private RemoteNode remoteNode;
    private SystemInfo parentSystem;
    private String mirroredId;
    private ConcurrentLinkedDeque<JsonObject> inboundDeque;

    public DatastreamInfo() {
        this.inboundDeque = new ConcurrentLinkedDeque<>();
    }

    public static DatastreamInfo fromJson(JsonObject json) {
        DatastreamInfo ds = new DatastreamInfo();
        ds.rawJson = json;
        ds.id = json.has("id") ? json.get("id").getAsString() : null;
        ds.name = json.has("name") ? json.get("name").getAsString() : null;
        ds.description = json.has("description") ? json.get("description").getAsString() : null;
        return ds;
    }

    public JsonObject toJson() {
        return rawJson != null ? rawJson.deepCopy() : new JsonObject();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public RemoteNode getRemoteNode() {
        return remoteNode;
    }

    public void setRemoteNode(RemoteNode remoteNode) {
        this.remoteNode = remoteNode;
    }

    public SystemInfo getParentSystem() {
        return parentSystem;
    }

    public void setParentSystem(SystemInfo parentSystem) {
        this.parentSystem = parentSystem;
    }

    public String getMirroredId() {
        return mirroredId;
    }

    public void setMirroredId(String mirroredId) {
        this.mirroredId = mirroredId;
    }

    public ConcurrentLinkedDeque<JsonObject> getInboundDeque() {
        return inboundDeque;
    }
}