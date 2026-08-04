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

import java.util.ArrayList;
import java.util.List;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.config.DisplayInfo.FieldType;
import org.sensorhub.api.config.DisplayInfo.FieldType.Type;
import org.sensorhub.api.service.ServiceConfig;
import org.sensorhub.impl.service.consys.nats.subject.ConSysSubjectValidator;


/**
 * <p>
 * Configuration class for the Connected Systems API NATS service module.
 * </p><p>
 * The module always connects to an external NATS server as a <i>client</i>
 * (there is no pure-Java NATS server to embed).
 * </p><p>
 * The configuration is validated when the module is initialized: invalid
 * values fail init with a message listing every problem at once, and settings
 * that are inert in the selected mode produce warnings (see
 * {@link ConSysApiNatsConfigValidator}).
 * </p>
 *
 * @author CR31
 * @since June 29, 2026
 */
public class ConSysApiNatsServiceConfig extends ServiceConfig
{

    /**
     * Resource Data streaming model for the NATS binding.
     *
     * <p>Per OGC CS API Part 3, flow control is an optional binding feature and
     * proactive publication is the baseline (Resource Events are always
     * published proactively).</p>
     */
    public enum DataStreamingMode
    {
        /** OSH publishes live Resource Data continuously to the data subjects (spec baseline). */
        PROACTIVE,
        /** OSH streams a data subject only while a client has requested it via the control channel (optional flow control). */
        ON_DEMAND
    }


    /**
     * Wire formats available for proactive Resource Data publishing. Mirrors
     * {@link ConSysSubjectValidator#FORMAT_SUBTOPICS} (the token set of the
     * {@code :data.<token>} content-negotiation subtopics). An enum (rather
     * than free strings) so the Admin UI renders a constrained picker: the
     * generic config form shows {@code List<Enum>} fields as a list with
     * add/remove buttons where Add opens an enum-constant selection popup.
     */
    public enum ProactiveFormat
    {
        JSON("json"),
        SWE_JSON("swe-json"),
        SWE_BINARY("swe-binary"),
        SWE_CSV("swe-csv"),
        SWE_PROTO("swe-proto"),
        SWE_FLATBUFFERS("swe-flatbuffers"),
        OM_JSON("om-json"),
        SML_JSON("sml-json");

        public final String token;

        ProactiveFormat(String token)
        {
            this.token = token;
        }

        @Override
        public String toString()
        {
            return token;
        }
    }


    @DisplayInfo(label="Node ID", desc="NATS subject namespace prefix for all subjects: a single "
        + "subject token (letters, digits, '_' and '-' only — no '.', '*' or '>'). Per OGC CS "
        + "API Part 3, subjects become '{nodeId}.systems.{id}' and "
        + "'{nodeId}.systems.{id}...observations:data'. Mirrors the MQTT binding's nodeId "
        + "(with '.' separators instead of '/').")
    public String nodeId = "api";


    @DisplayInfo(label="Origin Node UUID", desc="Fallback node identity for the CS-Origin-Node "
        + "provenance header when the nodehealth identity file (<moduleDataPath>/node-uuid) is "
        + "absent. Normally leave blank — the persisted identity file wins. With neither, the "
        + "header is omitted (one WARN) and provenance-based self-drop is disabled.")
    public String originNodeUuid;


    @DisplayInfo(label="OSH Acting User", desc="Local OSH user account this module acts as when "
        + "it calls the CS API internally (observation ingest, request-reply reads, command "
        + "relay). Blank = anonymous; must have the corresponding permissions when CS API access "
        + "control is enabled. Unrelated to the NATS server credentials below.")
    public String actingUser;


    @DisplayInfo(label="NATS Server", desc="NATS server address and client credentials")
    public NatsServerConfig server = new NatsServerConfig();


