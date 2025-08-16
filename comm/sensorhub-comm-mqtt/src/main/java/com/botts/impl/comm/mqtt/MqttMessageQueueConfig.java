package com.botts.impl.comm.mqtt;

import org.sensorhub.api.comm.MessageQueueConfig;
import org.sensorhub.api.config.DisplayInfo;

public class MqttMessageQueueConfig extends MessageQueueConfig {

//    @DisplayInfo.Required
//    @DisplayInfo(label="Subscribe Topic", desc="A string the broker uses to filter messages for each individual connected client")
//    public String subTopic;
//
//    @DisplayInfo.Required
//    @DisplayInfo(label="Publish Topic", desc="A string the broker uses to filter messages for each individual connected client")
//    public String pubTopic;

    @DisplayInfo.Required
    @DisplayInfo(label="Client", desc="A unique identifier for client, used by the broker to track connections")
    public String clientId;

    @DisplayInfo.Required
    @DisplayInfo(label="Protocol", desc="")
    public Protocol protocol = Protocol.TCP;

    @DisplayInfo.Required
    @DisplayInfo(label="Host Address", desc="The hostname or IP address of the MQTT Broker")
    public String brokerAddress;

    @DisplayInfo.ValueRange(min=0, max=65535)
    @DisplayInfo.Required
    @DisplayInfo(label="Port Number", desc="The port number to connect to on the broker addresses remote host")
    public int port;

    @DisplayInfo.Required
    @DisplayInfo(label="Quality of Service", desc="Determines the reliability of the message delivery (0,1,2)")
    public QoS qos;

    @DisplayInfo(label="Username", desc="An optional username if needed for connecting to MQTT Broker")
    public String username;

    @DisplayInfo(label="Password", desc="An optional password if needed for connecting to MQTT Broker")
    public String password;

    @DisplayInfo(label="Retain", desc="Check to allow MQTT broker to store the last message sent on the specific topic")
    public boolean retain;

//    @DisplayInfo(label="Last Will and Testament", desc="Allows Client to define a message and topic to be published if the broker client disconnects unexpectedly")
//    public String lastWillTestament;
//
//    @DisplayInfo(label="Clean Session", desc="Check to discard any previous session data, if left unchecked the broker will maintain the session after disconnect.")
//    public boolean cleanSession;

    public enum QoS {
        AT_MOST_ONCE(0),
        AT_LEAST_ONCE(1),
        EXACTLY_ONCE(2);

        final int value;
        QoS(int value){ this.value = value; }
        public int getValue(){ return value; }
    }

    public enum Protocol {
        WS("ws"),
        WSS("wss"),
        TCP("tcp"),
        MQTT("mqtt");

        final String protocol;
        Protocol(String protocol) { this.protocol = protocol; }
        public String getName() { return protocol; }
    }

}

