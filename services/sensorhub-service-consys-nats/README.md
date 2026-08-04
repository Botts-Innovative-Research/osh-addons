# Connected Systems API NATS Service

NATS pub/sub transport binding for the OGC API - Connected Systems Part 3 (Pub/Sub) draft,
built as a faithful port of the MQTT binding (`sensorhub-service-consys-mqtt`). The module
attaches to the Connected Systems API service and bridges it onto a NATS server: resource
lifecycle events and live data go out as NATS messages, and client publishes on the data
subjects are ingested through the CS API.

OSH always acts as a NATS **client** connecting to an external NATS server (`server.url`);
there is no pure-Java NATS server, so run one separately (e.g. the docker stack, or any
`nats-server`). Part 3 currently specifies only an MQTT binding, so the NATS subject
and control-channel protocol here is OSH-specific.

## Subject hierarchy

The prefix is `nodeId` (default `api`); then the CS API resource path, with MQTT's `/`, `+`, `#`
mapped to NATS `.`, `*`, `>`. Two families:

- **Resource Event subjects** (no suffix): `api.systems.{id}`,
  `api.systems.{id}.datastreams.{id}`, `…observations.{id}`, controlstreams/commands/status,
  subsystems, deployments, procedures, properties. CloudEvents v1.0 JSON notifications,
  server-published only; clients may not publish here.
- **Resource Data subjects** (`:data` or `:data.<format>`): native-encoded observation/command
  data. Clients publish to ingest; OSH publishes to stream. Format negotiation via
  `:data.swe-json`, `:data.swe-binary`, `:data.swe-proto`, etc.

Every published message carries a `Content-Type` header. The bare `:data` parent subject always
carries the server-default format alongside the explicit `:data.<token>` leafs, so wildcard
subscribers spanning both levels see the default-format payload twice and should pick one level
or dedupe.

## Streaming modes

- **PROACTIVE** (default, the Part 3 baseline): live observations stream continuously on
  `…observations:data.<fmt>` for every format in `proactive.dataFormats`, and live
  commands/status stream on `…controlstreams.{id}.commands:data[.json]` /
  `…status:data[.json]`. Clients just subscribe.