    @DisplayInfo(label="Data Streaming Mode", desc="PROACTIVE: OSH publishes live Resource Data to "
        + "the data subjects continuously for active streams (spec baseline; clients just "
        + "subscribe); the 'Proactive Streaming' section applies. ON_DEMAND: OSH streams a data "
        + "subject only while a client has requested it via the control channel (optional flow "
        + "control); the 'On-Demand Streaming' section applies.")
    public DataStreamingMode dataStreamingMode = DataStreamingMode.PROACTIVE;


    @DisplayInfo(label="Proactive Streaming", desc="PROACTIVE mode only (ignored in ON_DEMAND)")
    public ProactiveConfig proactive = new ProactiveConfig();


    @DisplayInfo(label="On-Demand Streaming", desc="ON_DEMAND mode only (ignored in PROACTIVE)")
    public OnDemandConfig onDemand = new OnDemandConfig();


    @DisplayInfo(label="JetStream", desc="Optional JetStream persistence settings")
    public JetStreamConfig jetStream = new JetStreamConfig();


    /**
     * <p>
     * NATS server connection settings. The credentials authenticate this
     * module against the NATS server only — they are unrelated to OSH user
     * accounts (see {@code actingUser} for the OSH side).
     * </p>
     */
    public static class NatsServerConfig
    {
        @DisplayInfo(label="Server URL", desc="URL of the NATS server to connect to "
            + "(e.g. nats://localhost:4222)")
        public String url = "nats://localhost:4222";


        @DisplayInfo(label="Username", desc="Username for NATS user/password authentication "
            + "(optional; leave blank for an unauthenticated server; mutually exclusive with "
            + "the auth token)")
        public String username;


        @FieldType(Type.PASSWORD)
        @DisplayInfo(label="Password", desc="Password for NATS user/password authentication")
        public String password;


        @FieldType(Type.PASSWORD)
        @DisplayInfo(label="Auth Token", desc="Token for NATS token-based authentication "
            + "(mutually exclusive with username/password)")
        public String authToken;


        @DisplayInfo(label="Connect Timeout (seconds)", desc="Maximum time to wait when "
            + "establishing the NATS connection. Reconnection attempts after a lost connection "
            + "are unlimited (not configurable — a transport that stops reconnecting is "
            + "silently dead).")
        public int connectTimeoutSeconds = 5;
    }


    /**
     * <p>
     * Settings that apply only in {@link DataStreamingMode#PROACTIVE} mode
     * (ignored, with a warning at init, in ON_DEMAND mode).
     * </p>
     */
    public static class ProactiveConfig
    {
        @DisplayInfo(label="Data Formats", desc="Wire formats for proactively streamed "
            + "Resource Data. Each selected format is streamed simultaneously on its own "
            + "':data.<token>' subject, and the server-default format also feeds the bare ':data' "
            + "parent subject (as an extra default-format stream if the default is not in this list). "
            + "Empty list = server default only, resolved per datastream to a concrete token "
            + "('swe-binary' for binary-encoded streams, 'json' otherwise) and published on both "
            + "':data' and its resolved leaf. All messages carry a Content-Type header. Formats must "
            + "be served by the CS API for the datastream "
            + "(SWE_PROTO/SWE_FLATBUFFERS require their codec modules + custom-format registration).")
        public List<ProactiveFormat> dataFormats = new ArrayList<>();


        @DisplayInfo(label="Exclude System UIDs", desc="Glob patterns matched against a "
            + "datastream's parent system UID ('*' is the only wildcard; every other character "
            + "is literal). Matching systems get NO proactive observation data streams "
            + "(ingest-terminal publishing for mirrored/ingested systems — one copy per broker, "
            + "never republish what this node did not originate). Empty = exclude nothing. "
            + "Command/status streams and resource event notifications are NOT affected. Systems "
            + "mirrored by the NATS client module are excluded automatically (origin registry); "
            + "these globs cover mirrors created by third-party relays.")
        public List<String> excludeSystemUids = new ArrayList<>();


