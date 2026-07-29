/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.nats;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.sensorhub.impl.service.consys.ConSysApiServlet;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.nats.ConSysApiNatsServiceConfig.DataStreamingMode;
import org.sensorhub.impl.service.consys.nats.publish.NatsOutputStream;
import org.sensorhub.impl.service.consys.nats.publish.NatsStreamHandler;
import org.sensorhub.impl.service.consys.nats.subject.ConSysSubjectValidator;
import org.sensorhub.impl.service.consys.nats.subject.InvalidSubjectException;
import org.slf4j.Logger;
import org.vast.util.Asserts;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;


/**
 * <p>
 * Bridges a NATS connection to the Connected Systems API servlet, transferring
 * messages to/from the API for processing. This is the NATS analogue of
 * {@code ConSysApiMqttConnector} and follows the same OGC CS API Part 3 topic
 * (here: subject) hierarchy, with NATS syntax (see {@link ConSysSubjectValidator}).
 * </p>
 *
 * <p>Two subject categories are handled, exactly as in the MQTT binding:</p>
 * <ul>
 *   <li><b>Resource Data subjects</b> (end with {@code :data} / {@code :data.<fmt>}) —
 *       client publishes are ingested via the ConSys servlet ({@code doPost}); streaming
 *       to subscribers is handled either proactively by {@link ResourceDataPublisher}
 *       (PROACTIVE mode) or on demand via the control channel (ON_DEMAND mode).</li>
 *   <li><b>Resource Event subjects</b> (no {@code :data}) — CloudEvents lifecycle
 *       notifications published proactively by {@link ResourceEventPublisher}. Clients
 *       are barred from publishing on these (per Part 3 publisher restriction).</li>
 * </ul>
 *
 * <h3>NATS-specific behaviors (vs. the embedded-broker MQTT binding)</h3>
 * <ul>
 *   <li><b>Echo prevention:</b> messages carrying the {@code CS-Origin: server}
 *       header (published by OSH itself) are skipped on ingest, because an external
 *       NATS server echoes a publisher's messages back to its own wildcard
 *       subscription.</li>
 *   <li><b>Subscription authorization:</b> OSH cannot observe client subscriptions on
 *       an external NATS server, so authorization of <i>subscribe</i> on data and event
 *       subjects is delegated to the NATS server (subject-level authz). OSH still
 *       enforces authorization on every <i>publish</i> (ingest) it receives, and — in
 *       ON_DEMAND mode — on control-channel stream requests (via {@code doGet}).</li>
 * </ul>
 *
 * @author CR31
 * @since June 29, 2026
 */
public class ConSysApiNatsConnector
{
    static final String CONTROL_TOKEN = "_control";

    /** Header a client may set on control messages to identify itself across
     *  subscribe/renew/unsubscribe requests. Falls back to the request's
     *  reply-to inbox, then to a shared anonymous identity. */
    public static final String CLIENT_ID_HEADER = "CS-Client-Id";
    static final String ANONYMOUS_CLIENT = "anonymous";

    final ConSysApiServlet servlet;
    final Connection nats;
    final String nodeId;
    final String subjectPrefix;       // "{nodeId}."
    final String controlPrefix;       // "{nodeId}._control."
    final String subscribeSubject;    // "{nodeId}._control.subscribe"
    final String unsubscribeSubject;  // "{nodeId}._control.unsubscribe"
    final String getSubject;          // "{nodeId}._control.get"
    final String defaultUser;         // connection-level acting user; may be null => anonymous
    final DataStreamingMode mode;
    final int leaseSeconds;           // ON_DEMAND lease TTL; 0 = leases never expire
    final Logger log;

    Dispatcher dispatcher;
    ScheduledExecutorService leaseSweeper;

    // ON_DEMAND streaming state: active data streams keyed by data subject
    final Map<String, StreamEntry> streams = new ConcurrentHashMap<>();


