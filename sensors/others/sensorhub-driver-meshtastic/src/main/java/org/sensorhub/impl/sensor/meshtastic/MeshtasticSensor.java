/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2025 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.meshtastic;

import com.botts.impl.comm.bluetooth.BleCommProvider;
import org.meshtastic.proto.MeshProtos;
import org.simplejavable.BluetoothUUID;
import org.simplejavable.Peripheral;
import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.vast.ogc.om.MovingFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Driver implementation for Meshtastic radios.
 * <p>
 * Supports two connection modes:
 * <ul>
 *   <li><b>Serial</b> — via any ICommProvider (e.g., JSSC). Uses 0x94/0xC3 framed packets.</li>
 *   <li><b>BLE</b> — via BleCommProvider. The driver performs GATT operations directly
 *       on the Meshtastic BLE service (no stream framing needed).</li>
 * </ul>
 */
public class MeshtasticSensor extends AbstractSensorModule<Config> {
    static final String UID_PREFIX = "urn:osh:sensor:georobotix:meshtastic:";
    static final String XML_PREFIX = "meshtastic";
    private static final String NODE_ID_FORMAT = "!%08x";

    String localMeshNodeId = null;

    private ICommProvider<?> commProvider;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicBoolean isProcessing = new AtomicBoolean(false);

    // SERIAL FRAMING CONSTANTS
    private static final int START1 = 0x94;
    private static final int START2 = 0xC3;

    // MESHTASTIC BLE GATT UUIDs
    private static final String MESH_SERVICE_UUID   = "6ba1b218-15a8-461f-9fa8-5dcae273eafd";
    private static final String MESH_TORADIO_UUID   = "f75c76d2-129e-4dad-a1dd-7866124401e7";
    private static final String MESH_FROMRADIO_UUID = "2c55e69e-4993-11ed-b878-0242ac120002";
    private static final String MESH_FROMNUM_UUID   = "ed9da18c-a800-4f66-a670-aa7547e34453";

    // BLE fields — only used when config.bleConnection == true
    private Peripheral blePeripheral;
    private BluetoothUUID bleSvcUuid;
    private BluetoothUUID bleToRadioUuid;
    private BluetoothUUID bleFromRadioUuid;
    private BluetoothUUID bleFromNumUuid;
    private final LinkedBlockingQueue<byte[]> bleMessageQueue = new LinkedBlockingQueue<>();
    private Thread bleReaderThread;
    private final Object bleReadSignal = new Object();

    MeshtasticHandler meshtasticHandler;

    MeshtasticOutputTextMessage textOutput;
    MeshtasticOutputPosition posOutput;
    MeshtasticOutputNodeInfo nodeInfoOutput;
    MeshtasticOutputGeneric genericOutput;

    MeshtasticControlTextMessage meshtasticControlTextMessage;


    @Override
    public void doInit() throws SensorHubException {
        super.doInit();

        if (config.commSettings != null) {
            commProvider = (ICommProvider<?>) getParentHub().getModuleRegistry().loadSubModule(config.commSettings, true);
            localMeshNodeId = fetchLocalMeshNodeId();
        }

        String id;
        if (config.serialNumber != null && !config.serialNumber.isBlank()) {
            id = config.serialNumber;
        } else if (localMeshNodeId != null) {
            id = localMeshNodeId;
        } else {
            throw new SensorHubException("Could not determine radio node ID — check comm settings and ensure the radio is connected");
        }
        generateUniqueID(UID_PREFIX, id);
        generateXmlID(XML_PREFIX, id);

        textOutput = new MeshtasticOutputTextMessage(this);
        addOutput(textOutput, false);
        textOutput.doInit();

        posOutput = new MeshtasticOutputPosition(this);
        addOutput(posOutput, false);
        posOutput.doInit();

        nodeInfoOutput = new MeshtasticOutputNodeInfo(this);
        addOutput(nodeInfoOutput, false);
        nodeInfoOutput.doInit();

        genericOutput = new MeshtasticOutputGeneric(this);
        addOutput(genericOutput, false);
        genericOutput.doInit();

        meshtasticHandler = new MeshtasticHandler(this);

        meshtasticControlTextMessage = new MeshtasticControlTextMessage(this);
        addControlInput(meshtasticControlTextMessage);
    }

