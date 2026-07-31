/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.nats.publish;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.sensorhub.api.command.CommandEvent;
import org.sensorhub.api.command.CommandStatusEvent;
import org.sensorhub.api.command.CommandStreamAddedEvent;
import org.sensorhub.api.command.CommandStreamEvent;
import org.sensorhub.api.command.CommandStreamRemovedEvent;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.data.DataStreamAddedEvent;
import org.sensorhub.api.data.DataStreamEvent;
import org.sensorhub.api.data.DataStreamRemovedEvent;
import org.sensorhub.api.data.ObsEvent;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.impl.system.SystemDatabaseTransactionHandler;
import org.sensorhub.impl.service.consys.ConSysApiServlet;
import org.sensorhub.impl.service.consys.task.CommandBindingJson;
import org.sensorhub.impl.service.consys.task.CommandHandler;
import org.sensorhub.impl.service.consys.nats.subject.ConSysSubjectValidator;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.slf4j.Logger;
import io.nats.client.Connection;
import net.opengis.swe.v20.BinaryEncoding;


/**
 * <p>
 * PROACTIVE-mode publisher of Resource Data Messages. For each datastream it
 * opens a streaming CS API {@code GET} on the observations collection with a
 * {@link NatsStreamHandler}, so live observations are serialized in the server's
 * native encoding and published continuously to the datastream's data subject
 * ({@code {nodeId}.systems.{id}.datastreams.{id}.observations:data.<format>}).
 * </p>
 *
 * <p>
 * This realizes the OGC CS API Part 3 baseline (proactive publication, no flow
 * control) for the canonical high-volume case. It is the data-message analogue
 * of {@link ResourceEventPublisher}: same enumerate-and-react-to-lifecycle
 * pattern, but it streams full native resource data via the servlet rather than
 * CloudEvents notifications.
 * </p>
 *
 * <p>
 * Streaming uses the exact same {@code servlet.doGet(...)} path the MQTT binding
 * drives from {@code onSubscribe}; only the trigger differs (proactive here vs.
 * broker subscribe-hook in MQTT).
 * </p>
 *
 * <p>
 * Command streams are covered too, mirroring the MQTT binding's observe mode: for
 * each control stream, live <b>commands</b> are echoed to
 * {@code …controlstreams.{id}.commands:data.json} and live command <b>status</b>
 * reports are streamed to {@code …controlstreams.{id}.status:data.json}, so a NATS
 * client that submits a command (ingest POST) can watch its acceptance/execution
 * without polling the REST API, and observers can see the commands themselves.
 * </p>
 *
 * <p>
 * Every resource data channel is served on both subject levels: the explicit
 * {@code :data.<token>} leafs (one per configured proactive format) and the bare
 * {@code :data} <b>parent</b> subject, which always carries the server-default
 * format for that resource — the token the CS API's AUTO negotiation would pick
 * ({@code swe-binary} for binary-encoded datastreams, {@code json} otherwise,
 * see {@link #resolveDefaultFormat}; always {@code json} for command/status,
 * their only binding). The default-format stream feeds the bare subject and its
 * own leaf from a single servlet stream (one serialization, two publishes); when
 * the default format is not among the configured leafs, an extra default-format
 * stream is opened for the bare subject alone. Every published message carries a
 * {@code Content-Type} header, so bare-subject subscribers can identify the wire
 * format without parsing the subject. Bare {@code :data} is also valid
 * <i>inbound</i> (client publishes with server-default parsing) and for ON_DEMAND
 * subscriptions, where the client chooses the subject.
 * </p>
 * <p>
 * The command echo is deliberately <b>not</b> implemented as a streaming GET on
 * {@code /commands}: that path connects the caller as a <i>command receiver</i>
 * ({@code CommandHandler.startRealTimeStream} →
 * {@code getCommandStreamHandler(id).connectCommandReceiver(...)}) whenever it
 * holds the {@code create} permission — which it always does with access control
 * off. For a driver-backed control stream that NPEs (the handler lookup returns
 * null) and, were it to resolve, would hijack command delivery from the actual
 * driver; for a mirrored (API-created) stream it would compete with the NATS
 * client's {@code CommandForwarder} receiver. Nor can it subscribe to the command
 * <i>data</i> EventBus topic: osh-core counts data-topic subscribers as THE
 * receiver-occupancy signal, so an early observer there blocks the driver's own
 * receiver connection on restart (see {@link #openCommandEchoStream}). Instead the
 * echo watches the command <b>status</b> topic and, on each command's first status
 * report, loads the recorded command from the store and serializes it with the CS
 * API's own {@code CommandBindingJson}, so the wire format matches a CS API
 * streaming GET. Published with {@code CS-Origin: server}, so the connector's echo
 * guard skips it on ingest. Command <i>results</i> have no streaming GET in the
 * CS API ({@code CommandResultHandler}), so there is no result data subject.
 * </p>
 *
 * @author CR31
 * @since June 29, 2026
 */
