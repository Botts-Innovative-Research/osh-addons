/* AUTO-GENERATED FILE.  DO NOT MODIFY.
 *
 * This class was automatically generated by the
 * java mavlink generator tool. It should not be modified by hand.
 */

// MESSAGE DEEPSTALL PACKING
package com.MAVLink.ardupilotmega;
import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Messages.MAVLinkMessage;
import com.MAVLink.Messages.MAVLinkPayload;
import com.MAVLink.Messages.Units;
import com.MAVLink.Messages.Description;

/**
 * Deepstall path planning.
 */
public class msg_deepstall extends MAVLinkMessage {

    public static final int MAVLINK_MSG_ID_DEEPSTALL = 195;
    public static final int MAVLINK_MSG_LENGTH = 37;
    private static final long serialVersionUID = MAVLINK_MSG_ID_DEEPSTALL;

    
    /**
     * Landing latitude.
     */
    @Description("Landing latitude.")
    @Units("degE7")
    public int landing_lat;
    
    /**
     * Landing longitude.
     */
    @Description("Landing longitude.")
    @Units("degE7")
    public int landing_lon;
    
    /**
     * Final heading start point, latitude.
     */
    @Description("Final heading start point, latitude.")
    @Units("degE7")
    public int path_lat;
    
    /**
     * Final heading start point, longitude.
     */
    @Description("Final heading start point, longitude.")
    @Units("degE7")
    public int path_lon;
    
    /**
     * Arc entry point, latitude.
     */
    @Description("Arc entry point, latitude.")
    @Units("degE7")
    public int arc_entry_lat;
    
    /**
     * Arc entry point, longitude.
     */
    @Description("Arc entry point, longitude.")
    @Units("degE7")
    public int arc_entry_lon;
    
    /**
     * Altitude.
     */
    @Description("Altitude.")
    @Units("m")
    public float altitude;
    
    /**
     * Distance the aircraft expects to travel during the deepstall.
     */
    @Description("Distance the aircraft expects to travel during the deepstall.")
    @Units("m")
    public float expected_travel_distance;
    
    /**
     * Deepstall cross track error (only valid when in DEEPSTALL_STAGE_LAND).
     */
    @Description("Deepstall cross track error (only valid when in DEEPSTALL_STAGE_LAND).")
    @Units("m")
    public float cross_track_error;
    
    /**
     * Deepstall stage.
     */
    @Description("Deepstall stage.")
    @Units("")
    public short stage;
    

    /**
     * Generates the payload for a mavlink message for a message of this type
     * @return
     */
    @Override
    public MAVLinkPacket pack() {
        MAVLinkPacket packet = new MAVLinkPacket(MAVLINK_MSG_LENGTH,isMavlink2);
        packet.sysid = sysid;
        packet.compid = compid;
        packet.msgid = MAVLINK_MSG_ID_DEEPSTALL;

        packet.payload.putInt(landing_lat);
        packet.payload.putInt(landing_lon);
        packet.payload.putInt(path_lat);
        packet.payload.putInt(path_lon);
        packet.payload.putInt(arc_entry_lat);
        packet.payload.putInt(arc_entry_lon);
        packet.payload.putFloat(altitude);
        packet.payload.putFloat(expected_travel_distance);
        packet.payload.putFloat(cross_track_error);
        packet.payload.putUnsignedByte(stage);
        
        if (isMavlink2) {
            
        }
        return packet;
    }

    /**
     * Decode a deepstall message into this class fields
     *
     * @param payload The message to decode
     */
    @Override
    public void unpack(MAVLinkPayload payload) {
        payload.resetIndex();

        this.landing_lat = payload.getInt();
        this.landing_lon = payload.getInt();
        this.path_lat = payload.getInt();
        this.path_lon = payload.getInt();
        this.arc_entry_lat = payload.getInt();
        this.arc_entry_lon = payload.getInt();
        this.altitude = payload.getFloat();
        this.expected_travel_distance = payload.getFloat();
        this.cross_track_error = payload.getFloat();
        this.stage = payload.getUnsignedByte();
        
        if (isMavlink2) {
            
        }
    }

    /**
     * Constructor for a new message, just initializes the msgid
     */
    public msg_deepstall() {
        this.msgid = MAVLINK_MSG_ID_DEEPSTALL;
    }

    /**
     * Constructor for a new message, initializes msgid and all payload variables
     */
    public msg_deepstall( int landing_lat, int landing_lon, int path_lat, int path_lon, int arc_entry_lat, int arc_entry_lon, float altitude, float expected_travel_distance, float cross_track_error, short stage) {
        this.msgid = MAVLINK_MSG_ID_DEEPSTALL;

        this.landing_lat = landing_lat;
        this.landing_lon = landing_lon;
        this.path_lat = path_lat;
        this.path_lon = path_lon;
        this.arc_entry_lat = arc_entry_lat;
        this.arc_entry_lon = arc_entry_lon;
        this.altitude = altitude;
        this.expected_travel_distance = expected_travel_distance;
        this.cross_track_error = cross_track_error;
        this.stage = stage;
        
    }

    /**
     * Constructor for a new message, initializes everything
     */
    public msg_deepstall( int landing_lat, int landing_lon, int path_lat, int path_lon, int arc_entry_lat, int arc_entry_lon, float altitude, float expected_travel_distance, float cross_track_error, short stage, int sysid, int compid, boolean isMavlink2) {
        this.msgid = MAVLINK_MSG_ID_DEEPSTALL;
        this.sysid = sysid;
        this.compid = compid;
        this.isMavlink2 = isMavlink2;

        this.landing_lat = landing_lat;
        this.landing_lon = landing_lon;
        this.path_lat = path_lat;
        this.path_lon = path_lon;
        this.arc_entry_lat = arc_entry_lat;
        this.arc_entry_lon = arc_entry_lon;
        this.altitude = altitude;
        this.expected_travel_distance = expected_travel_distance;
        this.cross_track_error = cross_track_error;
        this.stage = stage;
        
    }

    /**
     * Constructor for a new message, initializes the message with the payload
     * from a mavlink packet
     *
     */
    public msg_deepstall(MAVLinkPacket mavLinkPacket) {
        this.msgid = MAVLINK_MSG_ID_DEEPSTALL;

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
        return "MAVLINK_MSG_ID_DEEPSTALL - sysid:"+sysid+" compid:"+compid+" landing_lat:"+landing_lat+" landing_lon:"+landing_lon+" path_lat:"+path_lat+" path_lon:"+path_lon+" arc_entry_lat:"+arc_entry_lat+" arc_entry_lon:"+arc_entry_lon+" altitude:"+altitude+" expected_travel_distance:"+expected_travel_distance+" cross_track_error:"+cross_track_error+" stage:"+stage+"";
    }

    /**
     * Returns a human-readable string of the name of the message
     */
    @Override
    public String name() {
        return "MAVLINK_MSG_ID_DEEPSTALL";
    }
}
        