    @Override
    public void doStart() throws SensorHubException {
        super.doStart();

        if (commProvider == null) {
            throw new SensorHubException("No communication provider configured");
        }

        if (config.bleConnection) {
            // BLE: reuse connection from fetchNodeIdBle() if still active
            Peripheral existing = (commProvider instanceof BleCommProvider)
                    ? ((BleCommProvider) commProvider).getPeripheral() : null;
            if (existing == null) {
                commProvider.start();
                try { Thread.sleep(100); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SensorHubException("Thread interrupted while starting comm provider", e);
                }
            } else {
                getLogger().info("Reusing existing BLE connection");
            }
            initBle();
        } else {
            commProvider.start();
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SensorHubException("Thread interrupted while starting comm provider", e);
            }
        }

        // Send startConfig handshake with random non-zero config ID
        int configId = newConfigId();
        sendMessage(MeshProtos.ToRadio.newBuilder().setWantConfigId(configId).build());

        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            executor = Executors.newSingleThreadExecutor();
        }
        startProcessing();
    }

    @Override
    public void doStop() throws SensorHubException {
        super.doStop();

        isProcessing.set(false);

        if (config.bleConnection) {
            cleanupBle();
        }

        if (commProvider != null) {
            commProvider.stop();
        }

        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    getLogger().warn("Executor did not terminate cleanly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isConnected() {
        return commProvider != null && commProvider.isInitialized();
    }

    private static int newConfigId() {
        return (int) (Math.random() * Integer.MAX_VALUE) + 1;
    }

    // ========================= BLE SETUP/TEARDOWN =========================

    private void initBle() throws SensorHubException {
        if (!(commProvider instanceof BleCommProvider)) {
            throw new SensorHubException("BLE connection is enabled but the comm provider is not a BLE provider");
        }

        blePeripheral = ((BleCommProvider) commProvider).getPeripheral();
        if (blePeripheral == null) {
            throw new SensorHubException("BLE peripheral is not connected");
        }

        bleSvcUuid = new BluetoothUUID(MESH_SERVICE_UUID);
        bleToRadioUuid = new BluetoothUUID(MESH_TORADIO_UUID);
        bleFromRadioUuid = new BluetoothUUID(MESH_FROMRADIO_UUID);
        bleFromNumUuid = new BluetoothUUID(MESH_FROMNUM_UUID);

        bleMessageQueue.clear();
        bleReaderThread = new Thread(this::bleReaderLoop, "Mesh-BLE-reader");
        bleReaderThread.setDaemon(true);
        bleReaderThread.start();

        // Subscribe to fromNum notifications — signals new data is available.
        // The callback runs on SimpleBLE's native thread, so it must NOT call
        // peripheral.read() — it just signals the reader thread.
        blePeripheral.notify(bleSvcUuid, bleFromNumUuid, data -> {
            getLogger().debug("BLE fromNum notification received");
            synchronized (bleReadSignal) {
                bleReadSignal.notifyAll();
            }
        });
        getLogger().info("BLE GATT initialized — subscribed to Meshtastic notifications");
    }

    private void cleanupBle() {
        if (blePeripheral != null && bleSvcUuid != null && bleFromNumUuid != null) {
            try {
                blePeripheral.unsubscribe(bleSvcUuid, bleFromNumUuid);
            } catch (Exception e) {
                getLogger().debug("Error unsubscribing BLE notifications: {}", e.getMessage());
            }
        }

        synchronized (bleReadSignal) {
            bleReadSignal.notifyAll();
        }
        if (bleReaderThread != null) {
            bleReaderThread.interrupt();
            try { bleReaderThread.join(2000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        blePeripheral = null;
        bleMessageQueue.clear();
    }

    // ========================= BLE READ/WRITE =========================

    private void bleReaderLoop() {
        getLogger().debug("BLE reader thread started");
        while (isProcessing.get() || !Thread.currentThread().isInterrupted()) {
            try {
                int count = drainFromRadio();
                if (count == 0) {
                    synchronized (bleReadSignal) {
                        bleReadSignal.wait(200);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (isProcessing.get()) {
                    getLogger().warn("BLE reader error: {}", e.getMessage());
                    try { Thread.sleep(500); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        getLogger().debug("BLE reader thread stopped");
    }

    private int drainFromRadio() {
        if (blePeripheral == null) return 0;
        int count = 0;
        try {
            while (true) {
                byte[] data = blePeripheral.read(bleSvcUuid, bleFromRadioUuid);
                if (data == null || data.length == 0) break;
                getLogger().debug("BLE read: {} bytes from fromRadio", data.length);
                bleMessageQueue.offer(data);
                count++;
            }
        } catch (Exception e) {
            getLogger().debug("BLE drainFromRadio ended: {}", e.getMessage());
        }
        return count;
    }

    // ========================= PROCESSING =========================

    private void startProcessing() {
        if (isProcessing.get()) return;

        executor.execute(() -> {
            isProcessing.set(true);
            try {
                if (config.bleConnection) {
                    processBleMessages();
                } else {
                    try (InputStream in = commProvider.getInputStream()) {
                        processSerialStream(in);
                    }
                }
            } catch (Exception e) {
                getLogger().debug("Processing ended: {}", e.getMessage());
            } finally {
                isProcessing.set(false);
            }
        });
    }

    private void processBleMessages() {
        while (isProcessing.get()) {
            try {
                byte[] payload = bleMessageQueue.poll(500, TimeUnit.MILLISECONDS);
                if (payload == null) continue;
                parseProtobuf(payload);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processSerialStream(InputStream in) {
        while (isProcessing.get()) {
            try {
                processNextSerialPacket(in);
            } catch (Exception e) {
                if (!isProcessing.get() && e instanceof IllegalArgumentException && e.getMessage().contains("EBADF")) {
                    getLogger().debug("Serial port closed during shutdown (EBADF).");
                    break;
                }
                getLogger().error("Unexpected error in processing loop", e);
            }
        }
    }

    private void processNextSerialPacket(InputStream in) throws IOException {
        if (!findStartOfPacket(in)) return;

        int length = readPacketLength(in);
        if (length <= 0 || length > 512) {
            getLogger().info("Invalid length, resyncing...");
            return;
        }

        byte[] payload = readPayload(in, length);
        if (payload.length == 0) {
            getLogger().info("Truncated packet, resyncing...");
            return;
        }

        parseProtobuf(payload);
    }

    // ========================= SERIAL HELPERS =========================

    private boolean findStartOfPacket(InputStream in) throws IOException {
        int b;
        do {
            b = in.read();
            if (b == -1) return false;
        } while (b != START1);

        b = in.read();
        if (b != START2) {
            getLogger().warn("Invalid header");
            return false;
        }
        return true;
    }

    private int readPacketLength(InputStream in) throws IOException {
        int lenMSB = in.read();
        int lenLSB = in.read();
        if (lenMSB == -1 || lenLSB == -1) return -1;
        return ((lenMSB & 0xFF) << 8 | (lenLSB & 0xFF));
    }

    private byte[] readPayload(InputStream in, int length) throws IOException {
        byte[] payload = new byte[length];
        int read = 0;
        while (read < length) {
            int r = in.read(payload, read, length - read);
            if (r == -1) return new byte[0];
            read += r;
        }
        return payload;
    }

    // ========================= PROTOBUF PARSING (shared) =========================

    private void parseProtobuf(byte[] payload) {
        try {
            MeshProtos.FromRadio msg = MeshProtos.FromRadio.parseFrom(payload);
            if (msg.hasMyInfo()) {
                handleMyInfo(msg.getMyInfo());
            }
            if (msg.hasPacket()) {
                meshtasticHandler.handlePacket(msg.getPacket());
            }
        } catch (Exception e) {
            getLogger().error("Invalid protobuf: {} ", e.getMessage());
        }
    }

    private void handleMyInfo(MeshProtos.MyNodeInfo myInfo) {
        long nodeNum = Integer.toUnsignedLong(myInfo.getMyNodeNum());
        localMeshNodeId = String.format(NODE_ID_FORMAT, nodeNum);
        getLogger().info("Connected to Meshtastic radio: node ID = {}", localMeshNodeId);
    }

    // ========================= SEND MESSAGE =========================

    public boolean sendMessage(MeshProtos.ToRadio message) {
        byte[] bytes = message.toByteArray();

        try {
            if (config.bleConnection) {
                blePeripheral.writeRequest(bleSvcUuid, bleToRadioUuid, bytes);
            } else {
                OutputStream os = commProvider.getOutputStream();
                byte[] header = new byte[]{
                        (byte) START1, (byte) START2,
                        (byte) ((bytes.length >> 8) & 0xFF),
                        (byte) (bytes.length & 0xFF)
                };
                os.write(header);
                os.write(bytes);
                os.flush();
            }
            return true;
        } catch (Exception e) {
            getLogger().error("Failed to send message", e);
            return false;
        }
    }

    // ========================= FETCH NODE ID =========================

    private String fetchLocalMeshNodeId() {
        if (config.bleConnection) {
            return fetchNodeIdBle();
        } else {
            return fetchNodeIdSerial();
        }
    }

    private String fetchNodeIdBle() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            String result = attemptFetchNodeIdBle(attempt);
            if (result != null) return result;

            try { commProvider.stop(); } catch (Exception ignored) {}
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private String attemptFetchNodeIdBle(int attempt) {
        try {
            commProvider.start();
            Thread.sleep(500);

            Peripheral p = ((BleCommProvider) commProvider).getPeripheral();
            if (p == null) return null;

            BluetoothUUID svc = new BluetoothUUID(MESH_SERVICE_UUID);
            BluetoothUUID toRadio = new BluetoothUUID(MESH_TORADIO_UUID);
            BluetoothUUID fromRadio = new BluetoothUUID(MESH_FROMRADIO_UUID);
            BluetoothUUID fromNum = new BluetoothUUID(MESH_FROMNUM_UUID);

            // Subscribe to fromNum before sending handshake — the radio needs
            // to see an active subscription before it queues responses
            p.notify(svc, fromNum, data -> {});
            getLogger().debug("BLE fetchNodeId (attempt {}): subscribed to fromNum", attempt);
            Thread.sleep(200);

            int configId = newConfigId();
            byte[] handshake = MeshProtos.ToRadio.newBuilder()
                    .setWantConfigId(configId)
                    .build().toByteArray();
            p.writeRequest(svc, toRadio, handshake);
            getLogger().debug("BLE fetchNodeId (attempt {}): sent startConfig (configId={})", attempt, configId);

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                byte[] data = p.read(svc, fromRadio);
                if (data == null || data.length == 0) {
                    Thread.sleep(100);
                    continue;
                }
                getLogger().debug("BLE fetchNodeId: read {} bytes", data.length);
                MeshProtos.FromRadio msg = MeshProtos.FromRadio.parseFrom(data);
                if (msg.hasMyInfo()) {
                    long nodeNum = Integer.toUnsignedLong(msg.getMyInfo().getMyNodeNum());
                    String nodeId = String.format(NODE_ID_FORMAT, nodeNum);
                    getLogger().info("BLE: fetched node ID = {}", nodeId);
                    // Drain remaining config messages so the queue is clean for doStart()
                    int drained = 0;
                    while (true) {
                        byte[] extra = p.read(svc, fromRadio);
                        if (extra == null || extra.length == 0) break;
                        drained++;
                    }
                    getLogger().debug("BLE fetchNodeId: drained {} additional config messages", drained);
                    try { p.unsubscribe(svc, fromNum); } catch (Exception ignored) {}
                    return nodeId;
                }
            }
            getLogger().warn("BLE fetchNodeId (attempt {}): timed out", attempt);
        } catch (Exception e) {
            getLogger().warn("BLE fetchNodeId (attempt {}): {}", attempt, e.getMessage());
        }
        return null;
    }

    private String fetchNodeIdSerial() {
        ExecutorService fetchExecutor = Executors.newSingleThreadExecutor();
        try {
            commProvider.start();
            Thread.sleep(100);
            sendMessage(MeshProtos.ToRadio.newBuilder().setWantConfigId(newConfigId()).build());

            Future<String> future = fetchExecutor.submit(() -> {
                try (InputStream in = commProvider.getInputStream()) {
                    while (!Thread.currentThread().isInterrupted()) {
                        if (!findStartOfPacket(in)) continue;
                        int length = readPacketLength(in);
                        if (length <= 0 || length > 512) continue;
                        byte[] payload = readPayload(in, length);
                        if (payload.length == 0) continue;
                        MeshProtos.FromRadio msg = MeshProtos.FromRadio.parseFrom(payload);
                        if (msg.hasMyInfo()) {
                            long nodeNum = Integer.toUnsignedLong(msg.getMyInfo().getMyNodeNum());
                            return String.format(NODE_ID_FORMAT, nodeNum);
                        }
                    }
                }
                return null;
            });

            return future.get(5, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            getLogger().warn("Timed out waiting for radio node ID, falling back to serial number");
        } catch (Exception e) {
            getLogger().warn("Could not fetch radio node ID: {}", e.getMessage());
        } finally {
            fetchExecutor.shutdownNow();
            try { commProvider.stop(); } catch (Exception ignored) {}
        }
        return null;
    }

    // ========================= FOI MANAGEMENT =========================

    String addFoi(String meshNodeId) {
        String foiUID = UID_PREFIX + ":foi:" + meshNodeId;

        if (!foiMap.containsKey(foiUID)) {
            MovingFeature foi = new MovingFeature();
            foi.setId(meshNodeId);
            foi.setUniqueIdentifier(foiUID);
            foi.setName(meshNodeId);
            foi.setDescription("meshtastic Node " + meshNodeId);
            addFoi(foi);
            getLogger().debug("New meshtastic Node added as FOI: {}", foiUID);
        }

        return foiUID;
    }
}