public class ResourceDataPublisher
{
    /** One proactive output encoding: a format token + its resolved ResourceFormat.
     *  A null token/format means server default, resolved per datastream to a
     *  concrete token at stream-open time (see {@link #resolveDefaultFormat}). */
    record OutputFormat(String token, ResourceFormat format) {}

    /** One servlet stream to open: an output encoding + the subject(s) it feeds
     *  (the default-format stream feeds both the bare {@code :data} parent
     *  subject and its own {@code :data.<token>} leaf). */
    record PlannedStream(OutputFormat output, List<String> subjects) {}

    final ConSysApiServlet servlet;
    final Connection nats;
    final String nodeId;
    final IEventBus eventBus;
    final IObsSystemDatabase db;
    final IdEncoders idEncoders;
    final String defaultUser;
    final List<OutputFormat> outputFormats; // one proactive stream per entry, per datastream
    /** Non-null = command relay mode: connect as the command RECEIVER per control
     *  stream and publish commands at submit time (see openCommandRelayStream).
     *  Must be the CS API's WRITE database — the receiver records each command. */
    final IObsSystemDatabase relayWriteDb;
    /** Relay-mode scoping: relay only control streams whose parent system UID
     *  matches one of these compiled glob patterns; empty = relay all (see
     *  {@link #shouldRelayCommands}). Non-matching streams use the echo. */
    final List<Pattern> relayUidPatterns;
    final Logger log;

    final List<Flow.Subscription> lifecycleSubscriptions = new ArrayList<>();

    /** Active proactive data streams (one per output format) keyed by datastream internal ID. */
    final Map<BigId, List<NatsStreamHandler>> streams = new ConcurrentHashMap<>();

    /** Active proactive command/status stream pairs keyed by command stream internal ID. */
    final Map<BigId, List<NatsStreamHandler>> cmdStreams = new ConcurrentHashMap<>();


    public ResourceDataPublisher(
        ConSysApiServlet servlet,
        Connection nats,
        String nodeId,
        IEventBus eventBus,
        IObsSystemDatabase db,
        IdEncoders idEncoders,
        String defaultUser,
        List<String> dataFormatTokens,
        IObsSystemDatabase relayWriteDb,
        List<String> relayUidGlobs,
        Logger log)
    {
        this.servlet = servlet;
        this.nats = nats;
        this.nodeId = nodeId;
        this.eventBus = eventBus;
        this.db = db;
        this.idEncoders = idEncoders;
        this.defaultUser = defaultUser;
        this.relayWriteDb = relayWriteDb;
        this.relayUidPatterns = compileGlobs(relayUidGlobs);
        this.log = log;

        var formats = new ArrayList<OutputFormat>();
        if (dataFormatTokens != null)
        {
            for (var token : dataFormatTokens)
            {
                if (token == null || token.isBlank())
                    continue;
                var fmt = ConSysSubjectValidator.FORMAT_SUBTOPICS.get(token);
                if (fmt == null)
                    log.warn("Unknown proactive data format token '{}' — skipping. Known: {}",
                        token, ConSysSubjectValidator.FORMAT_SUBTOPICS.keySet());
                else if (formats.stream().noneMatch(f -> token.equals(f.token())))
                    formats.add(new OutputFormat(token, fmt));
            }
        }
        // no valid tokens => single stream in the server-default encoding
        if (formats.isEmpty())
            formats.add(new OutputFormat(null, null));
        this.outputFormats = List.copyOf(formats);
    }


