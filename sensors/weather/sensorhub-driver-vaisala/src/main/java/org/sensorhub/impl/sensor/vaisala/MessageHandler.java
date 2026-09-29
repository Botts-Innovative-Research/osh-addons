package org.sensorhub.impl.sensor.vaisala;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.ObjLongConsumer;


public final class MessageHandler {
    private final InputStream msgIn;
    private final Writer output;
    private final ObjLongConsumer<String> measurements;
    private final Object commandLock = new Object();
    private volatile boolean running;
    private Thread readerThread;
    private Pending pending;
    private boolean publishing;
    private static final int MAX_LINE_LENGTH = 4096;

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private static final class ReceivedMessage {
        final String line;
        final long receivedAt;
        ReceivedMessage(String line, long receivedAt) {
            this.line = line;
            this.receivedAt = receivedAt;
        }
    }

    private static final class Pending {
        final String command;
        volatile String lastLine = "<none>";
        final CompletableFuture<String> reply = new CompletableFuture<>();
        Pending(String command) { this.command = command; }
    }

    public MessageHandler(InputStream msgIn, OutputStream output, ObjLongConsumer<String> measurements) {
        this.msgIn = msgIn;
        this.output = new OutputStreamWriter(output, StandardCharsets.US_ASCII);
        this.measurements = measurements;
    }

    public synchronized void start() {
        if (readerThread != null)
            throw new IllegalStateException("Create a new handler for each connection");
        running = true;
        readerThread = new Thread(this::readMessages, "vaisala-message-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public String sendAndAwait(String command, long timeoutMillis) throws IOException {
        if (timeoutMillis <= 0)
            throw new IllegalArgumentException("Command timeout must be positive");
        synchronized (commandLock) {
            Pending request = new Pending(command);
            synchronized (this) {
                if (!running) throw new IOException("Vaisala reader is not running");
                pending = request;
            }
            try {
                log.info("Vaisala command: {}", command);
                output.write(command + "\r\n");
                output.flush();
                return awaitReply(request, timeoutMillis);
            } catch (IOException e) {
                stop();
                throw e;
            } finally {
                synchronized (this) {
                    if (pending == request) pending = null;
                }
            }
        }
    }

    private String awaitReply(Pending request, long timeoutMillis) throws IOException {
        try {
            return request.reply.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for " + request.command, e);
        } catch (TimeoutException e) {
            throw new IOException("Timed out after " + timeoutMillis + " ms waiting for "
                    + request.command + "; last received line: " + request.lastLine, e);
        } catch (ExecutionException e) {
            throw new IOException("Vaisala command failed: " + request.command, e.getCause());
        }
    }

    private void readMessages() {
        try {
            while (running) {
                String line = readLine();
                handleMessage(new ReceivedMessage(line, System.currentTimeMillis()));
            }
        } catch (IOException e) {
            boolean unexpected = running;
            stop();

        } finally {
            stop();
        }
    }

    private synchronized void handleMessage(ReceivedMessage message) {
        if (!running) return;
        String line = message.line;
        if (pending != null) {
            pending.lastLine = line;
            log.debug("Vaisala received while waiting for {}: {}", pending.command, line);
        }
        if (isMeasurement(line)) {
            if (pending != null && "?".equals(pending.command))
                pending.reply.complete(line.substring(0, 1));
            handleMeasurement(message);
        } else {
            handleReply(line);
        }
    }

    private void handleReply(String line) {
        if (pending != null && matches(pending.command, line)) {
            pending.reply.complete(line);
        } else if (pending != null && line.startsWith(pending.command.substring(0, 1) + "TX,")) {
            pending.reply.completeExceptionally(new IOException("Device error: " + line));
        } else {
            log.debug("Unexpected Vaisala line: {}", line);
        }
    }

    private void handleMeasurement(ReceivedMessage message) {
        if (publishing) publish(message);
    }

    static boolean matches(String command, String line) {
        if ("?".equals(command)) return line.matches("[0-9A-Za-z]");
        String header = command.split(",", 2)[0];
        return line.startsWith(header + ",");
    }

    static boolean isMeasurement(String line) {
        return line.length() > 3 && line.charAt(1) == 'R'
                && Character.isDigit(line.charAt(2)) && line.charAt(3) == ',';
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        while (running) {
            int value = msgIn.read();
            if (value < 0) throw new EOFException("Vaisala serial stream closed");
            if (value == '\r' || value == '\n') {
                if (line.length() > 0) {
                    return line.toString();
                }
            } else {
                if (line.length() >= MAX_LINE_LENGTH) throw new IOException("Vaisala line exceeds 4096 characters");
                line.append((char)value);
            }
        }
        throw new IOException("Vaisala reader stopped");
    }

    public synchronized void enablePublishing() {
        if (!running) return;
        publishing = true;
    }

    private void publish(ReceivedMessage message) {
        try { measurements.accept(message.line, message.receivedAt); }
        catch (RuntimeException e) {
            log.warn("Invalid Vaisala measurement: {}", message.line, e);
        }
    }

    public synchronized void stop() {
        running = false;
        publishing = false;
        if (pending != null)
            pending.reply.completeExceptionally(new IOException("Vaisala reader stopped"));

        if (readerThread != null)
            readerThread.interrupt();
    }

    public boolean isRunning() { return running; }
}
