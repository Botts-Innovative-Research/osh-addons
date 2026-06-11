package org.sensorhub.impl.service.federation.nodes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.function.Consumer;

import org.sensorhub.impl.service.federation.ControlstreamInfo;
import org.sensorhub.impl.service.federation.DatastreamInfo;
import org.sensorhub.impl.service.federation.SystemInfo;
import org.sensorhub.impl.service.federation.environment.NodeEnvData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class CommanderNode {

    private static final Logger log = LoggerFactory.getLogger(CommanderNode.class);

    private NodeEnvData nodeEnvData;
    private HttpClient httpClient;
    private Gson gson;

    public CommanderNode(NodeEnvData nodeEnvData) {
        this.nodeEnvData = nodeEnvData;
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
    }

    public String createSystem(SystemInfo system) throws IOException, InterruptedException {
        String url = nodeEnvData.getBaseUrl() + "/systems";

        JsonObject body = system.toJson();
        body.remove("id");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", getAuthHeader())
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            String location = response.headers().firstValue("Location").orElse("");
            return extractIdFromLocation(location);
        }

        throw new IOException("Failed to create system: HTTP " + response.statusCode());
    }

    public String createDatastream(DatastreamInfo datastream) throws IOException, InterruptedException {
        String systemId = datastream.getParentSystem().getMirroredId();
        String url = nodeEnvData.getBaseUrl() + "/systems/" + systemId + "/datastreams";

        JsonObject body = datastream.toJson();
        body.remove("id");
        body.remove("system@id");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", getAuthHeader())
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            String location = response.headers().firstValue("Location").orElse("");
            return extractIdFromLocation(location);
        }

        throw new IOException("Failed to create datastream: HTTP " + response.statusCode());
    }

    public String createControlstream(ControlstreamInfo controlstream) throws IOException, InterruptedException {
        String systemId = controlstream.getParentSystem().getMirroredId();
        String url = nodeEnvData.getBaseUrl() + "/systems/" + systemId + "/controlstreams";

        JsonObject body = controlstream.toJson();
        body.remove("id");
        body.remove("system@id");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", getAuthHeader())
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            String location = response.headers().firstValue("Location").orElse("");
            return extractIdFromLocation(location);
        }

        throw new IOException("Failed to create controlstream: HTTP " + response.statusCode());
    }

    public void postObservation(String datastreamId, JsonObject observation) throws IOException, InterruptedException {
        String url = nodeEnvData.getBaseUrl() + "/datastreams/" + datastreamId + "/observations";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", getAuthHeader())
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(observation)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("Failed to post observation: HTTP {}", response.statusCode());
        }
    }

    public void subscribeToCommands(String controlstreamId, Consumer<JsonObject> callback) {
        // MQTT subscription would be implemented here
        // For now, polling approach is used in the service
    }

    private String getAuthHeader() {
        if (nodeEnvData.getAuth() != null && "basic".equals(nodeEnvData.getAuth().getType())) {
            String credentials = nodeEnvData.getAuth().getUsername() + ":" + nodeEnvData.getAuth().getPassword();
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
        }
        return "";
    }

    private String extractIdFromLocation(String location) {
        if (location == null || location.isEmpty()) {
            return "";
        }
        String[] parts = location.split("/");
        return parts[parts.length - 1];
    }

    public NodeEnvData getNodeEnvData() {
        return nodeEnvData;
    }
}