    /**
     * Compile relay-scope globs ({@code *} = any run of characters, everything
     * else literal) to regex patterns. Null/blank entries are ignored.
     * Package-private for tests.
     */
    static List<Pattern> compileGlobs(List<String> globs)
    {
        if (globs == null)
            return List.of();
        return globs.stream()
            .filter(g -> g != null && !g.isBlank())
            .map(g -> Pattern.compile(Arrays.stream(g.trim().split("\\*", -1))
                .map(part -> part.isEmpty() ? "" : Pattern.quote(part))
                .collect(Collectors.joining(".*"))))
            .toList();
    }


    /**
     * Relay-mode scope check for one control stream: relay only if the parent
     * system's UID matches a configured glob (empty pattern list = relay all).
     * A system whose UID can't be resolved is NOT relayed — the observe-only
     * echo is always safe, while wrongly grabbing the receiver slot is not.
     * Package-private for tests.
     */
    boolean shouldRelayCommands(BigId sysInternalId)
    {
        if (relayUidPatterns.isEmpty())
            return true;
        var sys = db.getSystemDescStore().getCurrentVersion(sysInternalId);
        var uid = sys != null ? sys.getUniqueIdentifier() : null;
        if (uid == null)
        {
            log.warn("Cannot resolve system UID for {} — using command echo, not relay", sysInternalId);
            return false;
        }
        for (var p : relayUidPatterns)
        {
            if (p.matcher(uid).matches())
                return true;
        }
        return false;
    }


    public void start()
    {
        // React to datastream/commandstream lifecycle so streams track add/remove
        subscribeToDataStreamLifecycle();
        subscribeToCommandStreamLifecycle();

        // Open streams for datastreams that already exist
        db.getDataStreamStore()
            .selectEntries(new DataStreamFilter.Builder().build())
            .forEach(e -> startStream(
                e.getKey().getInternalID(),
                e.getValue().getSystemID().getInternalID()));

        // Open command/status streams for command streams that already exist
        var csCount = new int[1];
        db.getCommandStreamStore()
            .selectEntries(new CommandStreamFilter.Builder().build())
            .forEach(e -> {
                csCount[0]++;
                startCommandStreams(
                    e.getKey().getInternalID(),
                    e.getValue().getSystemID().getInternalID());
            });
        log.info("Proactive publisher startup scan: {} existing command stream(s){}",
            csCount[0], relayWriteDb != null ? " (command relay mode)" : "");
    }


    public void stop()
    {
        for (var sub : lifecycleSubscriptions)
            sub.cancel();
        lifecycleSubscriptions.clear();

        for (var handlers : streams.values())
            for (var handler : handlers)
                handler.close();
        streams.clear();

        for (var handlers : cmdStreams.values())
            for (var handler : handlers)
                handler.close();
        cmdStreams.clear();
    }