    /**
     * One active ON_DEMAND data stream and the client leases keeping it alive.
     * A lease is created/refreshed by a subscribe request and dies on explicit
     * unsubscribe or expiry (client crashed without unsubscribing); the stream
     * is closed when its last lease is gone.
     */
    static class StreamEntry
    {
        final NatsStreamHandler handler;
        final Map<String, Long> leaseExpiryByClient = new ConcurrentHashMap<>();

        StreamEntry(NatsStreamHandler handler)
        {
            this.handler = handler;
        }
    }


    public ConSysApiNatsConnector(ConSysApiServlet servlet, Connection nats, String nodeId,
        String defaultUser, DataStreamingMode mode, int leaseSeconds)
    {
        this.servlet = Asserts.checkNotNull(servlet, ConSysApiServlet.class);
        this.nats = Asserts.checkNotNull(nats, Connection.class);
        this.nodeId = Asserts.checkNotNullOrEmpty(nodeId, "nodeId");
        this.defaultUser = defaultUser;
        this.mode = Asserts.checkNotNull(mode, DataStreamingMode.class);
        this.leaseSeconds = leaseSeconds;
        this.log = servlet.getLogger();

        this.subjectPrefix = nodeId + ".";
        this.controlPrefix = nodeId + "." + CONTROL_TOKEN + ".";
        this.subscribeSubject = controlPrefix + "subscribe";
        this.unsubscribeSubject = controlPrefix + "unsubscribe";
        this.getSubject = controlPrefix + "get";
    }


