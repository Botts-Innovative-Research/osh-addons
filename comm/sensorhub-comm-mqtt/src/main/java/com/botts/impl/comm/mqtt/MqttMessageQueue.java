package com.botts.impl.comm.mqtt;


import org.eclipse.paho.client.mqttv3.*;
import org.sensorhub.api.comm.IMessageQueuePush;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.module.AbstractSubModule;
import javax.net.ssl.SSLSocketFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public class MqttMessageQueue extends AbstractSubModule<MqttMessageQueueConfig> implements IMessageQueuePush<MqttMessageQueueConfig>, Runnable {
    private final Set<MessageListener> listeners = new CopyOnWriteArraySet<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread workerThread;
    MqttClient mqttClient;

    private final BlockingQueue<MessageData> messageQueue = new LinkedBlockingQueue<>();
    private final Map<String, Map<String, String>> clientReceivedMqttMessage = new HashMap<>();


    /**
     * @param config
     * @throws SensorHubException
     */
    @Override
    public void init(MqttMessageQueueConfig config) throws SensorHubException {
        super.init(config);

        String protocol = config.protocol.getName();
        String brokerAddress = config.brokerAddress;
        int port = config.port;
        String clientId = config.clientId;

        String brokerURL = protocol + "://"+ brokerAddress +":" + port;

        try{
            mqttClient = new MqttClient(brokerURL, clientId);

            MqttConnectOptions connectOptions = getConnectOptions(config, protocol);
            getLogger().info("Connecting to MQTT Broker {} ", brokerURL);


            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable throwable) {
                    getLogger().debug("Connection lost...");

                    if(mqttClient.isConnected()) getLogger().debug("Connected");
                    else {
                        try {
                            getLogger().debug("Trying to reconnect");
                            mqttClient.reconnect();
                        } catch (MqttException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }

                @Override
                public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
                    getLogger().debug("MSG Arrived -- topic: '{}', bytes: '{}'", topic, mqttMessage.getPayload().length);

                    Map<String, String> attributes = new HashMap<>();
                    attributes.put("topic", topic);
                    attributes.put("qos", String.valueOf(mqttMessage.getQos()));
                    attributes.put("retained", String.valueOf(mqttMessage.isRetained()));

                    MessageData messageData = new MessageData(attributes, mqttMessage.getPayload());
                    boolean accepted = messageQueue.offer(messageData);

                    if (accepted){
                        getLogger().info("Message Arrived");
                    }

                    Map<String, String> topicPayload = new HashMap<>();
                    topicPayload.put(topic, new String(mqttMessage.getPayload()));
                    clientReceivedMqttMessage.put(clientId, topicPayload);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
                    getLogger().debug("MSG delivery successful");
                }
            });


            mqttClient.connect(connectOptions);

            Thread.sleep(1000);

            if(mqttClient.isConnected()) getLogger().info("Connected to MQTT Broker {}", brokerURL);
        } catch (MqttException e) {
            throw new SensorHubException("MQTT connection failed", e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }


    }

    /**
     * @param config
     * @param protocol
     * @return
     */
    private static MqttConnectOptions getConnectOptions(MqttMessageQueueConfig config, String protocol) {
        MqttConnectOptions connectOptions = new MqttConnectOptions();
        connectOptions.setCleanSession(true);
        connectOptions.setKeepAliveInterval(60);
        connectOptions.setConnectionTimeout(10);
        connectOptions.setAutomaticReconnect(true);

        // auth
        if(config.username != null && !config.username.isBlank()){
            connectOptions.setUserName(config.username);
            if(config.password != null)
                connectOptions.setPassword(config.password.toCharArray());
        }

        if(protocol.equals("wss"))
            connectOptions.setSocketFactory(SSLSocketFactory.getDefault());

        return connectOptions;
    }

    /**
     *
     */
    @Override
    public void start() throws SensorHubException{

        int qos = config.qos.getValue();

        if(!mqttClient.isConnected()) {
            throw new SensorHubException("MQTT Client is not connected");
        }


        if(config.enableSubscribe){
            try{
                getLogger().info("Subscribed to topic: {}", config.topicName);
                mqttClient.subscribe(config.topicName, qos);
            } catch (MqttException e) {
                throw new RuntimeException(e);
            }
        }


        isRunning.set(true);

        workerThread = new Thread(this, "Mqtt-Thread");
        workerThread.start();

    }

    /**
     *
     */
    @Override
    public void run() {
        while(isRunning.get()){
            try{
               MessageData msgData = messageQueue.poll(1000, TimeUnit.MILLISECONDS);
               if(msgData != null){
                   for (MessageListener listener: listeners){
                       try{
                           getLogger().debug("receiving data: {}", msgData);
                           listener.receive(msgData.attributes, msgData.payload);
                       }catch (Exception e){
                           getLogger().error("Error in message listener", e);
                       }
                   }
               }
            } catch(Exception e){
                getLogger().error("Error: ", e);
            }
        }
    }

    /**
     *
     * @throws SensorHubException
     */
    @Override
    public void stop() throws SensorHubException{
        isRunning.set(false);

        if(mqttClient == null) return;

        try{
            mqttClient.disconnect();
            mqttClient.close();
        } catch (MqttException e) {
            throw new RuntimeException(e);
        }

        messageQueue.clear();
        clientReceivedMqttMessage.clear();
    }

    /**
     *
     * @param payload
     */
    @Override
    public void publish(byte[] payload) {
        publish(null, payload);
    }

    /**
     *
     * @param attrs
     * @param payload
     */
    @Override
    public void publish(Map<String, String> attrs, byte[] payload) {
        if(!config.enablePublish) return;

        try {
            mqttClient.publish(config.topicName, payload, config.qos.getValue(), config.retain);
        } catch (MqttException e) {
            throw new RuntimeException(e);
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