    private void subscribeToDataStreamLifecycle()
    {
        eventBus.newSubscription(DataStreamEvent.class)
            .withTopicID(EventUtils.getSystemRegistryTopicID())
            .withEventType(DataStreamEvent.class)
            .subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription)
                {
                    lifecycleSubscriptions.add(subscription);
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(DataStreamEvent event)
                {
                    // ObsEvent is a DataStreamEvent subtype carrying data, not lifecycle
                    if (event instanceof ObsEvent)
                        return;
                    try
                    {
                        if (event instanceof DataStreamAddedEvent && event.getDataStreamID() != null)
                            startStream(event.getDataStreamID(), event.getSystemID());
                        else if (event instanceof DataStreamRemovedEvent && event.getDataStreamID() != null)
                            stopStream(event.getDataStreamID());
                    }
                    catch (Exception e)
                    {
                        log.error("Error updating proactive data streams from DataStreamEvent", e);
                    }
                }

                @Override
                public void onError(Throwable e)
                {
                    log.error("Error in ResourceDataPublisher lifecycle subscription", e);
                }

                @Override
                public void onComplete() {}
            });
    }


    private void subscribeToCommandStreamLifecycle()
    {
        eventBus.newSubscription(CommandStreamEvent.class)
            .withTopicID(EventUtils.getSystemRegistryTopicID())
            .withEventType(CommandStreamEvent.class)
            .subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription)
                {
                    lifecycleSubscriptions.add(subscription);
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(CommandStreamEvent event)
                {
                    // CommandEvent/CommandStatusEvent are CommandStreamEvent subtypes carrying data, not lifecycle
                    if (event instanceof CommandEvent || event instanceof CommandStatusEvent)
                        return;
                    try
                    {
                        if (event instanceof CommandStreamAddedEvent && event.getCommandStreamID() != null)
                            startCommandStreams(event.getCommandStreamID(), event.getSystemID());
                        else if (event instanceof CommandStreamRemovedEvent && event.getCommandStreamID() != null)
                            stopCommandStreams(event.getCommandStreamID());
                    }
                    catch (Exception e)
                    {
                        log.error("Error updating proactive command streams from CommandStreamEvent", e);
                    }
                }

                @Override
                public void onError(Throwable e)
                {
                    log.error("Error in ResourceDataPublisher command lifecycle subscription", e);
                }

                @Override
                public void onComplete() {}
            });
    }


    /** Package-private for tests. */
    void startStream(BigId dsInternalId, BigId sysInternalId)
    {
        if (streams.containsKey(dsInternalId))
            return;

        var sysId = idEncoders.getSystemIdEncoder().encodeID(sysInternalId);
        var dsId  = idEncoders.getDataStreamIdEncoder().encodeID(dsInternalId);
        var resourcePath = "/systems/" + sysId + "/datastreams/" + dsId + "/observations";
        var baseSubject  = nodeId + ".systems." + sysId + ".datastreams." + dsId + ".observations";

        // One stream per configured output format on its ":data.<token>" leaf,
        // with the server-default format also feeding the bare ":data" parent
        var plans = planStreams(
            baseSubject + ConSysSubjectValidator.DATA_SUFFIX,
            resolveDefaultFormat(dsInternalId));
        var handlers = new ArrayList<NatsStreamHandler>(plans.size());
        for (var plan : plans)
        {
            var handler = openStream(resourcePath, plan.subjects(), plan.output().format());
            if (handler != null)
                handlers.add(handler);
        }

        if (handlers.isEmpty())
        {
            log.warn("No proactive data stream could be opened for datastream {} — not published on NATS", baseSubject);
            return;
        }

        // Atomic register; if a duplicate raced ahead, close ours
        if (streams.putIfAbsent(dsInternalId, handlers) != null)
        {
            for (var handler : handlers)
                handler.close();
        }
        else
            log.debug("Started {} proactive NATS data stream(s) on {}", handlers.size(), baseSubject);
    }


    /**
     * Compute the streams to open for one data channel: each output format gets
     * its {@code :data.<token>} leaf subject, and the server-default format
     * additionally feeds the bare {@code :data} parent subject — piggybacked on
     * the matching configured stream when there is one, as an extra bare-only
     * stream otherwise. Package-private for tests.
     *
     * @param bareSubject the parent data subject (ends with {@code :data})
     * @param def the resolved server-default format for this channel
     */
    List<PlannedStream> planStreams(String bareSubject, OutputFormat def)
    {
        var plans = new ArrayList<PlannedStream>(outputFormats.size() + 1);
        var defaultCovered = false;
        for (var of : outputFormats)
        {
            var out = (of.format() != null) ? of : def;
            var subjects = new ArrayList<String>(2);
            if (!defaultCovered && out.token().equals(def.token()))
            {
                subjects.add(bareSubject);
                defaultCovered = true;
            }
            subjects.add(bareSubject + "." + out.token());
            plans.add(new PlannedStream(out, List.copyOf(subjects)));
        }
        if (!defaultCovered)
            plans.add(new PlannedStream(def, List.of(bareSubject)));
        return plans;
    }


    /**
     * Resolve the server-default output (empty {@code proactiveDataFormats}) to a
     * concrete token + format for one datastream, mirroring the CS API's own AUTO
     * negotiation for a non-browser streaming GET ({@code ObsHandler.getBinding}):
     * SWE binary for a {@link BinaryEncoding} datastream, OM JSON otherwise. This
     * is the format carried by the bare {@code :data} parent subject.
     */
    OutputFormat resolveDefaultFormat(BigId dsInternalId)
    {
        var dsInfo = db.getDataStreamStore().get(new DataStreamKey(dsInternalId));
        if (dsInfo != null && dsInfo.getRecordEncoding() instanceof BinaryEncoding)
            return new OutputFormat("swe-binary", ResourceFormat.SWE_BINARY);
        return new OutputFormat("json", ResourceFormat.JSON);
    }


    private void stopStream(BigId dsInternalId)
    {
        var handlers = streams.remove(dsInternalId);
        if (handlers != null)
        {
            for (var handler : handlers)
                handler.close();
            log.debug("Stopped proactive NATS data stream(s) for datastream {}", dsInternalId);
        }
    }


    private void startCommandStreams(BigId csInternalId, BigId sysInternalId)
    {
        if (cmdStreams.containsKey(csInternalId))
            return;

        var sysId = idEncoders.getSystemIdEncoder().encodeID(sysInternalId);
        var csId  = idEncoders.getCommandStreamIdEncoder().encodeID(csInternalId);
        var basePath    = "/systems/" + sysId + "/controlstreams/" + csId;
        var baseSubject = nodeId + ".systems." + sysId + ".controlstreams." + csId;

        // Command/status only have JSON bindings, so JSON is their server default:
        // each stream feeds both its bare ":data" parent subject and the explicit
        // ":data.json" leaf (same bytes, two publishes).
        var dataSuffix = ConSysSubjectValidator.DATA_SUFFIX;
        var jsonSuffix = dataSuffix + ".json";
        var handlers = new ArrayList<NatsStreamHandler>(2);

        // Live COMMAND publication. Two modes:
        // - relay (hub/mirror nodes, relayWriteDb != null): connect as THE command
        //   receiver — commands get recorded + auto-PENDING'd and published to NATS
        //   at submit time, so an external relay (the broker) can forward them.
        //   Without a receiver a mirrored stream's commands go nowhere: nothing
        //   stores them, nothing sends status, and the echo below never fires.
        // - echo (default, source nodes): observe-only via the command STATUS
        //   topic. Neither a streaming GET on /commands (connects as a command
        //   RECEIVER -> hijacks the driver's receiver slot) nor a subscription on
        //   the command DATA topic (osh-core counts data-topic subscribers as
        //   receiver occupancy, so an early observer blocks the driver's receiver
        //   connect on restart) is safe there. See openCommandEchoStream + javadoc.
        // Relay mode is scoped per stream by the parent system's UID (relayUidPatterns),
        // so a dual-role node can relay mirrored streams while its own driver-backed
        // streams keep the echo (and their drivers keep the receiver slot).
        var cmdSubjects = List.of(
            baseSubject + ".commands" + dataSuffix,
            baseSubject + ".commands" + jsonSuffix);
        var cmdHandler = relayWriteDb != null && shouldRelayCommands(sysInternalId)
            ? openCommandRelayStream(basePath + "/commands", cmdSubjects, csInternalId)
            : openCommandEchoStream(basePath + "/commands", cmdSubjects, csInternalId);
        if (cmdHandler != null)
            handlers.add(cmdHandler);

        // Live command STATUS — the normal streaming GET path (no receiver semantic).
        var statusHandler = openStream(
            basePath + "/status",
            List.of(baseSubject + ".status" + dataSuffix, baseSubject + ".status" + jsonSuffix),
            ResourceFormat.JSON);
        if (statusHandler != null)
            handlers.add(statusHandler);

        if (handlers.isEmpty())
            return;

        // Atomic register; if a duplicate raced ahead, close ours
        if (cmdStreams.putIfAbsent(csInternalId, handlers) != null)
        {
            for (var handler : handlers)
                handler.close();
        }
        else
            log.debug("Started proactive NATS command/status streams {}.(commands|status):data[.json]", baseSubject);
    }


    private void stopCommandStreams(BigId csInternalId)
    {
        var handlers = cmdStreams.remove(csInternalId);
        if (handlers != null)
        {
            for (var handler : handlers)
                handler.close();
            log.debug("Stopped proactive NATS command/status streams for control stream {}", csInternalId);
        }
    }


    /**
     * Command relay mode: connect as THE command receiver on one control stream and
     * publish every submitted command to {@code dataSubjects} at submit time. Meant
     * for hub/mirror nodes whose control streams have no local driver: being the
     * receiver makes osh-core record the command in the write DB, assign its ID, and
     * auto-send PENDING status on ack timeout — none of which happens otherwise. The
     * serialized wire format matches the status-triggered echo (CommandBindingJson).
     * If a receiver is already connected (a driver-backed stream on a mixed node),
     * falls back to the observe-only echo instead of fighting the driver.
     */
    private NatsStreamHandler openCommandRelayStream(String resourcePath, List<String> dataSubjects, BigId csInternalId)
    {
        NatsStreamHandler handler = null;
        try
        {
            var txnHandler = new SystemDatabaseTransactionHandler(eventBus, relayWriteDb);
            var csHandler = txnHandler.getCommandStreamHandler(csInternalId);
            if (csHandler == null)
            {
                log.warn("No command stream {} in write DB — skipping command relay stream", resourcePath);
                return null;
            }

            var natsHandler = new NatsStreamHandler(nats, dataSubjects, ResourceFormat.JSON.getMimeType());
            handler = natsHandler;
            var ctx = new RequestContext(servlet, new URI(resourcePath), natsHandler);
            ctx.setData(new CommandHandler.CommandHandlerContextData());
            var binding = new CommandBindingJson(ctx, idEncoders, false, relayWriteDb.getCommandStore());

            csHandler.connectCommandReceiver(new Flow.Subscriber<CommandEvent>() {
                Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription subscription)
                {
                    this.subscription = subscription;
                    subscription.request(Long.MAX_VALUE);
                    natsHandler.setCloseCallback(subscription::cancel);
                }

                @Override
                public void onNext(CommandEvent event)
                {
                    try
                    {
                        // ID was assigned by the receiver wrapper (store-add) before delivery
                        var cmd = event.getCommand();
                        binding.serialize(cmd.getID(), cmd, false);
                        natsHandler.sendPacket(event.getCorrelationID());
                    }
                    catch (Exception e)
                    {
                        log.error("Error relaying command to {}", dataSubjects, e);
                        subscription.cancel();
                    }
                }

                @Override
                public void onError(Throwable e)
                {
                    log.error("Command relay subscription error on {}", dataSubjects, e);
                }

                @Override
                public void onComplete()
                {
                    // control stream removed; handler closed via stopCommandStreams
                }
            });

            log.info("Command relay receiver connected for {} -> {}", resourcePath, dataSubjects);
            return natsHandler;
        }
        catch (IllegalStateException e)
        {
            // "A command receiver is already connected" — a driver owns this stream
            if (handler != null)
                handler.close();
            log.warn("Command receiver already connected for {} — using status-triggered echo instead", resourcePath);
            return openCommandEchoStream(resourcePath, dataSubjects, csInternalId);
        }
        catch (Exception e)
        {
            if (handler != null)
                handler.close();
            log.error("Failed to start command relay stream {} for resource {}", dataSubjects, resourcePath, e);
            return null;
        }
    }


    /**
     * Publish live commands of one control stream to {@code dataSubjects} via an
     * observe-only EventBus subscription on the command <b>status</b> topic — NOT a
     * streaming GET (which would connect as a command receiver, see
     * {@link #startCommandStreams}), and NOT a subscription on the command <i>data</i>
     * topic either: osh-core treats "any subscriber on the command data topic" as THE
     * receiver-occupancy signal ({@code CommandStreamTransactionHandler
     * .connectCommandReceiver} throws "A command receiver is already connected" when
     * {@code getNumberOfSubscribers(dataTopic) > 0}), so a data-topic observer opened
     * before the driver registers — e.g. on any node restart with a persisted DB,
     * where the binding's startup scan runs first — blocks the driver's own receiver
     * connection and fails the whole driver module. (Hit live on the axis node,
     * 2026-07-16.)
     * <p>
     * Instead we watch {@code CommandStatusEvent}s (the status topic is guard-free
     * and already multi-subscriber) and, on the first status report for each command,
     * load the recorded command from the command store and serialize it with the CS
     * API's own {@code CommandBindingJson} — same wire format as a CS API streaming
     * GET, including the {@code id} field. Closing the returned handler (e.g. from
     * {@link #stopCommandStreams}) cancels the subscription.
     */
    private NatsStreamHandler openCommandEchoStream(String resourcePath, List<String> dataSubjects, BigId csInternalId)
    {
        try
        {
            var csInfo = db.getCommandStore().getCommandStreams().get(new CommandStreamKey(csInternalId));
            if (csInfo == null)
            {
                log.warn("No command stream info for {} — skipping command echo stream", resourcePath);
                return null;
            }

            var handler = new NatsStreamHandler(nats, dataSubjects, ResourceFormat.JSON.getMimeType());
            var ctx = new RequestContext(servlet, new URI(resourcePath), handler);
            // empty context data => the binding creates params writers lazily, keyed
            // by each command's stream id (see CommandBindingJson.serialize)
            ctx.setData(new CommandHandler.CommandHandlerContextData());
            var binding = new CommandBindingJson(ctx, idEncoders, false, db.getCommandStore());

            // echo each command once, on its FIRST status report (bounded LRU dedupe)
            var echoedIds = Collections.newSetFromMap(Collections.synchronizedMap(
                new LinkedHashMap<BigId, Boolean>(16, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<BigId, Boolean> eldest)
                    {
                        return size() > 1000;
                    }
                }));

            var topic = EventUtils.getCommandStatusTopicID(csInfo);
            eventBus.newSubscription(CommandStatusEvent.class)
                .withTopicID(topic)
                .withEventType(CommandStatusEvent.class)
                .subscribe(new Flow.Subscriber<CommandStatusEvent>() {
                    Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription subscription)
                    {
                        this.subscription = subscription;
                        subscription.request(Long.MAX_VALUE);
                        handler.setCloseCallback(subscription::cancel);
                    }

                    @Override
                    public void onNext(CommandStatusEvent event)
                    {
                        try
                        {
                            var cmdId = event.getStatus().getCommandID();
                            if (cmdId == null || !echoedIds.add(cmdId))
                                return; // no id or already echoed
                            var cmd = db.getCommandStore().get(cmdId);
                            if (cmd == null)
                                return; // not recorded (yet) — nothing to echo
                            binding.serialize(cmdId, cmd, false);
                            handler.sendPacket(event.getCorrelationID());
                        }
                        catch (Exception e)
                        {
                            log.error("Error publishing command to {}", dataSubjects, e);
                            subscription.cancel();
                        }
                    }

                    @Override
                    public void onError(Throwable e)
                    {
                        log.error("Command echo subscription error on {}", dataSubjects, e);
                    }

                    @Override
                    public void onComplete()
                    {
                        // control stream removed; handler closed via stopCommandStreams
                    }
                });

            return handler;
        }
        catch (Exception e)
        {
            log.error("Failed to start command echo stream {} for resource {}", dataSubjects, resourcePath, e);
            return null;
        }
    }


    /**
     * Open a streaming CS API GET on {@code resourcePath}, publishing each packet
     * to every subject in {@code dataSubjects} tagged with the format's MIME type.
     * Returns the stream handler, or null if the stream could not be started.
     */
    private NatsStreamHandler openStream(String resourcePath, List<String> dataSubjects, ResourceFormat format)
    {
        NatsStreamHandler handler = null;
        try
        {
            handler = new NatsStreamHandler(nats, dataSubjects,
                format != null ? format.getMimeType() : null);
            var ctx = new RequestContext(servlet, new URI(resourcePath), handler);
            if (format != null)
                ctx.setResponseFormat(format);
            setUser();
            servlet.getRootHandler().doGet(ctx);
            handler.maybeStart();
            return handler;
        }
        catch (Exception e)
        {
            // the resource cannot be served in this format (or the stream failed
            // to open): skip just this stream — the resource's other formats keep
            // publishing. close() cancels any partially-established subscription.
            if (handler != null)
                handler.close();
            log.warn("Resource {} cannot be streamed in format {} — skipping subject(s) {}",
                resourcePath, format != null ? format : "(server default)", dataSubjects, e);
            return null;
        }
        finally
        {
            clearUser();
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
}
