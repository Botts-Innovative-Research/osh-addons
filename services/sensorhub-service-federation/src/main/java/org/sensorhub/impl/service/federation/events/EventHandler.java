package org.sensorhub.impl.service.federation.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventHandler {

    private static final Logger log = LoggerFactory.getLogger(EventHandler.class);

    private static EventHandler instance;

    private List<IEventListener> listeners;
    private List<IEventListener> toAdd;
    private List<IEventListener> toRemove;
    private Deque<Event> eventQueue;
    private boolean publishLock;

    private EventHandler() {
        this.listeners = new ArrayList<>();
        this.toAdd = new ArrayList<>();
        this.toRemove = new ArrayList<>();
        this.eventQueue = new ArrayDeque<>();
        this.publishLock = false;
    }

    public static synchronized EventHandler getInstance() {
        if (instance == null) {
            instance = new EventHandler();
        }
        return instance;
    }

    public void registerListener(IEventListener listener) {
        if (!listeners.contains(listener)) {
            if (!publishLock) {
                listeners.add(listener);
            } else {
                toAdd.add(listener);
            }
        }
    }

    public void unregisterListener(IEventListener listener) {
        if (!publishLock) {
            listeners.remove(listener);
        } else {
            toRemove.add(listener);
        }
    }

    public void publish(Event evt) {
        if (publishLock) {
            eventQueue.add(evt);
        } else {
            publishLock = true;

            try {
                for (IEventListener listener : listeners) {
                    listener.handleEvents(evt);
                }
            } catch (Exception e) {
                log.error("Error publishing event: {}", e.getMessage());
            } finally {
                publishLock = false;
                commitChanges();
            }
        }
    }

    private void commitChanges() {
        commitRemoves();
        commitAdds();

        while (!eventQueue.isEmpty()) {
            publish(eventQueue.pollFirst());
        }
    }

    private void commitAdds() {
        listeners.addAll(toAdd);
        toAdd.clear();
    }

    private void commitRemoves() {
        listeners.removeAll(toRemove);
        toRemove.clear();
    }

    public void clearListeners() {
        listeners.clear();
        toAdd.clear();
        toRemove.clear();
    }

    public int getNumListeners() {
        return listeners.size();
    }
}