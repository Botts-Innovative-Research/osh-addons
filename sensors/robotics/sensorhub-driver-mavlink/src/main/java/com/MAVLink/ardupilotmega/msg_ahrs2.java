/* AUTO-GENERATED FILE.  DO NOT MODIFY.
 *
 * This class was automatically generated by the
 * java mavlink generator tool. It should not be modified by hand.
 */

// MESSAGE AHRS2 PACKING
package com.MAVLink.ardupilotmega;
import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Messages.MAVLinkMessage;
import com.MAVLink.Messages.MAVLinkPayload;
import com.MAVLink.Messages.Units;
import com.MAVLink.Messages.Description;

/**
 * Status of secondary AHRS filter if available.
 */
public class msg_ahrs2 extends MAVLinkMessage {

    public static final int MAVLINK_MSG_ID_AHRS2 = 178;
    public static final int MAVLINK_MSG_LENGTH = 24;
    private static final long serialVersionUID = MAVLINK_MSG_ID_AHRS2;

    
    /**
     * Roll angle.
     */
    @Description("Roll angle.")
    @Units("rad")
    public float roll;
    
    /**
     * Pitch angle.
     */
    @Description("Pitch angle.")
    @Units("rad")
    public float pitch;
    
    /**
     * Yaw angle.
     */
    @Description("Yaw angle.")
    @Units("rad")
    public float yaw;
    
    /**
     * Altitude (MSL).
     */
    @Description("Altitude (MSL).")
    @Units("m")
    public float altitude;
    
    /**
     * Latitude.
     */
    @Description("Latitude.")
    @Units("degE7")
    public int lat;
    
    /**
     * Longitude.
     */
    @Description("Longitude.")
    @Units("degE7")
    public int lng;
    

    /**
     * Generates the payload for a mavlink message for a message of this type
     * @return
     */
    @Override
    public MAVLinkPacket pack() {
        MAVLinkPacket packet = new MAVLinkPacket(MAVLINK_MSG_LENGTH,isMavlink2);
        packet.sysid = sysid;
        packet.compid = compid;
        packet.msgid = MAVLINK_MSG_ID_AHRS2;

        packet.payload.putFloat(roll);
        packet.payload.putFloat(pitch);
        packet.payload.putFloat(yaw);
        packet.payload.putFloat(altitude);
        packet.payload.putInt(lat);
        packet.payload.putInt(lng);
        
        if (isMavlink2) {
            
        }
        return packet;
    }

    /**
     * Decode a ahrs2 message into this class fields
     *
     * @param payload The message to decode
     */
    @Override
    public void unpack(MAVLinkPayload payload) {
        payload.resetIndex();

        this.roll = payload.getFloat();
        this.pitch = payload.getFloat();
        this.yaw = payload.getFloat();
        this.altitude = payload.getFloat();
        this.lat = payload.getInt();
        this.lng = payload.getInt();
        
        if (isMavlink2) {
            
        }
    }

    /**
     * Constructor for a new message, just initializes the msgid
     */
    public msg_ahrs2() {
        this.msgid = MAVLINK_MSG_ID_AHRS2;
    }

    /**
     * Constructor for a new message, initializes msgid and all payload variables
     */
    public msg_ahrs2( float roll, float pitch, float yaw, float altitude, int lat, int lng) {
        this.msgid = MAVLINK_MSG_ID_AHRS2;

        this.roll = roll;
        this.pitch = pitch;
        this.yaw = yaw;
        this.altitude = altitude;
        this.lat = lat;
        this.lng = lng;
        
    }

    /**
     * Constructor for a new message, initializes everything
     */
    public msg_ahrs2( float roll, float pitch, float yaw, float altitude, int lat, int lng, int sysid, int compid, boolean isMavlink2) {
        this.msgid = MAVLINK_MSG_ID_AHRS2;
        this.sysid = sysid;
        this.compid = compid;
        this.isMavlink2 = isMavlink2;

        this.roll = roll;
        this.pitch = pitch;
        this.yaw = yaw;
        this.altitude = altitude;
        this.lat = lat;
        this.lng = lng;
        
    }

    /**
     * Constructor for a new message, initializes the message with the payload
     * from a mavlink packet
     *
     */
    public msg_ahrs2(MAVLinkPacket mavLinkPacket) {
        this.msgid = MAVLINK_MSG_ID_AHRS2;

        this.sysid = mavLinkPacket.sysid;
        this.compid = mavLinkPacket.compid;
        this.isMavlink2 = mavLinkPacket.isMavlink2;
        unpack(mavLinkPacket.payload);
    }

                
    /**
     * Returns a string with the MSG name and data
     */
    @Override
    public String toString() {
        return "MAVLINK_MSG_ID_AHRS2 - sysid:"+sysid+" compid:"+compid+" roll:"+roll+" pitch:"+pitch+" yaw:"+yaw+" altitude:"+altitude+" lat:"+lat+" lng:"+lng+"";
    }

    /**
     * Returns a human-readable string of the name of the message
     */
    @Override
    public String name() {
        return "MAVLINK_MSG_ID_AHRS2";
    }
}
        