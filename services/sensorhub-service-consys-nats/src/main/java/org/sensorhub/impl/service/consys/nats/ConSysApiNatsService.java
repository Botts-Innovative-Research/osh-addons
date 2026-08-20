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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.service.IServiceModule;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.service.consys.ConSysApiService;
import org.sensorhub.impl.service.consys.nats.ConSysApiNatsServiceConfig.DataStreamingMode;
import org.sensorhub.impl.service.consys.nats.publish.ResourceDataPublisher;
import org.sensorhub.impl.service.consys.nats.publish.ResourceEventPublisher;
import org.sensorhub.impl.service.consys.nats.server.EmbeddedNatsServer;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;


/**
 * <p>
 * Add-on service to {@link ConSysApiService} that enables a NATS transport
 * binding for the OGC API - Connected Systems Pub/Sub interface (Part 3).
 * The NATS analogue of {@code ConSysApiMqttService}.
 * </p><p>
 * Unlike the MQTT binding (which attaches to a separate embedded-broker module),
 * this module manages its own NATS connection — to an external server or to a
 * locally launched/managed one (see {@link ConSysApiNatsServiceConfig}). On
 * attach it registers the {@link ConSysApiNatsConnector} (ingest + optional flow
 * control), starts the {@link ResourceEventPublisher} (CloudEvents lifecycle
 * notifications), and — in PROACTIVE mode — the {@link ResourceDataPublisher}
 * (live native-encoded data streaming).
 * </p>
 *
 * @author CR31
 * @since June 29, 2026
 */
public class ConSysApiNatsService extends AbstractModule<ConSysApiNatsServiceConfig> implements IServiceModule<ConSysApiNatsServiceConfig>
{
    protected EmbeddedNatsServer embeddedServer;
    protected Connection natsConnection;
    protected ConSysApiNatsConnector connector;
    protected ResourceEventPublisher resourceEventPublisher;
    protected ResourceDataPublisher resourceDataPublisher;


