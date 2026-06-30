package org.sensorhub.impl.sensor.rtmpcam.connection;

import org.sensorhub.impl.sensor.rtmpcam.config.ConnectionConfig;

import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

public class RtmpListenerManager {

    private static final RtmpListenerManager INSTANCE = new RtmpListenerManager();

    public static RtmpListenerManager getInstance() { return INSTANCE; }

    /**
     * All 16 bitmasks for the 4 optional fields (username, password, path, streamKey),
     * pre-sorted from most specific (0b1111 = all four) to least (0b0000 = none).
     * The router iterates these in order so the first map hit is always the best match.
     *
     * Bit layout: bit3=username, bit2=password, bit1=path, bit0=streamKey
     */
    private static final int[] CANDIDATE_MASKS = IntStream.range(0, 16)
            .boxed()
            .sorted(Comparator.comparingInt(Integer::bitCount).reversed())
            .mapToInt(Integer::intValue)
            .toArray();

    // One entry per unique composite key — second registration with the same
    // config overwrites the first (unlike the old CopyOnWriteArrayList).
    private final ConcurrentHashMap<String, RtmpListener> listeners =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Integer, RtmpPortServer> portServers =
            new ConcurrentHashMap<>();

    private final Object portLock = new Object();

    // ── Registration ───────────────────────────────────────────────────────

    public void addListener(RtmpListener listener) {
        listeners.put(listener.config().compositeKey(), listener);

        synchronized (portLock) {
            portServers.computeIfAbsent(listener.config().port, port -> {
                RtmpPortServer srv = new RtmpPortServer(port, this);
                srv.start();
                return srv;
            });
        }
    }

    public void removeListener(RtmpListener listener) {
        // Two-arg remove: only deletes if the value still matches this exact listener,
        // so a replacement registered under the same key isn't accidentally removed.
        listeners.remove(listener.config().compositeKey(), listener);

        int port = listener.config().port;
        synchronized (portLock) {
            boolean anyRemaining = listeners.values().stream()
                    .anyMatch(l -> l.config().port == port);
            if (!anyRemaining) {
                RtmpPortServer srv = portServers.remove(port);
                if (srv != null) srv.stop();
            }
        }
    }

    // ── Routing ────────────────────────────────────────────────────────────

    /**
     * Tries all 16 composite key variants for the connection in specificity
     * order (most fields → fewest fields) and returns the first map hit.
     *
     * A listener registered as "::1935::" (catch-all, specificity 0) is only
     * reached if no more-specific listener claims the connection.
     */
    Optional<RtmpListener> route(RtmpConnectionContext ctx) {
        String u  = ctx.username();
        String pw = ctx.password();
        String pa = ctx.path();
        String sk = ctx.streamKey();

        for (int mask : CANDIDATE_MASKS) {
            String key = ConnectionConfig.compositeKey(
                    (mask & 0b1000) != 0 ? u  : null,
                    (mask & 0b0100) != 0 ? pw : null,
                    ctx.port(),
                    (mask & 0b0010) != 0 ? pa : null,
                    (mask & 0b0001) != 0 ? sk : null);

            RtmpListener found = listeners.get(key);
            if (found != null) return Optional.of(found);
        }
        return Optional.empty();
    }
}