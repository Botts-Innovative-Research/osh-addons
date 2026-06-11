package org.sensorhub.impl.service.federation.nodes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BiConsumer;

import org.sensorhub.impl.service.federation.ControlstreamInfo;
import org.sensorhub.impl.service.federation.DatastreamInfo;
import org.sensorhub.impl.service.federation.SystemInfo;
import org.sensorhub.impl.service.federation.environment.NodeEnvData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class RemoteNode {

    private static final Logger log = LoggerFactory.getLogger(RemoteNode.class);

    private NodeEnvData nodeEnvData;
    private HttpClient httpClient;
    private Gson gson;

    public RemoteNode(NodeEnvData nodeEnvData) {
        this.nodeEnvData = nodeEnvData;
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
    }

    public List<SystemInfo> discoverSystems() throws IOException, InterruptedException {
        String url = nodeEnvData.getBaseUrl() + "/systems";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", getAuthHeader())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        List<SystemInfo> systems = new ArrayList<>();

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            JsonArray items = json.getAsJsonArray("items");

            if (items != null) {
                for (JsonElement item : items) {
                    SystemInfo sys = SystemInfo.fromJson(item.getAsJsonObject());
                    sys.setRemoteNode(this);
                    systems.add(sys);
                }
            }
        }

        return systems;
    }

    public List<DatastreamInfo> discoverDatastreams(String systemId) throws IOException, InterruptedException {
        String url = nodeEnvData.getBaseUrl() + "/systems/" + systemId + "/datastreams";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", getAuthHeader())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        List<DatastreamInfo> datastreams = new ArrayList<>();

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            JsonArray items = json.getAsJsonArray("items");

            if (items != null) {
                for (JsonElement item : items) {
                    DatastreamInfo ds = DatastreamInfo.fromJson(item.getAsJsonObject());
                    ds.setRemoteNode(this);
                    datastreams.add(ds);
                }
            }
        }

        return datastreams;
    }

    public List<ControlstreamInfo> discoverControlstreams(String systemId) throws IOException, InterruptedException {
        String url = nodeEnvData.getBaseUrl() + "/systems/" + systemId + "/controlstreams";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", getAuthHeader())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        List<ControlstreamInfo> controlstreams = new ArrayList<>();

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            JsonArray items = json.getAsJsonArray("items");

            if (items != null) {
                for (JsonElement item : items) {
                    ControlstreamInfo cs = ControlstreamInfo.fromJson(item.getAsJsonObject());
                    cs.setRemoteNode(this);
                    controlstreams.add(cs);
                }
            }
        }

        return controlstreams;
    }

    public void subscribeToObservations(String datastreamId, BiConsumer<String, JsonObject> callback) {
        // MQTT subscription would be implemented here
        // For now this is a placeholder - actual MQTT implementation would use Eclipse Paho
    }

    public void postCommand(String controlstreamId, JsonObject command) throws IOException, InterruptedException {
        String url = nodeEnvData.getBaseUrl() + "/controlstreams/" + controlstreamId + "/commands";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", getAuthHeader())
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(command)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("Command forwarded to {}: HTTP {}", controlstreamId, response.statusCode());
        } else {
            log.error("Remote rejected command for {}: HTTP {} — {}",
                    controlstreamId, response.statusCode(), response.body());
        }
    }

    private String getAuthHeader() {
        if (nodeEnvData.getAuth() != null && "basic".equals(nodeEnvData.getAuth().getType())) {
            String credentials = nodeEnvData.getAuth().getUsername() + ":" + nodeEnvData.getAuth().getPassword();
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
        }
        return "";
    }

    public NodeEnvData getNodeEnvData() {
        return nodeEnvData;
    }
}