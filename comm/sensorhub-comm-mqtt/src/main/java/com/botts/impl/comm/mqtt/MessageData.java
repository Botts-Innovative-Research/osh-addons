package com.botts.impl.comm.mqtt;

import java.util.Map;

public class MessageData {
    final Map<String,String> attributes;
    final byte[] payload;

    MessageData(Map<String, String> attributes, byte[] payload){
        this.attributes = attributes;
        this.payload = payload;
    }
}
