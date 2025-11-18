/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.comm.websockets;

import io.socket.client.IO;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sensorhub.api.comm.IMessageQueuePush;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.module.AbstractSubModule;

import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import io.socket.client.Socket;


/**
 * <p>
 * Communication provider for
 * </p>
 *
 * @author Kalyn Stricklin
 * @since Nov 17, 2025
 */
public class WebSocketMessageQueue extends AbstractSubModule<WebSocketMessageQueueConfig> implements IMessageQueuePush<WebSocketMessageQueueConfig>
{
    private final Set<MessageListener> listeners = new CopyOnWriteArraySet<>();

    Socket socket;

    @Override
    public void start() throws SensorHubException {

        try {

            IO.Options options = new IO.Options();
            options.reconnection = true;
            options.reconnectionDelay = config.connection.connectTimeout;
            options.reconnectionAttempts = config.connection.reconnectAttempts;

            String scheme = config.enableTLS ? "https" : "http";
            String url = scheme + "://" + config.remoteHost + ":" + config.remotePort;

            socket = IO.socket(url, options);
            setupSocketHandlers();
            socket.connect();

        } catch (URISyntaxException e) {
            System.out.println("error connecting to websocket, " + e.getMessage());
        }

    }

    private void setupSocketHandlers() {
        socket.on(Socket.EVENT_CONNECT, args -> {
//            socket.emit("message", "");
            getLogger().info("Connected to websocket server");
        });

        socket.on(config.customEventName, args -> {
            Object data = args[0];
            System.out.println("data: "+ data);

            byte[] payload = null;

            if (data instanceof byte[]) {
                payload = (byte[]) data;

            } else if (data instanceof String) {
                payload = ((String) data).getBytes(Charset.forName("UTF-8"));
//                payload = String.valueOf(data).getBytes(StandardCharsets.UTF_8);
            }
//            else if (data instanceof JSONObject) {
//                payload = data.toString().getBytes(StandardCharsets.UTF_8);
//x
//            } else if (data instanceof JSONArray) {
//                payload = data.toString().getBytes(StandardCharsets.UTF_8);
//
//            } else if (data instanceof Number || data instanceof Boolean) {
//                payload = data.toString().getBytes(StandardCharsets.UTF_8);
//
//            } else {
//                getLogger().warn("Received unsupported message type: " + data.getClass());
//                return;
//            }

            for (MessageListener listener : listeners) {
                listener.receive(null, payload);
            }
        });


        socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
//            System.out.println("error connection: " + args[0]);
            getLogger().error("error connecting to websocket server");
        });

        socket.on(Socket.EVENT_DISCONNECT, args -> {
            getLogger().info("disconnected from websocket server");
        });
    }

    @Override
    public void stop() throws SensorHubException {
        if (socket != null) {
            socket.disconnect();
            socket.close();
            socket = null;
        }
    }

    @Override
    public void publish(byte[] payload) {
        publish(null, payload);
    }

    @Override
    public void publish(Map<String, String> attrs, byte[] payload) {
        if (socket == null) {
            getLogger().warn("Socket not connected, cannot send message");
            return;
        }
        try {
            socket.emit("message", payload);
        } catch (Exception e) {
            getLogger().error("error publishing message", e);
        }

    }

    /**
     *
     * @param listener
     */
    @Override
    public void registerListener(MessageListener listener) {
        listeners.add(listener);
    }

    /**
     *
     * @param listener
     */
    @Override
    public void unregisterListener(MessageListener listener) {
        listeners.remove(listener);
    }
}
