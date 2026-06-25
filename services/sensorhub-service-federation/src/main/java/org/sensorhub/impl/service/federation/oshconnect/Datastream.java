package org.sensorhub.impl.service.federation.oshconnect;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

import com.google.gson.JsonObject;

/**
 * Port of oshconnect.streamableresource.Datastream for the surface the broker
 * uses: PULL-mode MQTT subscribe into an inbound deque, and observation insert.
 */
public class Datastream
{
    private final Node parentNode;
    private DatastreamResource underlyingResource;
    private final String resourceId;
    private String parentResourceId;

    private final Deque<byte[]> inboundDeque = new ConcurrentLinkedDeque<>();
    private final MqttCommClient mqttClient;
    private String topic;
    private StreamableModes connectionMode = StreamableModes.PUSH;

    public Datastream(Node parentNode, DatastreamResource datastreamResource)
    {
        this.parentNode = parentNode;
        this.mqttClient = parentNode != null ? parentNode.getMqttClient() : null;
        this.underlyingResource = datastreamResource;
        this.resourceId = datastreamResource.getDsId();
    }

    public String getId()
    {
        return underlyingResource.getDsId();
    }

    public DatastreamResource getResource()
    {
        return underlyingResource;
    }

    public void setConnectionMode(StreamableModes connectionMode)
    {
        this.connectionMode = connectionMode;
    }

    public String getTopic()
    {
        return topic;
    }

    public void setTopic(String topic)
    {
        this.topic = topic;
    }

    public void setParentResourceId(String resId)
    {
        this.parentResourceId = resId;
    }

    /** Build the WS URL and configure MQTT (we only need the topic + status). */
    public void initialize()
    {
        initMqtt();
    }

    /** Set the observation data topic (CS API Part 3 {@code :data} suffix). */
    public void initMqtt()
    {
        this.topic = parentNode.getApiHelper().getMqttTopic(
                APIResourceTypes.DATASTREAM, APIResourceTypes.OBSERVATION, resourceId, null, true);
    }

    public void start()
    {
        if (mqttClient != null)
        {
            if (connectionMode == StreamableModes.PULL || connectionMode == StreamableModes.BIDIRECTIONAL)
                mqttClient.subscribe(topic, 0, this::mqttSubCallback);
        }
    }

    /**
     * Subscribe to this datastream's observation MQTT topic. {@code topic} must
     * be {@code null} or {@code "Observation"}; both resolve to the data topic.
     */
    public void subscribe(String topic)
    {
        String t;
        if (topic == null || topic.equals(APIResourceTypes.OBSERVATION.value()))
            t = this.topic;
        else
            throw new IllegalArgumentException("Invalid topic provided " + topic + ", must be null or 'Observation'.");

        mqttClient.subscribe(t, 0, this::mqttSubCallback);
    }

    /** Default callback: append the raw payload to the inbound deque. */
    protected void mqttSubCallback(String msgTopic, byte[] payload)
    {
        inboundDeque.add(payload);
    }

    public Deque<byte[]> getInboundDeque()
    {
        return inboundDeque;
    }

    /**
     * POST an observation to {@code /datastreams/{id}/observations}.
     *
     * @throws RuntimeException if the server returns a non-OK response.
     */
    public String insertObservationDict(JsonObject obsData)
    {
        ApiResponse res = parentNode.getApiHelper().createResource(APIResourceTypes.OBSERVATION,
                obsData.toString(), resourceId, Map.of("Content-Type", "application/json"));
        if (res.ok())
        {
            String location = res.header("Location");
            return location.substring(location.lastIndexOf('/') + 1);
        }
        throw new RuntimeException("Failed to insert observation: " + res.text());
    }
}
