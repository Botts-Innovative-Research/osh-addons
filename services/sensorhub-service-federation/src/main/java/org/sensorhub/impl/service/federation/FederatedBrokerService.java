package org.sensorhub.impl.service.federation;

import java.io.FileReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.service.federation.environment.EnvironmentData;
import org.sensorhub.impl.service.federation.environment.NodeEnvData;
import org.sensorhub.impl.service.federation.events.DefaultEventTypes;
import org.sensorhub.impl.service.federation.events.Event;
import org.sensorhub.impl.service.federation.events.EventHandler;
import org.sensorhub.impl.service.federation.nodes.CommanderNode;
import org.sensorhub.impl.service.federation.nodes.RemoteNode;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FederatedBrokerService extends AbstractModule<FederatedBrokerConfig> {

    private static final Logger log = LoggerFactory.getLogger(FederatedBrokerService.class);

    private EventHandler eventHandler;
    private EnvironmentData env;
    private List<CommanderNode> commandNodes;
    private List<RemoteNode> remoteNodes;
    private Map<String, DatastreamInfo> dsMap;
    private Map<String, ControlstreamInfo> csMap;
    private ExecutorService executorService;

    public FederatedBrokerService() {
        this.eventHandler = EventHandler.getInstance();
        this.commandNodes = new ArrayList<>();
        this.remoteNodes = new ArrayList<>();
        this.dsMap = new HashMap<>();
        this.csMap = new HashMap<>();
    }

    @Override
    protected void doInit() throws SensorHubException {
        super.doInit();
        log.info("Initializing OSH Data Broker Service...");
        this.executorService = Executors.newCachedThreadPool();
    }

    @Override
    protected void doStart() throws SensorHubException {
        super.doStart();
        log.info("Starting OSH Data Broker Service...");

        if (config.envFilePath != null && !config.envFilePath.isEmpty()) {
            loadEnvFile(config.envFilePath);
            discoverAll();
        }
    }

    @Override
    protected void doStop() throws SensorHubException {
        log.info("Stopping OSH Data Broker Service...");
        if (executorService != null) {
            executorService.shutdown();
        }
        super.doStop();
    }

    public void loadEnvFile(String filePath) {
        try (FileReader reader = new FileReader(filePath)) {
            Gson gson = new Gson();
            this.env = gson.fromJson(reader, EnvironmentData.class);

            log.info("Loading environment configuration...");

            for (NodeEnvData node : env.getNodes()) {
                int mqttPort = node.getMqttPort();

                String role = node.isCommander() ? "COMMANDER" : "REMOTE";
                log.info("  {}: {}:{} (MQTT:{})", role, node.getAddress(), node.getPort(), mqttPort);

                if (node.isCommander()) {
                    CommanderNode cmdNode = new CommanderNode(node);
                    commandNodes.add(cmdNode);
                    eventHandler.publish(new Event(
                            Instant.now(),
                            DefaultEventTypes.ADD_COMMANDER,
                            DefaultEventTypes.ADD_COMMANDER.getValue(),
                            node,
                            this
                    ));
                } else {
                    RemoteNode rmtNode = new RemoteNode(node);
                    remoteNodes.add(rmtNode);
                    eventHandler.publish(new Event(
                            Instant.now(),
                            DefaultEventTypes.ADD_REMOTE_NODE,
                            DefaultEventTypes.ADD_REMOTE_NODE.getValue(),
                            node,
                            this
                    ));
                }
            }

            log.info("Loaded {} commander(s), {} remote node(s)", commandNodes.size(), remoteNodes.size());

        } catch (Exception e) {
            log.error("Failed to load environment file: {}", e.getMessage());
        }
    }

    public void discoverAll() {
        if (env == null) {
            throw new IllegalStateException("Environment not loaded");
        }
        if (commandNodes.isEmpty()) {
            return;
        }

        log.info("==================================================");
        log.info("Starting discovery and mirroring...");
        log.info("==================================================");

        log.info("Phase 1: Systems");
        discoverAndMirrorSystems();

        log.info("Phase 2: Datastreams");
        discoverAndMirrorDatastreams();

        log.info("Phase 3: Control Streams");
        discoverAndMirrorControlstreams();

        log.info("Discovery complete. Waiting 5s for stabilization...");
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void discoverAndMirrorSystems() {
        if (commandNodes.isEmpty()) {
            log.warn("[discover] no commander nodes configured; skipping systems");
            return;
        }

        CommanderNode commander = commandNodes.get(0);
        log.info("[DISCOVER] using commander at {}:{}",
                commander.getNodeEnvData().getAddress(),
                commander.getNodeEnvData().getPort());

        List<SystemInfo> remoteSystems = discoverRemoteSystems();
        log.info("[DISCOVER] discovered {} remote systems", remoteSystems.size());
        mirrorSystemsToCommander(commander, remoteSystems);
    }

    private List<SystemInfo> discoverRemoteSystems() {
        List<SystemInfo> allSystems = new ArrayList<>();

        for (RemoteNode rmtNode : remoteNodes) {
            try {
                List<SystemInfo> systems = rmtNode.discoverSystems();
                allSystems.addAll(systems);
            } catch (Exception e) {
                log.warn("Failed to discover systems from remote node: {}", e.getMessage());
            }
        }

        return allSystems;
    }

    private void mirrorSystemsToCommander(CommanderNode commander, List<SystemInfo> remoteSystems) {
        log.info("Mirroring {} system(s) to commander", remoteSystems.size());
        int mirroredCount = 0;

        for (SystemInfo system : remoteSystems) {
            try {
                String mirroredId = commander.createSystem(system);
                system.setMirroredId(mirroredId);
                mirroredCount++;
                log.info("  Mirrored system: {}", system.getUrn());
            } catch (Exception e) {
                log.error("  Failed to mirror system {}: {}", system.getUrn(), e.getMessage());
            }
        }

        log.info("Mirrored {} system(s)", mirroredCount);
    }

    private void discoverAndMirrorDatastreams() {
        List<DatastreamInfo> remoteDatastreams = discoverRemoteDatastreams();
        subscribeToRemoteObservations(remoteDatastreams);

        if (commandNodes.isEmpty()) {
            log.warn("No commander nodes configured; skipping datastream mirror");
            return;
        }

        CommanderNode commander = commandNodes.get(0);
        mirrorDatastreamsToCommander(commander, remoteDatastreams);
        startObservationPumps(remoteDatastreams);
    }

    private List<DatastreamInfo> discoverRemoteDatastreams() {
        List<DatastreamInfo> discovered = new ArrayList<>();

        for (RemoteNode rmtNode : remoteNodes) {
            try {
                List<SystemInfo> systems = rmtNode.discoverSystems();
                for (SystemInfo sys : systems) {
                    List<DatastreamInfo> datastreams = rmtNode.discoverDatastreams(sys.getId());
                    for (DatastreamInfo ds : datastreams) {
                        ds.setParentSystem(sys);
                        ds.setRemoteNode(rmtNode);
                        discovered.add(ds);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to discover datastreams: {}", e.getMessage());
            }
        }

        log.info("Discovered {} remote datastream(s)", discovered.size());
        return discovered;
    }

    private void subscribeToRemoteObservations(List<DatastreamInfo> datastreams) {
        for (DatastreamInfo ds : datastreams) {
            try {
                ds.getRemoteNode().subscribeToObservations(ds.getId(), this::handleIncomingObservation);
            } catch (Exception e) {
                log.error("Failed to subscribe to datastream {}: {}", ds.getId(), e.getMessage());
            }
        }
        log.info("Subscribed to observations for {} datastream(s)", datastreams.size());
    }

    private void mirrorDatastreamsToCommander(CommanderNode commander, List<DatastreamInfo> datastreams) {
        int mirroredCount = 0;

        for (DatastreamInfo ds : datastreams) {
            SystemInfo parentSys = ds.getParentSystem();
            if (parentSys == null || parentSys.getMirroredId() == null) {
                log.debug("No commander system for datastream {}", ds.getId());
                continue;
            }

//            if (ds.getRecordSchema() == null) {
//                log.warn("Skipping datastream {}: source has no record_schema", ds.getName());
//                continue;
//            }

            try {
                String mirroredId = commander.createDatastream(ds);
                dsMap.put(ds.getId(), ds);
                ds.setMirroredId(mirroredId);
                mirroredCount++;
                log.debug("Mirrored datastream: {}", ds.getName());
            } catch (Exception e) {
                log.error("Failed to create datastream {} on commander: {}", ds.getName(), e.getMessage());
            }
        }

        log.info("Mirrored {} datastream(s)", mirroredCount);
    }

    private void startObservationPumps(List<DatastreamInfo> datastreams) {
        for (DatastreamInfo ds : datastreams) {
            executorService.submit(() -> observationRetrievalLoop(ds));
        }
        log.info("Started observation pumps for {} datastream(s)", datastreams.size());
    }

    private void observationRetrievalLoop(DatastreamInfo datastream) {
        String dsId = datastream.getId();
        ConcurrentLinkedDeque<JsonObject> inbound = datastream.getInboundDeque();
        int checkCount = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                checkCount++;

                if (checkCount % 100 == 0) {
                    log.debug("ds={} deque_len={}", dsId, inbound.size());
                }

                if (!inbound.isEmpty()) {
                    JsonObject obs = inbound.pollFirst();
                    if (obs != null) {
                        List<JsonObject> obsData = parseObservation(obs, dsId);
                        if (obsData != null) {
                            for (JsonObject item : obsData) {
                                routeObservationToCommanderDatastream(dsId, item);
                            }
                        }
                    }
                } else {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Observation retrieval error for {}: {}", dsId, e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private List<JsonObject> parseObservation(JsonObject obs, String dsId) {
        List<JsonObject> items = new ArrayList<>();

        if (obs.has("items") && obs.get("items").isJsonArray()) {
            for (var item : obs.getAsJsonArray("items")) {
                if (item.isJsonObject()) {
                    items.add(item.getAsJsonObject());
                }
            }
        } else if (obs.has("resultTime") || obs.has("phenomenonTime") || obs.has("result")) {
            items.add(obs);
        }

        return items.isEmpty() ? null : items;
    }

    private void handleIncomingObservation(String datastreamId, JsonObject observation) {
        routeObservationToCommanderDatastream(datastreamId, observation);
    }

    public void routeObservationToCommanderDatastream(String remoteDsId, JsonObject observation) {
        DatastreamInfo dsInfo = dsMap.get(remoteDsId);
        if (dsInfo == null) {
            log.debug("No mirrored datastream for {}", remoteDsId);
            return;
        }

        if (commandNodes.isEmpty()) {
            return;
        }

        CommanderNode commander = commandNodes.get(0);
        try {
            JsonObject body = buildInsertObservationDict(dsInfo, observation);
            commander.postObservation(dsInfo.getMirroredId(), body);
            log.debug("Inserted observation for {}", remoteDsId);
        } catch (Exception e) {
            log.error("Failed to insert observation for {}: {}", remoteDsId, e.getMessage());
        }
    }

    private JsonObject buildInsertObservationDict(DatastreamInfo commanderDs, JsonObject item) {
        String phenomenonTime = item.has("phenomenonTime") ? item.get("phenomenonTime").getAsString() : null;
        String resultTime = item.has("resultTime") ? item.get("resultTime").getAsString() : phenomenonTime;

        if (phenomenonTime == null || resultTime == null) {
            String isoNow = Instant.now().toString();
            phenomenonTime = phenomenonTime != null ? phenomenonTime : isoNow;
            resultTime = resultTime != null ? resultTime : isoNow;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("resultTime", resultTime);
        payload.addProperty("phenomenonTime", phenomenonTime);

        if (item.has("result")) {
            payload.add("result", item.get("result"));
        } else {
            payload.add("result", new JsonObject());
        }

        return payload;
    }

    private void discoverAndMirrorControlstreams() {
        List<ControlstreamInfo> remoteControlstreams = discoverRemoteControlstreams();

        if (commandNodes.isEmpty()) {
            log.warn("[CONTROLSTREAM] no commander nodes configured; skipping control streams");
            return;
        }

        CommanderNode commander = commandNodes.get(0);
        log.info("[CONTROLSTREAM] Mirroring {} control stream(s) to commander", remoteControlstreams.size());
        mirrorControlstreamsToCommander(commander, remoteControlstreams);
    }

    private List<ControlstreamInfo> discoverRemoteControlstreams() {
        List<ControlstreamInfo> discovered = new ArrayList<>();

        for (RemoteNode rmtNode : remoteNodes) {
            String nodeAddr = rmtNode.getNodeEnvData().getAddress() + ":" + rmtNode.getNodeEnvData().getPort();
            try {
                List<SystemInfo> systems = rmtNode.discoverSystems();
                for (SystemInfo sys : systems) {
                    List<ControlstreamInfo> controlstreams = rmtNode.discoverControlstreams(sys.getId());
                    if (!controlstreams.isEmpty()) {
                        log.debug("Found {} control stream(s) for {}", controlstreams.size(), sys.getUrn());
                    }
                    for (ControlstreamInfo cs : controlstreams) {
                        cs.setParentSystem(sys);
                        cs.setRemoteNode(rmtNode);
                        discovered.add(cs);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to connect to remote node {}: {}", nodeAddr, e.getMessage());
            }
        }

        return discovered;
    }

    private void mirrorControlstreamsToCommander(CommanderNode commander, List<ControlstreamInfo> controlstreams) {
        int mirroredCount = 0;

        for (ControlstreamInfo cs : controlstreams) {
            SystemInfo parentSys = cs.getParentSystem();
            if (parentSys == null || parentSys.getMirroredId() == null) {
                log.debug("No commander system for urn={}, skipping", parentSys != null ? parentSys.getUrn() : "null");
                continue;
            }

//            if (cs.getCommandSchema() == null) {
//                log.warn("Skipping control stream {}: source has no command_schema", cs.getName());
//                continue;
//            }

            try {
                String mirroredId = commander.createControlstream(cs);
                csMap.put(mirroredId, cs);
                cs.setMirroredId(mirroredId);
                mirroredCount++;
                log.info("Mirrored control stream: {} -> {}", cs.getName(), mirroredId);
                subscribeToCommanderControlstream(commander, cs);
            } catch (Exception e) {
                log.error("Failed to mirror control stream {}: {}", cs.getName(), e.getMessage());
            }
        }

        log.info("Mirrored {} control stream(s)", mirroredCount);
    }

    private void subscribeToCommanderControlstream(CommanderNode commander, ControlstreamInfo cs) {
        String csId = cs.getMirroredId();

        try {
            executorService.submit(() -> commandForwardingLoop(commander, cs));
            log.debug("Started command forwarding: {} -> {}", csId, cs.getId());
        } catch (Exception e) {
            log.error("[CONTROLSTREAM] failed to subscribe to commander control stream {}: {}", csId, e.getMessage());
        }
    }

    private void commandForwardingLoop(CommanderNode commander, ControlstreamInfo remoteCs) {
        String cmdCsId = remoteCs.getMirroredId();
        String remoteCsId = remoteCs.getId();
        ConcurrentLinkedDeque<JsonObject> inbound = remoteCs.getCommandInboundDeque();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!inbound.isEmpty()) {
                    JsonObject commandPayload = inbound.pollFirst();
                    if (commandPayload != null) {
                        log.info("Command received on {}", cmdCsId);
                        log.debug("Command payload: {}", commandPayload);
                        forwardCommandToRemote(commandPayload, remoteCs);
                    }
                } else {
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[COMMAND-FORWARD] error in loop for {}: {}", cmdCsId, e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void forwardCommandToRemote(JsonObject command, ControlstreamInfo remoteCs) {
        String remoteCsId = remoteCs.getId();

        JsonObject body = new JsonObject();
        for (String key : command.keySet()) {
            if (!key.equals("id") && !key.equals("controlstream@id")) {
                body.add(key, command.get(key));
            }
        }

        try {
            remoteCs.getRemoteNode().postCommand(remoteCsId, body);
            log.info("Command forwarded to {}", remoteCsId);
        } catch (Exception e) {
            log.error("Failed to POST command to {}: {}", remoteCsId, e.getMessage());
        }
    }

    public void routeCommand(String commanderCsId, JsonObject command) {
        ControlstreamInfo csInfo = csMap.get(commanderCsId);
        if (csInfo == null) {
            log.warn("No mapping for commander_cs_id={}", commanderCsId);
            return;
        }
        forwardCommandToRemote(command, csInfo);
    }

    public String normalizeMqttTopic(String topic, String prefix) {
        String t = (topic != null ? topic : "").trim();
        while (t.startsWith("/")) {
            t = t.substring(1);
        }

        if (prefix != null) {
            String p = prefix.trim();
            while (p.startsWith("/")) {
                p = p.substring(1);
            }
            while (p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            if (!p.isEmpty()) {
                t = p + "/" + t;
            }
        }

        return t;
    }

    public EventHandler getEventHandler() {
        return eventHandler;
    }

    public EnvironmentData getEnv() {
        return env;
    }

    public List<CommanderNode> getCommandNodes() {
        return commandNodes;
    }

    public List<RemoteNode> getRemoteNodes() {
        return remoteNodes;
    }

    public Map<String, DatastreamInfo> getDsMap() {
        return dsMap;
    }

    public Map<String, ControlstreamInfo> getCsMap() {
        return csMap;
    }
}
