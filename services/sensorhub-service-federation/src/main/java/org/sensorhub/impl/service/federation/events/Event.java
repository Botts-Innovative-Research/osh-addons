package org.sensorhub.impl.service.federation.events;

import java.time.Instant;

public class Event {

    private Instant timestamp;
    private DefaultEventTypes type;
    private String topic;
    private Object data;
    private Object producer;

    public Event(Instant timestamp, DefaultEventTypes type, String topic, Object data, Object producer) {
        this.timestamp = timestamp;
        this.type = type;
        this.topic = topic;
        this.data = data;
        this.producer = producer;
    }

    public static Event blankEvent() {
        return new Event(
                Instant.now(),
                DefaultEventTypes.NEW_OBSERVATION,
                "",
                null,
                null
        );
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public DefaultEventTypes getType() {
        return type;
    }

    public void setType(DefaultEventTypes type) {
        this.type = type;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public Object getProducer() {
        return producer;
    }

    public void setProducer(Object producer) {
        this.producer = producer;
    }
}