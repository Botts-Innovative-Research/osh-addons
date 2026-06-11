package org.sensorhub.impl.service.federation.events;

import java.util.List;

public interface IEventListener {

    List<String> getTopics();

    List<DefaultEventTypes> getTypes();

    void handleEvents(Event event);
}