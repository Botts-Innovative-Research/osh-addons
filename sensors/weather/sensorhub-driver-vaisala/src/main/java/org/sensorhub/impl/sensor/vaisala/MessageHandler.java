package org.sensorhub.impl.sensor.vaisala;
import java.io.*;
import org.slf4j.Logger;
public final class MessageHandler {
    private final InputStream input;
    private final Logger logger;
    private volatile boolean running;
    private Thread readerThread;
    public synchronized void start() {
    }
        StringBuilder line = new StringBuilder();

        running = false;

}
