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
import org.sensorhub.api.service.ServiceConfig;
import org.sensorhub.impl.service.consys.nats.subject.ConSysSubjectValidator;


/**
 * <p>
 * Configuration class for the Connected Systems API NATS service module.
 * </p><p>
 * The module always connects to a NATS server as a <i>client</i>. That server
 * can either be an external one (give its URL below) or a local one that this
 * module launches and manages itself (enable the embedded server section).
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


    @DisplayInfo(label="Node ID", desc="NATS subject namespace prefix for all subjects. Per OGC CS "
        + "API Part 3, subjects become '{nodeId}.systems.{id}' and "
        + "'{nodeId}.systems.{id}...observations:data'. Defaults to 'api'. Mirrors the MQTT "
        + "binding's nodeId (with '.' separators instead of '/').")
    public String nodeId = "api";


    @DisplayInfo(label="NATS Server URL", desc="URL of the external NATS server to connect to "
        + "(e.g. nats://localhost:4222). Ignored when the embedded server below is enabled, "
        + "in which case the module connects to the embedded server instead.")
    public String serverUrl = "nats://localhost:4222";


    @DisplayInfo(label="Username", desc="Username for NATS user/password authentication (optional)")
    public String username;


    @DisplayInfo(label="Password", desc="Password for NATS user/password authentication (optional)")
    public String password;


    @DisplayInfo(label="Auth Token", desc="Token for NATS token-based authentication "
        + "(optional, alternative to username/password)")
    public String token;


    @DisplayInfo(label="Connection Timeout (ms)", desc="Maximum time, in milliseconds, to wait "
        + "when establishing the NATS connection")
    public int connectionTimeoutMs = 5000;


    @DisplayInfo(label="Max Reconnects", desc="Maximum number of reconnection attempts the NATS "
        + "client will make if the connection is lost (-1 for unlimited)")
    public int maxReconnects = -1;


    @DisplayInfo(label="Data Streaming Mode", desc="PROACTIVE: OSH publishes live Resource Data to "
        + "the data subjects continuously for active streams (spec baseline; clients just "
        + "subscribe). ON_DEMAND: OSH streams a data subject only while a client has requested it "
        + "via the control channel (optional flow control).")
    public DataStreamingMode dataStreamingMode = DataStreamingMode.PROACTIVE;


    @DisplayInfo(label="On-Demand Lease (seconds)", desc="ON_DEMAND mode only: how long a "
        + "client's stream subscription stays alive without renewal. Clients renew by re-sending "
        + "the same control-channel subscribe request (the subscribe reply carries this value as "
        + "'leaseSeconds'). Prevents streams leaking forever when a client dies without "
        + "unsubscribing. 0 = leases never expire (explicit unsubscribe required).")
    public int onDemandLeaseSeconds = 300;


    @DisplayInfo(label="Command Relay Mode", desc="Hub/mirror nodes only: connect as the command "
        + "RECEIVER on every control stream and publish each submitted command to its "
        + "':commands:data' / ':commands:data.json' subjects immediately, so an external relay (e.g. the OSHConnect "
        + "broker) can forward it to the source node. This also makes commands get recorded in the "
        + "write database and auto-acknowledged PENDING. MUST stay false on nodes with local "
        + "drivers: only one command receiver may connect per stream, so this would fight the "
        + "driver's own connection (driver-backed streams fall back to the status-triggered echo, "
        + "but only if the driver connects first).")
    public boolean commandRelayMode = false;


    @DisplayInfo(label="Command Relay System UIDs", desc="Command relay mode only: glob patterns "
        + "(e.g. '*:mirror', 'urn:osh:system:remote:*') matched against a control stream's parent "
        + "system UID to decide PER STREAM whether to connect as the command receiver (relay) or "
        + "use the observe-only echo. Empty = relay every control stream. Lets a dual-role node "
        + "(local drivers + mirrored systems) relay only the mirrored streams while driver-backed "
        + "streams keep their drivers as the receiver.")
    public List<String> commandRelayUidPatterns = new ArrayList<>();


    @DisplayInfo(label="Origin Node UUID", desc="Fallback node identity for the CS-Origin-Node "
        + "provenance header when the nodehealth identity file (<moduleDataPath>/node-uuid) is "
        + "absent. Normally leave blank — the persisted identity file wins. With neither, the "
        + "header is omitted (one WARN) and provenance-based self-drop is disabled.")
    public String originNodeUuid;


    @DisplayInfo(label="Proactive Data Exclude System UIDs", desc="PROACTIVE mode: glob patterns "
        + "(e.g. '*:mirror', 'urn:osh:system:remote:*') matched against a datastream's parent "
        + "system UID. Matching systems get NO proactive observation data streams "
        + "(ingest-terminal publishing for mirrored/ingested systems — one copy per broker, "
        + "never republish what this node did not originate). Command/status streams and "
        + "resource event notifications are NOT affected. Systems mirrored by the NATS client "
        + "module are excluded automatically (origin registry); these globs cover mirrors "
        + "created by third-party relays.")
    public List<String> proactiveDataUidExcludePatterns = new ArrayList<>();


    @DisplayInfo(label="Proactive Data Formats", desc="Wire formats for proactively streamed "
        + "Resource Data. Each selected format is streamed simultaneously on its own "
        + "':data.<token>' subject, and the server-default format also feeds the bare ':data' "
        + "parent subject (as an extra default-format stream if the default is not in this list). "
        + "Empty list = server default only, resolved per datastream to a concrete token "
        + "('swe-binary' for binary-encoded streams, 'json' otherwise) and published on both "
        + "':data' and its resolved leaf. All messages carry a Content-Type header. Formats must "
        + "be served by the CS API for the datastream "
        + "(SWE_PROTO/SWE_FLATBUFFERS require their codec modules + custom-format registration).")
    public List<ProactiveFormat> proactiveDataFormats = new ArrayList<>();


    @DisplayInfo(label="JetStream", desc="Optional JetStream persistence settings")
    public JetStreamConfig jetStream = new JetStreamConfig();


    @DisplayInfo(label="Embedded Server", desc="Optional embedded/managed NATS server settings")
    public EmbeddedServerConfig embeddedServer = new EmbeddedServerConfig();


    /**
     * <p>
     * Optional JetStream persistence. When enabled, the module ensures a
     * JetStream stream exists that captures this node's subjects
     * ({@code <nodeId>.>}), so published messages are persisted and can be
     * replayed by durable consumers (and browsed in NUI's Streams tab).
     * </p><p>
     * Requires the NATS server to be started with JetStream enabled
     * ({@code nats-server -js}, or the embedded server with JetStream on).
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


    /**
     * <p>
     * Settings for an embedded (locally managed) NATS server.
     * </p><p>
     * NATS server is a native Go binary; there is no pure-Java NATS server, so
     * "embedded" here means this module launches and supervises a local
     * {@code nats-server} process and then connects to it as a client.
     * </p>
     */
    public static class EmbeddedServerConfig
    {
        @DisplayInfo(label="Enabled", desc="Set to true to launch and manage a local nats-server "
            + "process instead of connecting to the external server URL")
        public boolean enabled = false;


        @DisplayInfo(label="Executable Path", desc="Path to the nats-server executable. If left "
            + "blank, 'nats-server' is looked up on the system PATH.")
        public String executablePath;


        @DisplayInfo(label="Bind Address", desc="Address the embedded server binds to")
        public String host = "localhost";


        @DisplayInfo(label="Port", desc="Port the embedded server listens on")
        public int port = 4222;


        @DisplayInfo(label="Enable JetStream", desc="Start the embedded server with JetStream "
            + "(persistence) enabled")
        public boolean jetStream = false;


        @DisplayInfo(label="Startup Timeout (ms)", desc="Maximum time, in milliseconds, to wait "
            + "for the embedded server to become ready to accept connections")
        public int startupTimeoutMs = 10000;
    }


    public ConSysApiNatsServiceConfig()
    {
        this.moduleClass = ConSysApiNatsService.class.getCanonicalName();
    }
}
