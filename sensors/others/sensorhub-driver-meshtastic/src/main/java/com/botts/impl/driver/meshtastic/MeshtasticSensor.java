/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2025 the Initial Developer. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package com.botts.impl.driver.meshtastic;

import com.botts.impl.driver.meshtastic.control.TextMessageControl;
import com.botts.impl.driver.meshtastic.output.*;
import org.meshtastic.proto.MeshProtos;
import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.sensor.AbstractSensorModule;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MeshtasticSensor extends AbstractSensorModule<MeshtasticConfig> {

    private ICommProvider<?> commProvider;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicBoolean isProcessing = new AtomicBoolean(false);

    private static final byte START1 = (byte) 0x94;
    private static final byte START2 = (byte) 0xC3;

    @Override
    public void doInit() throws SensorHubException {
        super.doInit();

        // Generate identifiers
        generateUniqueID("urn:osh:driver:meshtastic:", config.serialNumber);
        generateXmlID("MESHTASTIC_", config.serialNumber);

        if (config.commSettings != null)
            commProvider = (ICommProvider<?>) getParentHub().getModuleRegistry().loadSubModule(config.commSettings, true);

        addOutput(new NodeInfoOutput(this), false);
        addOutput(new MyNodeInfoOutput(this), false);
        addOutput(new PositionPacketOutput(this), false);
        addOutput(new TextMessagePacketOutput(this), false);

        addControlInput(new TextMessageControl(this));
    }

    @Override
    public void doStart() throws SensorHubException {
        if (commProvider != null) {
            commProvider.start();
            startProcessing();

            // send "handshake" to start receiving protobufs
            MeshProtos.ToRadio handshake = MeshProtos.ToRadio.newBuilder()
                    // TODO: Verify response ID in future if needed
                    .setWantConfigId(0)
                    .build();
            try {
                sendMessage(handshake);
            } catch (IOException e) {
                getLogger().error("Failed to send handshake message", e);
            }
        }
    }
    public void sendMessage(MeshProtos.ToRadio message) throws IOException {
        byte[] bytes = message.toByteArray();

        int len = bytes.length;
        byte[] header = new byte[4];
        header[0] = START1;
        header[1] = START2;
        header[2] = (byte) ((len >> 8) & 0xFF);
        header[3] = (byte) (len & 0xFF);

        var os = commProvider.getOutputStream();
        os.write(header);
        os.write(bytes);
        os.flush();
    }

    private void startProcessing() {
        executor.execute(() -> {
            try {
                InputStream in = commProvider.getInputStream();
                isProcessing.set(true);
                while (isProcessing.get()) {
                    int b;
                    // find START1
                    do {
                        b = in.read();
                        if (b == -1) return;
                        if (b != 0x94) {
                            // optional: treat as debug ASCII
                            System.out.print((char) b);
                        }
                    } while (b != 0x94);

                    // expect START2
                    b = in.read();
                    // invalid header
                    if (b != 0xC3) {
                        getLogger().warn("Invalid header");
                        continue;
                    }

                    // protobuf length
                    int lenMSB = in.read();
                    int lenLSB = in.read();
                    if (lenMSB == -1 || lenLSB == -1) break;

                    int length = ((lenMSB & 0xFF) << 8) | (lenLSB & 0xFF);
                    if (length <= 0 || length > 512) {
                        getLogger().info("Invalid length, resyncing...");
                        continue;
                    }

                    // protobuf data
                    byte[] payload = new byte[length];
                    int read = 0;
                    while (read < length) {
                        int r = in.read(payload, read, length - read);
                        if (r == -1) break;
                        read += r;
                    }

                    if (read < length) {
                        getLogger().info("Truncated packet, resyncing...");
                        continue;
                    }

                    // parse protobuf
                    try {
                        MeshProtos.FromRadio msg = MeshProtos.FromRadio.parseFrom(payload);
                        for (int i = 0; i < getOutputs().size(); i++) {
                            AbstractMeshtasticOutput output = (AbstractMeshtasticOutput) getOutputs().values().stream().toList().get(i);
                            if (output.canHandle(msg))
                                output.onMessage(msg);
                        }
                        getLogger().info("New message: " + msg);
                    } catch (Exception e) {
                        getLogger().error("Invalid protobuf: " + e.getMessage());
                    }
                }

            } catch (IOException e) {
            }
        });
    }

    @Override
    public void doStop() throws SensorHubException {
        isProcessing.set(false);
    }

    @Override
    public boolean isConnected() {
        return isProcessing.get();
    }
}