    public void start()
    {
        dispatcher = nats.createDispatcher(this::onMessage);
        dispatcher.subscribe(nodeId + ".>");

        if (mode == DataStreamingMode.ON_DEMAND && leaseSeconds > 0)
        {
            var sweepPeriod = Math.max(1, leaseSeconds / 2);
            leaseSweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "nats-ondemand-lease-sweeper");
                t.setDaemon(true);
                return t;
            });
            leaseSweeper.scheduleAtFixedRate(this::sweepExpiredLeases, sweepPeriod, sweepPeriod, TimeUnit.SECONDS);
        }

        log.info("CONSYS API NATS handler registered on subject root '{}.>' (mode={})", nodeId, mode);
    }


    /**
     * Single inbound entry point. Skips OSH's own (echoed) messages, then routes
     * control-channel, resource-data, and resource-event subjects.
     */
    void onMessage(Message msg)
    {
        // Echo prevention: ignore messages OSH published itself
        var headers = msg.getHeaders();
        if (headers != null && NatsOutputStream.ORIGIN_SERVER.equals(headers.getFirst(NatsOutputStream.ORIGIN_HEADER)))
            return;

        var subject = msg.getSubject();
        try
        {
            if (mode == DataStreamingMode.ON_DEMAND && subscribeSubject.equals(subject))
                onControlSubscribe(msg);
            else if (mode == DataStreamingMode.ON_DEMAND && unsubscribeSubject.equals(subject))
                onControlUnsubscribe(msg);
            else if (getSubject.equals(subject))
                onControlGet(msg);
            else if (subject.startsWith(controlPrefix))
                log.debug("Ignoring control subject {} (mode={})", subject, mode);
            else if (ConSysSubjectValidator.isDataSubject(subject))
                onPublish(msg);
            else
                onEventPublishAttempt(msg);
        }
        catch (Exception e)
        {
            log.error("Error handling NATS message on subject {}", subject, e);
            replyError(msg, "Internal error: " + e.getMessage());
        }
    }


    /**
     * Ingest an inbound Resource Data publish (client -> OSH), mapped to an API POST.
     * Mirrors {@code ConSysApiMqttConnector.onPublish}.
     */
    void onPublish(Message msg)
    {
        var subject = msg.getSubject();
        try
        {
            var ctx = new RequestContext(servlet, getResourceUri(subject),
                new ByteArrayInputStream(msg.getData()));
            var fmt = ConSysSubjectValidator.parseDataSubjectFormat(subject);
            ctx.setRequestContentType(fmt.orElse(ResourceFormat.AUTO).getMimeType());

            var headers = msg.getHeaders();
            if (headers != null)
            {
                var correl = headers.getFirst(NatsOutputStream.CORREL_ID_HEADER);
                if (correl != null && !correl.isBlank())
                {
                    long cmdId = Long.parseLong(correl);
                    if (cmdId != 0)
                        ctx.setCorrelationID(cmdId);
                }
            }

            setUser();
            servlet.getRootHandler().doPost(ctx);
            replyOk(msg);
        }
        catch (NumberFormatException e)
        {
            replyError(msg, "Invalid " + NatsOutputStream.CORREL_ID_HEADER + " header");
        }
        catch (InvalidSubjectException e)
        {
            log.warn("Publish to invalid subject {}: {}", subject, e.getMessage());
            replyError(msg, e.getMessage());
        }
        catch (InvalidRequestException | SecurityException e)
        {
            log.warn("Publish to {} rejected: {}", subject, e.getMessage());
            replyError(msg, e.getMessage());
        }
        catch (IOException e)
        {
            log.error("I/O error handling publish to {}", subject, e);
            replyError(msg, "Internal error: " + e.getMessage());
        }
        finally
        {
            clearUser();
        }
    }


    /**
     * Per OGC CS API Part 3, only the server may publish to Resource Event
     * subjects; clients are rejected.
     */
    void onEventPublishAttempt(Message msg)
    {
        log.warn("Client attempted to publish to resource event subject '{}' — rejected", msg.getSubject());
        replyError(msg, "Publishing to resource event subjects is not permitted");
    }


    /**
     * ON_DEMAND flow control: start (or join/renew) streaming a Resource Data
     * subject. The message body is the target data subject; OSH publishes the
     * stream to that same subject (identical hierarchy) and the client
     * subscribes to it.
     *
     * <p>Each subscribe creates or refreshes a client lease (see
     * {@link StreamEntry}). When leases expire ({@code leaseSeconds > 0}),
     * clients must re-send the same subscribe request before expiry to keep the
     * stream alive; the reply carries {@code leaseSeconds} so clients know the
     * renewal cadence. The CS API {@code doGet} (and its authorization check)
     * runs only when the stream is first created — subsequent joins/renewals
     * are equivalent since all inbound requests act as the same connection-level
     * user.</p>
     */
    void onControlSubscribe(Message msg)
    {
        var dataSubject = new String(msg.getData(), StandardCharsets.UTF_8).trim();

        if (!ConSysSubjectValidator.isDataSubject(dataSubject))
        {
            replyError(msg, "Not a resource data subject: " + dataSubject);
            return;
        }
        if (ConSysSubjectValidator.hasWildcard(dataSubject))
        {
            replyError(msg, "Wildcard data-stream subscriptions are not supported; "
                + "request an exact data subject");
            return;
        }

        var clientId = getClientId(msg);

        // Join or renew an existing stream — no second doGet, which would stack
        // a duplicate event-bus subscription onto the same handler
        var existing = streams.get(dataSubject);
        if (existing != null)
        {
            existing.leaseExpiryByClient.put(clientId, newLeaseExpiry());
            log.debug("Client '{}' joined/renewed NATS data stream {}", clientId, dataSubject);
            replySubscribed(msg);
            return;
        }

        // Parse the format token up front so published messages can carry a
        // Content-Type header (bare :data => server-default negotiation, no header)
        ResourceFormat format;
        try
        {
            format = ConSysSubjectValidator.parseDataSubjectFormat(dataSubject).orElse(null);
        }
        catch (InvalidSubjectException e)
        {
            replyError(msg, e.getMessage());
            return;
        }

        var entry = new StreamEntry(new NatsStreamHandler(nats, dataSubject,
            format != null ? format.getMimeType() : null));
        var raced = streams.putIfAbsent(dataSubject, entry);
        if (raced != null)
        {
            raced.leaseExpiryByClient.put(clientId, newLeaseExpiry());
            replySubscribed(msg);
            return;
        }

        try
        {
            var ctx = new RequestContext(servlet, getResourceUri(dataSubject), entry.handler);
            if (format != null)
                ctx.setResponseFormat(format);
            setUser();
            servlet.getRootHandler().doGet(ctx);
            entry.leaseExpiryByClient.put(clientId, newLeaseExpiry());
            entry.handler.maybeStart();
            log.debug("Client '{}' started NATS data stream {}", clientId, dataSubject);
            replySubscribed(msg);
        }
        catch (InvalidSubjectException | InvalidRequestException | SecurityException e)
        {
            streams.remove(dataSubject, entry);
            log.warn("Stream subscribe to {} rejected: {}", dataSubject, e.getMessage());
            replyError(msg, e.getMessage());
        }
        catch (Exception e)
        {
            streams.remove(dataSubject, entry);
            log.error("Internal error starting data stream {}", dataSubject, e);
            replyError(msg, "Internal error: " + e.getMessage());
        }
        finally
        {
            clearUser();
        }
    }


    /**
     * ON_DEMAND flow control: drop this client's lease on a Resource Data
     * subject; the stream stops when its last lease is gone. The message body
     * is the data subject.
     */
    void onControlUnsubscribe(Message msg)
    {
        var dataSubject = new String(msg.getData(), StandardCharsets.UTF_8).trim();
        var clientId = getClientId(msg);
        streams.computeIfPresent(dataSubject, (k, entry) -> {
            entry.leaseExpiryByClient.remove(clientId);
            if (entry.leaseExpiryByClient.isEmpty())
            {
                log.debug("No more clients on data stream {}. Stopping.", dataSubject);
                entry.handler.close();
                return null;
            }
            log.debug("{} client(s) still on data stream {}", entry.leaseExpiryByClient.size(), dataSubject);
            return entry;
        });
        replyOk(msg);
    }


    /**
     * NATS request-reply read: map a CS API {@code GET} onto the bus. The
     * message body is a resource path with optional query string (e.g.
     * {@code systems/abc1/datastreams/xyz2/observations?resultTime=latest&f=om-json});
     * the reply is the API response body (JSON unless the {@code f}/{@code format}
     * query param selects another encoding). Available in both streaming modes.
     * This is an OSH-specific extension (like the flow-control channel): NATS
     * request-reply has no MQTT equivalent, and it makes the whole CS API read
     * surface reachable without HTTP.
     */
    void onControlGet(Message msg)
    {
        var reply = msg.getReplyTo();
        if (reply == null || reply.isBlank())
        {
            log.debug("Ignoring {} message without a reply subject", getSubject);
            return;
        }

        var request = new String(msg.getData(), StandardCharsets.UTF_8).trim();
        if (ConSysSubjectValidator.hasWildcard(request))
        {
            replyError(msg, "Wildcards are not supported in read requests");
            return;
        }

        try
        {
            var qIdx = request.indexOf('?');
            var path = qIdx >= 0 ? request.substring(0, qIdx) : request;
            var query = qIdx >= 0 && qIdx < request.length() - 1 ? request.substring(qIdx + 1) : null;
            if (!path.startsWith("/"))
                path = "/" + path;

            var out = new ByteArrayOutputStream();
            var ctx = new RequestContext(servlet, new URI(null, null, path, query, null), out);
            setUser();
            servlet.getRootHandler().doGet(ctx);

            var maxPayload = nats.getMaxPayload();
            if (maxPayload > 0 && out.size() > maxPayload)
            {
                replyError(msg, "Response too large for NATS (" + out.size() + " bytes > max payload "
                    + maxPayload + "); narrow the query (e.g. add a 'limit' parameter)");
                return;
            }

            nats.publish(reply, out.toByteArray());
            log.debug("Answered NATS read request for {} ({} bytes)", path, out.size());
        }
        catch (URISyntaxException e)
        {
            replyError(msg, "Invalid resource path: " + request);
        }
        catch (InvalidRequestException | SecurityException e)
        {
            log.warn("NATS read request for '{}' rejected: {}", request, e.getMessage());
            replyError(msg, e.getMessage());
        }
        catch (Exception e)
        {
            log.error("Internal error handling NATS read request '{}'", request, e);
            replyError(msg, "Internal error: " + e.getMessage());
        }
        finally
        {
            clearUser();
        }
    }


    /**
     * Drop expired client leases and stop streams that no longer have any.
     * This reclaims streams whose clients died without unsubscribing.
     */
    void sweepExpiredLeases()
    {
        var now = System.currentTimeMillis();
        for (var dataSubject : streams.keySet())
        {
            streams.computeIfPresent(dataSubject, (k, entry) -> {
                entry.leaseExpiryByClient.values().removeIf(expiry -> expiry <= now);
                if (entry.leaseExpiryByClient.isEmpty())
                {
                    log.info("All leases on NATS data stream {} expired. Stopping.", dataSubject);
                    entry.handler.close();
                    return null;
                }
                return entry;
            });
        }
    }


    /**
     * Identify the requesting client for lease tracking: the
     * {@value #CLIENT_ID_HEADER} header if set, else the request's reply-to
     * inbox, else a shared anonymous identity. Note that without a client id,
     * distinct anonymous clients (or one client's per-request inboxes) cannot
     * be told apart, so explicit unsubscribe is only reliable for clients that
     * send a stable {@value #CLIENT_ID_HEADER}.
     */
    private String getClientId(Message msg)
    {
        var headers = msg.getHeaders();
        if (headers != null)
        {
            var id = headers.getFirst(CLIENT_ID_HEADER);
            if (id != null && !id.isBlank())
                return id;
        }
        var reply = msg.getReplyTo();
        if (reply != null && !reply.isBlank())
            return reply;
        return ANONYMOUS_CLIENT;
    }


    private long newLeaseExpiry()
    {
        return leaseSeconds > 0 ? System.currentTimeMillis() + leaseSeconds * 1000L : Long.MAX_VALUE;
    }


    /**
     * Map a NATS subject to a CS API resource URI: strip the nodeId prefix and
     * any {@code :data}/{@code :data.<fmt>} suffix, then translate NATS token
     * separators ({@code .}) back to path separators ({@code /}).
     */
    private URI getResourceUri(String subject) throws InvalidSubjectException
    {
        try
        {
            var path = subject;
            if (path.startsWith(subjectPrefix))
                path = path.substring(subjectPrefix.length());

            path = ConSysSubjectValidator.stripDataSuffix(path);
            var uriPath = "/" + path.replace('.', '/');
            return new URI(uriPath);
        }
        catch (URISyntaxException e)
        {
            throw new InvalidSubjectException("Invalid CS API resource subject: " + subject);
        }
    }


    private void setUser()
    {
        if (defaultUser != null && !defaultUser.isBlank())
            servlet.getSecurityHandler().setCurrentUser(defaultUser);
    }


    private void clearUser()
    {
        servlet.getSecurityHandler().clearCurrentUser();
    }


    private void replyOk(Message msg)
    {
        var reply = msg.getReplyTo();
        if (reply != null && !reply.isBlank())
            nats.publish(reply, "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
    }


    /**
     * Ack a subscribe/renew request, telling the client the lease TTL so it
     * knows how often to renew (0 = no expiry, renewal not required).
     */
    private void replySubscribed(Message msg)
    {
        var reply = msg.getReplyTo();
        if (reply != null && !reply.isBlank())
            nats.publish(reply, ("{\"status\":\"ok\",\"leaseSeconds\":" + leaseSeconds + "}")
                .getBytes(StandardCharsets.UTF_8));
    }


    private void replyError(Message msg, String errMsg)
    {
        var reply = msg.getReplyTo();
        if (reply != null && !reply.isBlank())
        {
            var safe = errMsg == null ? "error" : errMsg.replace("\\", "\\\\").replace("\"", "\\\"");
            nats.publish(reply, ("{\"error\":\"" + safe + "\"}").getBytes(StandardCharsets.UTF_8));
        }
    }


    public void stop()
    {
        if (leaseSweeper != null)
        {
            leaseSweeper.shutdownNow();
            leaseSweeper = null;
        }

        if (dispatcher != null)
        {
            try { nats.closeDispatcher(dispatcher); }
            catch (Exception e) { log.debug("Error closing NATS dispatcher", e); }
            dispatcher = null;
        }

        for (var entry : streams.values())
            entry.handler.close();
        streams.clear();
    }
}