    @Override
    protected void doStart() throws SensorHubException
    {
        // we finish startup asynchronously once attached to the CS API service
        startAsync = true;
        reportStatus("Connecting to NATS server...");

        // 1. optionally launch the embedded/managed NATS server
        var serverUrl = config.serverUrl;
        if (config.embeddedServer != null && config.embeddedServer.enabled)
        {
            embeddedServer = new EmbeddedNatsServer(config.embeddedServer, getLogger());
            embeddedServer.start();
            serverUrl = embeddedServer.getServerUrl();
        }

        // 2. connect to the NATS server as a client
        try
        {
            natsConnection = connect(serverUrl);
            getLogger().info("Connected to NATS server at {}", serverUrl);
        }
        catch (Exception e)
        {
            throw new SensorHubException("Cannot connect to NATS server at " + serverUrl, e);
        }

        // optionally set up JetStream persistence for this node's subjects
        if (config.jetStream != null && config.jetStream.enabled)
            ensureJetStreamStream();

        // 3. attach to the CONSYS API REST service, then register handler + publishers
        reportStatus("Waiting for Connected Systems API service...");
        getParentHub().getModuleRegistry().waitForModuleType(ConSysApiService.class, ModuleState.STARTED)
            .thenAccept(service -> {
                try
                {
                    var servlet      = service.getServlet();
                    var eventBus     = getParentHub().getEventBus();
                    var idEncoders   = getParentHub().getIdEncoders();
                    var db           = getParentHub().getDatabaseRegistry().getFederatedDatabase();
                    var csApiBaseUrl = service.getPublicEndpointUrl();
                    var originNode   = resolveOriginNodeUuid();

                    // Inbound handler: ingest + (ON_DEMAND) flow-control control channel;
                    // db + idEncoders enable fingerprint-idempotent obs ingest
                    connector = new ConSysApiNatsConnector(
                        servlet, natsConnection, config.nodeId, config.username,
                        config.dataStreamingMode, config.onDemandLeaseSeconds,
                        db, idEncoders, originNode);
                    connector.start();

                    // Proactive CloudEvents Resource Event publisher (always on)
                    resourceEventPublisher = new ResourceEventPublisher(
                        natsConnection, config.nodeId, csApiBaseUrl, eventBus, db, idEncoders, getLogger());
                    resourceEventPublisher.start();
                    getLogger().info("CONSYS API NATS resource-event publisher started on nodeId '{}'", config.nodeId);

                    // Proactive Resource Data publisher (PROACTIVE mode only)
                    if (config.dataStreamingMode == DataStreamingMode.PROACTIVE)
                    {
                        var formatTokens = config.proactiveDataFormats == null ? List.<String>of() :
                            config.proactiveDataFormats.stream().filter(f -> f != null).map(f -> f.token).toList();

                        // Command relay mode needs the CS API's WRITE database (same resolution
                        // as ConSysApiService.doStart): connecting as a command receiver records
                        // each command via the transaction handler, which the read-only federated
                        // view can't do.
                        IObsSystemDatabase relayWriteDb = null;
                        if (config.commandRelayMode)
                        {
                            var csApiConfig = service.getConfiguration();
                            if (csApiConfig.databaseID != null && !csApiConfig.databaseID.isBlank())
                            {
                                relayWriteDb = (IObsSystemDatabase)getParentHub().getModuleRegistry()
                                    .getModuleById(csApiConfig.databaseID);
                                if (relayWriteDb != null && !relayWriteDb.isOpen())
                                    relayWriteDb = null;
                            }
                            else
                                relayWriteDb = getParentHub().getSystemDriverRegistry().getSystemStateDatabase();
                            if (relayWriteDb == null)
                                getLogger().warn("Command relay mode is on but no write database "
                                    + "could be resolved — falling back to status-triggered command echo");
                        }

                        resourceDataPublisher = new ResourceDataPublisher(
                            servlet, natsConnection, config.nodeId, eventBus, db, idEncoders,
                            config.username, formatTokens, relayWriteDb,
                            config.commandRelayUidPatterns,
                            config.proactiveDataUidExcludePatterns, originNode, getLogger());
                        resourceDataPublisher.start();
                        getLogger().info("CONSYS API NATS resource-data publisher started (PROACTIVE, formats={}{})",
                            config.proactiveDataFormats != null && !config.proactiveDataFormats.isEmpty()
                                ? config.proactiveDataFormats : "server-default",
                            (relayWriteDb != null
                                ? (config.commandRelayUidPatterns == null || config.commandRelayUidPatterns.isEmpty()
                                    ? ", command relay ON (all streams)"
                                    : ", command relay ON (UIDs " + config.commandRelayUidPatterns + ")")
                                : "")
                            + (config.proactiveDataUidExcludePatterns != null && !config.proactiveDataUidExcludePatterns.isEmpty()
                                ? ", obs publish excluded for UIDs " + config.proactiveDataUidExcludePatterns
                                : ""));
                    }
                    else
                    {
                        getLogger().info("CONSYS API NATS data streaming is ON_DEMAND (control channel '{}._control.*')", config.nodeId);
                    }

                    getLogger().info("CONSYS API NATS handler registered");
                    setState(ModuleState.STARTED);
                    clearStatus();

                    // prove messages are actually landing in JetStream
                    if (config.jetStream != null && config.jetStream.enabled)
                        scheduleJetStreamCheck();
                }
                catch (Exception e)
                {
                    reportError("Could not register CONSYS API NATS handler", e);
                }
            })
            // generous window: CS API startup can take minutes on a cold node
            // (GWT/protoc first builds, large H2 db recovery). The MQTT binding's
            // 30 s here proved too aggressive — a slow DB open killed the module.
            .orTimeout(10, TimeUnit.MINUTES)
            .exceptionally(e -> {
                reportError("Could not attach to CONSYS API service (waited 10 min)", e);
                return null;
            });
    }


    protected Connection connect(String serverUrl) throws Exception
    {
        var builder = new Options.Builder()
            .server(serverUrl)
            .connectionName("osh-consys-nats")
            .connectionTimeout(Duration.ofMillis(config.connectionTimeoutMs))
            .maxReconnects(config.maxReconnects);

        if (config.token != null && !config.token.isBlank())
        {
            builder.token(config.token.toCharArray());
        }
        else if (config.username != null && !config.username.isBlank())
        {
            var pwd = config.password != null ? config.password.toCharArray() : new char[0];
            builder.userInfo(config.username.toCharArray(), pwd);
        }

        return Nats.connect(builder.build());
    }


    /**
     * Create or update a JetStream stream that captures this node's resource
     * subjects. JetStream streams persist any message published to their
     * configured subjects (whether via core publish or the JetStream API), so
     * this alone makes the node's traffic durable and replayable. Failures
     * (e.g. server started without {@code -js}) are logged, not fatal.
     *
     * <p>The stream captures the CS API resource families (systems,
     * deployments, procedures, properties) rather than {@code <nodeId>.>}:
     * the {@code <nodeId>._control.*} channel must NOT be captured, because
     * JetStream acks any captured message that carries a reply subject, and
     * that ack would race ahead of the connector's own request-reply response
     * (e.g. on {@code _control.get}). Control traffic is also meaningless to
     * replay.</p>
     */
    /**
     * Resolve this node's identity UUID for the {@code CS-Origin-Node} header:
     * the nodehealth driver's persisted identity file wins, else the
     * {@code originNodeUuid} config fallback, else null (header omitted, one
     * WARN). Read-only — creating/persisting the identity is the nodehealth
     * driver's job (it is the singleton identity owner).
     */
    protected String resolveOriginNodeUuid()
    {
        try
        {
            var hubConfig = getParentHub().getConfig();
            var dataPath = hubConfig != null ? hubConfig.getModuleDataPath() : null;
            if (dataPath != null && !dataPath.isBlank())
            {
                var file = new java.io.File(dataPath, "node-uuid");
                if (file.isFile())
                {
                    var uuid = java.nio.file.Files.readString(file.toPath()).trim();
                    return java.util.UUID.fromString(uuid).toString();
                }
            }
        }
        catch (Exception e)
        {
            getLogger().warn("Cannot read node identity file: {}", e.getMessage());
        }

        if (config.originNodeUuid != null && !config.originNodeUuid.isBlank())
        {
            try
            {
                return java.util.UUID.fromString(config.originNodeUuid.trim()).toString();
            }
            catch (IllegalArgumentException e)
            {
                getLogger().warn("Invalid originNodeUuid '{}' — ignoring", config.originNodeUuid);
            }
        }

        getLogger().warn("No node identity — CS-Origin-Node header omitted and provenance-based "
            + "self-drop disabled (load the nodehealth driver or set originNodeUuid)");
        return null;
    }


