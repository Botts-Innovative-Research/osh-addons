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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.impl.service.consys.ConSysApiServlet;
import org.sensorhub.impl.service.consys.nats.ingest.IngestedCommandMemory;
import org.sensorhub.impl.service.consys.nats.ingest.ObsFingerprint;
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
    /** Idempotent-ingest support; null when no db was provided => dedupe off. */
    final IObsSystemDatabase db;
    final IdEncoders idEncoders;
    final ObsFingerprint fingerprint;
    /** This node's identity UUID (CS-Origin-Node provenance); null = self-drop disabled. */
    final String originNodeUuid;
    final Logger log;

    Dispatcher dispatcher;
    ScheduledExecutorService leaseSweeper;

    // ON_DEMAND streaming state: active data streams keyed by data subject
    final Map<String, StreamEntry> streams = new ConcurrentHashMap<>();

    // Idempotent-ingest memos: encoded ds id token -> internal id; internal id -> swe-json time field name
    final Map<String, BigId> dsIdByToken = new ConcurrentHashMap<>();
    final Map<BigId, Optional<String>> timeFieldByDsId = new ConcurrentHashMap<>();
    final Set<BigId> binarySkipLogged = ConcurrentHashMap.newKeySet();


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
        this(servlet, nats, nodeId, defaultUser, mode, leaseSeconds, null, null, null);
    }


    public ConSysApiNatsConnector(ConSysApiServlet servlet, Connection nats, String nodeId,
        String defaultUser, DataStreamingMode mode, int leaseSeconds,
        IObsSystemDatabase db, IdEncoders idEncoders)
    {
        this(servlet, nats, nodeId, defaultUser, mode, leaseSeconds, db, idEncoders, null);
    }


    /**
     * Full constructor. {@code db} + {@code idEncoders} enable fingerprint-idempotent
     * observation ingest: duplicate deliveries of the same observation are acked
     * ok and skipped instead of re-POSTed; null disables the check.
     * {@code originNodeUuid} is this node's identity for CS-Origin-Node provenance:
     * outbound streams stamp it, and inbound messages carrying it are dropped
     * (our own data coming back via any path); null disables both.
     */
    public ConSysApiNatsConnector(ConSysApiServlet servlet, Connection nats, String nodeId,
        String defaultUser, DataStreamingMode mode, int leaseSeconds,
        IObsSystemDatabase db, IdEncoders idEncoders, String originNodeUuid)
    {
        this.servlet = Asserts.checkNotNull(servlet, ConSysApiServlet.class);
        this.nats = Asserts.checkNotNull(nats, Connection.class);
        this.nodeId = Asserts.checkNotNullOrEmpty(nodeId, "nodeId");
        this.defaultUser = defaultUser;
        this.mode = Asserts.checkNotNull(mode, DataStreamingMode.class);
        this.leaseSeconds = leaseSeconds;
        this.db = db;
        this.idEncoders = idEncoders;
        this.fingerprint = (db != null && idEncoders != null) ? new ObsFingerprint(db, servlet.getLogger()) : null;
        this.originNodeUuid = originNodeUuid;
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

        // drop our own data coming back via any path (a relay, a bridge)
        if (originNodeUuid != null && headers != null
            && originNodeUuid.equals(headers.getFirst(NatsOutputStream.ORIGIN_NODE_HEADER)))
        {
            log.debug("Dropping self-originated message on {}", msg.getSubject());
            return;
        }

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
            // a duplicate delivery of an already-stored observation is acked
            // ok and skipped instead of re-POSTed
            var fp = checkObsFingerprint(subject, msg.getData());
            if (fp != null && fp.duplicate())
            {
                // INFO deliberately: a real duplicate delivery is rare and worth seeing
                // (replay, reconnect overlap, or a relay loop being terminated)
                log.info("Skipping duplicate observation on {}", subject);
                replyOk(msg);
                return;
            }

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
            // mark BEFORE the POST: osh-core publishes the obs event to the bus
            // before the store insert returns, and the mark must win that race
            // so the proactive publisher's egress check sees it
            if (fp != null)
                fingerprint.markIngested(fp.dsId(), fp.timeMs());
            // ingested commands are marked BEFORE the POST too: the relay-mode
            // publisher must not republish a command that arrived over the
            // broker (the external relay would forward it back to the source
            // and double-task the driver)
            var cmdMark = markIngestedCommand(subject, msg.getData());
            try
            {
                servlet.getRootHandler().doPost(ctx);
            }
            catch (Exception e)
            {
                if (fp != null)
                    fingerprint.unmarkIngested(fp.dsId(), fp.timeMs());
                if (cmdMark != null)
                    IngestedCommandMemory.unmark(cmdMark.csId(), cmdMark.timeMs());
                throw e;
            }
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


    /** Result of an obs-fingerprint check: the resolved fingerprint + whether
     *  it is already present (null overall = not applicable / fail open). */
    record FpCheck(BigId dsId, long timeMs, boolean duplicate) {}


    /** Identity of an ingested command marked in {@link IngestedCommandMemory}
     *  (null overall = not a command publish / no issueTime / fail open). */
    record CmdMark(BigId csId, long timeMs) {}


    /**
     * If this publish is a command carrying an explicit {@code issueTime},
     * mark its identity (control stream, issueTime@ms) so the relay-mode
     * publisher skips republishing it (ingest is terminal — the relay that
     * delivered it has already seen it). Fails open: any parse or decode
     * failure returns null and the command is republished as before.
     */
    CmdMark markIngestedCommand(String subject, byte[] payload)
    {
        try
        {
            // command data subjects only:
            // {nodeId}.systems.{sys}.controlstreams.{cs}.commands:data[.fmt]
            var tokens = subject.split("\\.");
            if (tokens.length < 6
                || !"systems".equals(tokens[1])
                || !"controlstreams".equals(tokens[3])
                || !tokens[5].startsWith("commands" + ConSysSubjectValidator.DATA_SUFFIX))
                return null;

            var timeMs = IngestedCommandMemory.extractIssueTimeMs(payload);
            if (timeMs == null)
                return null;

            var csId = idEncoders.getCommandStreamIdEncoder().decodeID(tokens[4]);
            IngestedCommandMemory.mark(csId, timeMs);
            return new CmdMark(csId, timeMs);
        }
        catch (Exception e)
        {
            log.debug("Command ingest-marking skipped on {}: {}", subject, e.getMessage());
            return null;
        }
    }


    /**
     * Resolve the fingerprint (datastream, phenomenonTime@ms) of an inbound
     * observation publish and report whether it already exists locally. Only
     * observation data subjects are checked (commands are intents, never
     * deduped); binary formats are skipped (time not cheaply extractable —
     * logged once per datastream); every failure fails OPEN (null result ⇒
     * the POST proceeds unchecked).
     */
    FpCheck checkObsFingerprint(String subject, byte[] payload)
    {
        if (fingerprint == null)
            return null;

        try
        {
            // observation data subjects only:
            // {nodeId}.systems.{sys}.datastreams.{ds}.observations:data[.fmt]
            var tokens = subject.split("\\.");
            if (tokens.length < 6
                || !"systems".equals(tokens[1])
                || !"datastreams".equals(tokens[3])
                || !tokens[5].startsWith("observations" + ConSysSubjectValidator.DATA_SUFFIX))
                return null;

            var dsId = dsIdByToken.computeIfAbsent(tokens[4],
                t -> idEncoders.getDataStreamIdEncoder().decodeID(t));

            // format token after ":data."; bare ":data" parses as server-default
            // json for non-binary streams — attempt json, extraction fails open
            var dataIdx = subject.lastIndexOf(ConSysSubjectValidator.DATA_SUFFIX + ".");
            var fmtToken = dataIdx >= 0
                ? subject.substring(dataIdx + ConSysSubjectValidator.DATA_SUFFIX.length() + 1)
                : "json";

            var timeField = "swe-json".equals(fmtToken)
                ? timeFieldByDsId.computeIfAbsent(dsId, id -> {
                    var dsInfo = db.getDataStreamStore().get(new DataStreamKey(id));
                    return Optional.ofNullable(dsInfo != null
                        ? ObsFingerprint.findTimeFieldName(dsInfo.getRecordStructure()) : null);
                }).orElse(null)
                : null;

            var timeMs = ObsFingerprint.extractPhenTimeMs(payload, fmtToken, timeField);
            if (timeMs == null)
            {
                if (ConSysSubjectValidator.FORMAT_SUBTOPICS.containsKey(fmtToken)
                    && !fmtToken.contains("json") && binarySkipLogged.add(dsId))
                    log.debug("Fingerprint check skipped for binary format '{}' on datastream {} "
                        + "(phenomenonTime not extractable — duplicates not detected on this path)",
                        fmtToken, tokens[4]);
                return null;
            }

            return new FpCheck(dsId, timeMs, fingerprint.exists(dsId, timeMs));
        }
        catch (Exception e)
        {
            log.debug("Fingerprint check failed on {} — proceeding with ingest: {}", subject, e.getMessage());
            return null;
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
