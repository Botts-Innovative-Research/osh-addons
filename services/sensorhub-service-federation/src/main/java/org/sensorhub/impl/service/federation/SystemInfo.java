package org.sensorhub.impl.service.federation;

import org.sensorhub.impl.service.federation.nodes.RemoteNode;

import com.google.gson.JsonObject;

public class SystemInfo {

    private String id;
    private String urn;
    private String name;
    private String description;
    private JsonObject rawJson;
    private RemoteNode remoteNode;
    private String mirroredId;

    public static SystemInfo fromJson(JsonObject json) {
        SystemInfo sys = new SystemInfo();
        sys.rawJson = json;
        sys.id = json.has("id") ? json.get("id").getAsString() : null;
        sys.urn = json.has("uniqueId") ? json.get("uniqueId").getAsString() : null;
        sys.name = json.has("name") ? json.get("name").getAsString() : null;
        sys.description = json.has("description") ? json.get("description").getAsString() : null;
        return sys;
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

    public String getUrn() {
        return urn;
    }

    public void setUrn(String urn) {
        this.urn = urn;
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

    public String getMirroredId() {
        return mirroredId;
    }

    public void setMirroredId(String mirroredId) {
        this.mirroredId = mirroredId;
    }
}
