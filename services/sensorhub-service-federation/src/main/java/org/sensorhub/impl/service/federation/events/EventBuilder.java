package org.sensorhub.impl.service.federation.events;

import java.time.Instant;

public class EventBuilder {

    private Event event;

    public EventBuilder() {
        this.event = Event.blankEvent();
    }

    public EventBuilder withType(DefaultEventTypes eventType) {
        event.setType(eventType);
        return this;
    }

    public EventBuilder withTopic(String topic) {
        event.setTopic(topic);
        return this;
    }

    public EventBuilder withData(Object data) {
        event.setData(data);
        return this;
    }

    public EventBuilder withProducer(Object producer) {
        event.setProducer(producer);
        return this;
    }

    public EventBuilder withTimestamp(Instant timestamp) {
        event.setTimestamp(timestamp);
        return this;
    }

    public Event build() {
        Event built = new Event(
                event.getTimestamp(),
                event.getType(),
                event.getTopic(),
                event.getData(),
                event.getProducer()
        );
        reset();
        return built;
    }

    public void reset() {
        this.event = Event.blankEvent();
    }

    public static String createTopic(DefaultEventTypes baseType, String resourceId) {
        if (resourceId != null && !resourceId.isEmpty()) {
            return baseType.getValue() + "/" + resourceId;
        }
        return baseType.getValue();
    }
}