    protected void ensureJetStreamStream()
    {
        var js = config.jetStream;
        try
        {
            var jsm = natsConnection.jetStreamManagement();

            var builder = StreamConfiguration.builder()
                .name(js.streamName)
                .subjects(
                    config.nodeId + ".systems.>",
                    config.nodeId + ".deployments.>",
                    config.nodeId + ".procedures.>",
                    config.nodeId + ".properties.>")
                .storageType(js.fileStorage ? StorageType.File : StorageType.Memory)
                .retentionPolicy(RetentionPolicy.Limits);
            if (js.maxAgeSeconds > 0)
                builder.maxAge(Duration.ofSeconds(js.maxAgeSeconds));
            if (js.maxMsgsPerSubject > 0)
                builder.maxMessagesPerSubject(js.maxMsgsPerSubject);
            var streamConfig = builder.build();

            if (jsm.getStreamNames().contains(js.streamName))
            {
                jsm.updateStream(streamConfig);
                getLogger().info("Updated JetStream stream '{}' (subjects {})", js.streamName, streamConfig.getSubjects());
            }
            else
            {
                jsm.addStream(streamConfig);
                getLogger().info("Created JetStream stream '{}' (subjects {})", js.streamName, streamConfig.getSubjects());
            }

            // confirm the stream is live and report its current state
            var info = jsm.getStreamInfo(js.streamName);
            getLogger().info("JetStream stream '{}' ready: subjects={}, storage={}, messages={}",
                js.streamName,
                info.getConfiguration().getSubjects(),
                info.getConfiguration().getStorageType(),
                info.getStreamState().getMsgCount());
        }
        catch (Exception e)
        {
            getLogger().warn("Could not set up JetStream stream '{}' — is the NATS server running "
                + "with JetStream enabled (nats-server -js)? Continuing without persistence. Cause: {}",
                js.streamName, e.getMessage());
        }
    }


    /**
     * A few seconds after startup, log how many messages the JetStream stream
     * has captured. A non-zero (and growing) count confirms the node's published
     * events/data are actually being persisted into the stream.
     */
    protected void scheduleJetStreamCheck()
    {
        var t = new Thread(() -> {
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            try
            {
                var info = natsConnection.jetStreamManagement().getStreamInfo(config.jetStream.streamName);
                var state = info.getStreamState();
                getLogger().info("JetStream '{}' is capturing the node's traffic: {} message(s), {} byte(s) stored",
                    config.jetStream.streamName, state.getMsgCount(), state.getByteCount());
                if (state.getMsgCount() == 0)
                    getLogger().warn("JetStream '{}' has 0 messages — check that publishers are running "
                        + "and that the stream subjects ('{}.systems.>' etc.) match what the node publishes.",
                        config.jetStream.streamName, config.nodeId);
            }
            catch (Exception e)
            {
                getLogger().warn("JetStream check failed for '{}': {}", config.jetStream.streamName, e.getMessage());
            }
        }, "nats-js-check");
        t.setDaemon(true);
        t.start();
    }


    @Override
    protected void doStop() throws SensorHubException
    {
        // stop publishers first
        if (resourceDataPublisher != null)
        {
            resourceDataPublisher.stop();
            resourceDataPublisher = null;
        }
        if (resourceEventPublisher != null)
        {
            resourceEventPublisher.stop();
            resourceEventPublisher = null;
        }

        // unregister inbound handler
        if (connector != null)
        {
            connector.stop();
            connector = null;
            getLogger().info("CONSYS API NATS handler unregistered");
        }

        // close the NATS connection
        if (natsConnection != null)
        {
            try
            {
                natsConnection.close();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            natsConnection = null;
        }

        // stop the embedded server if we launched one
        if (embeddedServer != null)
        {
            embeddedServer.stop();
            embeddedServer = null;
        }
    }
}