        @DisplayInfo(label="Command Relay", desc="Hub/mirror nodes only — see the warnings inside")
        public CommandRelayConfig commandRelay = new CommandRelayConfig();
    }


    /**
     * <p>
     * Command relay settings (PROACTIVE mode only). Relay mode makes this
     * module connect as the command <i>receiver</i> on control streams, which
     * competes with local drivers for the single receiver slot per stream —
     * hence the UID scoping option.
     * </p>
     */
    public static class CommandRelayConfig
    {
        @DisplayInfo(label="Enabled", desc="Hub/mirror nodes only: connect as the command "
            + "RECEIVER on control streams and publish each submitted command to its "
            + "':commands:data' / ':commands:data.json' subjects immediately, so an external relay "
            + "(e.g. the OSHConnect broker) can forward it to the source node. Commands are then "
            + "recorded in the write database and auto-acknowledged PENDING. Only one command "
            + "receiver may connect per stream, so on nodes with local drivers scope this with "
            + "'Relay Only System UIDs' or leave it disabled (driver-backed streams fall back to "
            + "the status-triggered echo, but only if the driver connects first).")
        public boolean enabled = false;


        @DisplayInfo(label="Relay Only System UIDs", desc="Glob patterns matched against a "
            + "control stream's parent system UID ('*' is the only wildcard; every other "
            + "character is literal) to decide PER STREAM whether to connect as the command "
            + "receiver (relay) or use the observe-only echo. EMPTY = RELAY EVERY CONTROL "
            + "STREAM. Lets a dual-role node (local drivers + mirrored systems) relay only the "
            + "mirrored streams while driver-backed streams keep their drivers as the receiver.")
        public List<String> onlySystemUids = new ArrayList<>();
    }


    /**
     * <p>
     * Settings that apply only in {@link DataStreamingMode#ON_DEMAND} mode
     * (ignored, with a warning at init, in PROACTIVE mode).
     * </p>
     */
    public static class OnDemandConfig
    {
        @DisplayInfo(label="Lease (seconds)", desc="How long a client's stream subscription "
            + "stays alive without renewal. Clients renew by re-sending the same control-channel "
            + "subscribe request (the subscribe reply carries this value as 'leaseSeconds'). "
            + "Prevents streams leaking forever when a client dies without unsubscribing. "
            + "0 = leases never expire (explicit unsubscribe required).")
        public int leaseSeconds = 300;
    }


    /**
     * <p>
     * Optional JetStream persistence. When enabled, the module ensures a
     * JetStream stream exists that captures this node's subjects
     * ({@code <nodeId>.>}), so published messages are persisted and can be
     * replayed by durable consumers (and browsed in NUI's Streams tab).
     * </p><p>
     * Requires the NATS server to be started with JetStream enabled
     * ({@code nats-server -js}).
     * </p>
     */
    public static class JetStreamConfig
    {
        @DisplayInfo(label="Enabled", desc="Create/ensure a JetStream stream capturing this node's subjects")
        public boolean enabled = false;


        @DisplayInfo(label="Stream Name", desc="Name of the JetStream stream to create/ensure "
            + "(letters, digits, underscores, dashes only)")
        public String streamName = "CONSYS_API";


        @DisplayInfo(label="File Storage", desc="true = file storage (persists across restarts); "
            + "false = memory storage")
        public boolean fileStorage = true;


        @DisplayInfo(label="Max Age (seconds)", desc="Maximum age of retained messages, in seconds "
            + "(0 = unlimited). Bounds how much history JetStream keeps.")
        public int maxAgeSeconds = 3600;


        @DisplayInfo(label="Max Messages Per Subject", desc="Maximum number of messages retained "
            + "per subject (0 = unlimited). E.g. 1 keeps only the latest message on every "
            + "subject, turning the stream into a browsable last-value cache per datastream.")
        public int maxMsgsPerSubject = 0;
    }


    public ConSysApiNatsServiceConfig()
    {
        this.moduleClass = ConSysApiNatsService.class.getCanonicalName();
    }
}