- **ON_DEMAND** (the spec's optional flow control): clients request streams via
  `<nodeId>._control.subscribe` / `unsubscribe` (body = the data subject). Subscriptions are
  leases (`onDemand.leaseSeconds`, renewed by re-subscribing); clients should send a stable
  `CS-Client-Id` header.

**Request-reply reads** (`<nodeId>._control.get`, both modes): the request body is a CS API
resource path with optional query (`datastreams/xyz/observations?limit=1`); the reply is the
GET response body. `f=` takes CS API short names (`json`, `geojson`, `sml3`, `sml2`) or mime
types, not `:data` tokens. Replies over the NATS max payload return an error suggesting a
narrower query.

## Ingest identity and no-republish

Observations are singular events; a node that ingests data from the bus must not multiply it
back onto the bus. Four cooperating mechanisms:

1. **`CS-Origin: server` echo skip**: the module tags its own publishes and skips them on
   ingest (an external NATS server echoes a publisher's messages back to its own subscription).
2. **`CS-Origin-Node` provenance header**: every data message carries the publishing node's
   identity UUID (from the nodehealth driver's persisted `<moduleDataPath>/node-uuid` file,
   else the `originNodeUuid` config field, else omitted with a warning). Inbound messages whose
   origin equals self are dropped unconditionally.
3. **Fingerprint-idempotent ingest**: an observation's identity is
   `(datastream, phenomenonTime at epoch-ms)`. Duplicates already present locally are acked ok
   and skipped. Binary formats (`swe-proto`, `swe-binary`) bypass the check (no cheap timestamp
   extraction); all checks fail open.
4. **Ingest-terminal publishing**: no proactive observation streams are opened for systems this
   node did not originate, via the ingest-origin registry (fed by the NATS client module) and
   the `proactive.excludeSystemUids` globs. Unresolvable UIDs fail open so a native system
   is never silenced by accident. Command/status streams and event notifications stay on for
   excluded systems (command passback needs them).

## Configuration

The configuration is validated when the module is initialized: invalid values fail init with a
message listing every problem at once (bad `nodeId`, malformed URL/UUID, conflicting NATS auth,
negative lease/limits), and settings that are inert in the selected streaming mode — or globs
that look like regexes — produce warnings. UID globs support `*` as the **only** wildcard;
every other character matches literally.

### Identity

| field           | default   | description |
|-----------------|-----------|-------------|
| `nodeId`        | `api`     | NATS subject namespace prefix: a single subject token (letters, digits, `_`, `-`). |
| `originNodeUuid`| *(unset)* | Fallback node identity for `CS-Origin-Node` when the nodehealth identity file is absent. Normally blank; the persisted file wins (a differing config value logs a warning). With neither, the header is omitted and provenance self-drop is disabled. |
| `actingUser`    | *(unset)* | Local OSH user account the module acts as for its internal CS API calls (ingest, request-reply reads, command relay). Blank = anonymous. Unrelated to the NATS credentials. |

### NATS server (`server` block)

| field                   | default                 | description |
|-------------------------|-------------------------|-------------|
| `url`                   | `nats://localhost:4222` | NATS server URL (`nats://`, `tls://`, …). |
| `username` / `password` | *(unset)*               | NATS user/password authentication. Mutually exclusive with `authToken`. |
| `authToken`             | *(unset)*               | NATS token authentication. Mutually exclusive with `username`/`password`. |
| `connectTimeoutSeconds` | `5`                     | Max wait when establishing the connection. Reconnects after a drop are always unlimited. |

### Data streaming

| field                        | default     | description |
|------------------------------|-------------|-------------|
| `dataStreamingMode`          | `PROACTIVE` | `PROACTIVE`: publish live data continuously (spec baseline); the `proactive` block applies. `ON_DEMAND`: stream only while a client holds a lease; the `onDemand` block applies. |
| `proactive.dataFormats`      | *(empty)*   | Formats streamed simultaneously, each on its own `:data.<token>` subject. Enum names in config: `JSON`, `SWE_JSON`, `SWE_BINARY`, `SWE_CSV`, `SWE_PROTO`, `SWE_FLATBUFFERS`, `OM_JSON`, `SML_JSON`. Empty = server default only (resolved per datastream: `swe-binary` for binary-encoded streams, `json` otherwise). The default format also feeds the bare `:data` subject. `SWE_PROTO`/`SWE_FLATBUFFERS` need their codec modules registered with the CS API. |
| `proactive.excludeSystemUids`| *(empty)*   | Globs against a datastream's parent system UID; matches get NO proactive observation streams. Command/status and event notifications unaffected. Systems mirrored by the NATS client are excluded automatically; these globs cover third-party relays. |
| `onDemand.leaseSeconds`      | `300`       | Lease lifetime without renewal. `0` = never expires. |

### Command relay (`proactive.commandRelay` block)

| field           | default   | description |
|-----------------|-----------|-------------|
| `enabled`       | `false`   | Hub/mirror nodes only: connect as the command RECEIVER on control streams and publish each submitted command immediately for an external relay. Only one receiver may connect per stream, so on nodes with local drivers scope with `onlySystemUids` or leave disabled (an unscoped relay next to local drivers logs a warning naming them). |
| `onlySystemUids`| *(empty)* | Globs against a control stream's parent system UID: relay only matching streams, others keep the observe-only echo. **Empty = relay every stream.** |

### JetStream (`jetStream` block)

When enabled, the module creates/updates a JetStream stream over the four resource subject
families (`<nodeId>.systems.>`, `.deployments.>`, `.procedures.>`, `.properties.>`; deliberately
not `_control.>`, whose JetStream acks would race the request-reply responses) before the
publishers start, so ordinary publishes are captured automatically. Requires the server to run
with JetStream on (`nats-server -js`).

| field               | default      | description |
|---------------------|--------------|-------------|
| `enabled`           | `false`      | Create/ensure the stream at startup. |
| `streamName`        | `CONSYS_API` | Stream name (letters, digits, underscores, dashes). |
| `fileStorage`       | `true`       | `true` = file storage (survives restarts); `false` = memory. |
| `maxAgeSeconds`     | `3600`       | Max age of retained messages (`0` = unlimited). |
| `maxMsgsPerSubject` | `0`          | Max messages per subject (`0` = unlimited; `1` = last-value cache). |

## Authorization

OSH cannot observe client subscriptions on an external NATS server, so subscribe authorization
is delegated to the NATS server (subject-level authz). OSH authorizes every publish (ingest) it
receives and, in ON_DEMAND mode, control-channel stream requests.
