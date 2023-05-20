/* AUTO-GENERATED FILE.  DO NOT MODIFY.
 *
 * This class was automatically generated by the
 * java mavlink generator tool. It should not be modified by hand.
 */

// MESSAGE HIL_SENSOR PACKING
package com.MAVLink.common;
import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Messages.MAVLinkMessage;
import com.MAVLink.Messages.MAVLinkPayload;
import com.MAVLink.Messages.Units;
import com.MAVLink.Messages.Description;

/**
 * The IMU readings in SI units in NED body frame
 */
public class msg_hil_sensor extends MAVLinkMessage {

    public static final int MAVLINK_MSG_ID_HIL_SENSOR = 107;
    public static final int MAVLINK_MSG_LENGTH = 65;
    private static final long serialVersionUID = MAVLINK_MSG_ID_HIL_SENSOR;

    
    /**
     * Timestamp (UNIX Epoch time or time since system boot). The receiving end can infer timestamp format (since 1.1.1970 or since system boot) by checking for the magnitude of the number.
     */
    @Description("Timestamp (UNIX Epoch time or time since system boot). The receiving end can infer timestamp format (since 1.1.1970 or since system boot) by checking for the magnitude of the number.")
    @Units("us")
    public long time_usec;
    
    /**
     * X acceleration
     */
    @Description("X acceleration")
    @Units("m/s/s")
    public float xacc;
    
    /**
     * Y acceleration
     */
    @Description("Y acceleration")
    @Units("m/s/s")
    public float yacc;
    
    /**
     * Z acceleration
     */
    @Description("Z acceleration")
    @Units("m/s/s")
    public float zacc;
    
    /**
     * Angular speed around X axis in body frame
     */
    @Description("Angular speed around X axis in body frame")
    @Units("rad/s")
    public float xgyro;
    
    /**
     * Angular speed around Y axis in body frame
     */
    @Description("Angular speed around Y axis in body frame")
    @Units("rad/s")
    public float ygyro;
    
    /**
     * Angular speed around Z axis in body frame
     */
    @Description("Angular speed around Z axis in body frame")
    @Units("rad/s")
    public float zgyro;
    
    /**
     * X Magnetic field
     */
    @Description("X Magnetic field")
    @Units("gauss")
    public float xmag;
    
    /**
     * Y Magnetic field
     */
    @Description("Y Magnetic field")
    @Units("gauss")
    public float ymag;
    
    /**
     * Z Magnetic field
     */
    @Description("Z Magnetic field")
    @Units("gauss")
    public float zmag;
    
    /**
     * Absolute pressure
     */
    @Description("Absolute pressure")
    @Units("hPa")
    public float abs_pressure;
    
    /**
     * Differential pressure (airspeed)
     */
    @Description("Differential pressure (airspeed)")
    @Units("hPa")
    public float diff_pressure;
    
    /**
     * Altitude calculated from pressure
     */
    @Description("Altitude calculated from pressure")
    @Units("")
    public float pressure_alt;
    
    /**
     * Temperature
     */
    @Description("Temperature")
    @Units("degC")
    public float temperature;
    
    /**
     * Bitmap for fields that have updated since last message, bit 0 = xacc, bit 12: temperature, bit 31: full reset of attitude/position/velocities/etc was performed in sim.
     */
    @Description("Bitmap for fields that have updated since last message, bit 0 = xacc, bit 12: temperature, bit 31: full reset of attitude/position/velocities/etc was performed in sim.")
    @Units("")
    public long fields_updated;
    
    /**
     * Sensor ID (zero indexed). Used for multiple sensor inputs
     */
    @Description("Sensor ID (zero indexed). Used for multiple sensor inputs")
    @Units("")
    public short id;
    

    /**
     * Generates the payload for a mavlink message for a message of this type
     * @return
     */
    @Override
    public MAVLinkPacket pack() {
        MAVLinkPacket packet = new MAVLinkPacket(MAVLINK_MSG_LENGTH,isMavlink2);
        packet.sysid = sysid;
        packet.compid = compid;
        packet.msgid = MAVLINK_MSG_ID_HIL_SENSOR;

        packet.payload.putUnsignedLong(time_usec);
        packet.payload.putFloat(xacc);
        packet.payload.putFloat(yacc);
        packet.payload.putFloat(zacc);
        packet.payload.putFloat(xgyro);
        packet.payload.putFloat(ygyro);
        packet.payload.putFloat(zgyro);
        packet.payload.putFloat(xmag);
        packet.payload.putFloat(ymag);
        packet.payload.putFloat(zmag);
        packet.payload.putFloat(abs_pressure);
        packet.payload.putFloat(diff_pressure);
        packet.payload.putFloat(pressure_alt);
        packet.payload.putFloat(temperature);
        packet.payload.putUnsignedInt(fields_updated);
        
        if (isMavlink2) {
             packet.payload.putUnsignedByte(id);
            
        }
        return packet;
    }

    /**
     * Decode a hil_sensor message into this class fields
     *
     * @param payload The message to decode
     */
    @Override
    public void unpack(MAVLinkPayload payload) {
        payload.resetIndex();

        this.time_usec = payload.getUnsignedLong();
        this.xacc = payload.getFloat();
        this.yacc = payload.getFloat();
        this.zacc = payload.getFloat();
        this.xgyro = payload.getFloat();
        this.ygyro = payload.getFloat();
        this.zgyro = payload.getFloat();
        this.xmag = payload.getFloat();
        this.ymag = payload.getFloat();
        this.zmag = payload.getFloat();
        this.abs_pressure = payload.getFloat();
        this.diff_pressure = payload.getFloat();
        this.pressure_alt = payload.getFloat();
        this.temperature = payload.getFloat();
        this.fields_updated = payload.getUnsignedInt();
        
        if (isMavlink2) {
             this.id = payload.getUnsignedByte();
            
        }
    }

    /**
     * Constructor for a new message, just initializes the msgid
     */
    public msg_hil_sensor() {
        this.msgid = MAVLINK_MSG_ID_HIL_SENSOR;
    }

    /**
     * Constructor for a new message, initializes msgid and all payload variables
     */
    public msg_hil_sensor( long time_usec, float xacc, float yacc, float zacc, float xgyro, float ygyro, float zgyro, float xmag, float ymag, float zmag, float abs_pressure, float diff_pressure, float pressure_alt, float temperature, long fields_updated, short id) {
        this.msgid = MAVLINK_MSG_ID_HIL_SENSOR;

        this.time_usec = time_usec;
        this.xacc = xacc;
        this.yacc = yacc;
        this.zacc = zacc;
        this.xgyro = xgyro;
        this.ygyro = ygyro;
        this.zgyro = zgyro;
        this.xmag = xmag;
        this.ymag = ymag;
        this.zmag = zmag;
        this.abs_pressure = abs_pressure;
        this.diff_pressure = diff_pressure;
        this.pressure_alt = pressure_alt;
        this.temperature = temperature;
        this.fields_updated = fields_updated;
        this.id = id;
        
    }

    /**
     * Constructor for a new message, initializes everything
     */
    public msg_hil_sensor( long time_usec, float xacc, float yacc, float zacc, float xgyro, float ygyro, float zgyro, float xmag, float ymag, float zmag, float abs_pressure, float diff_pressure, float pressure_alt, float temperature, long fields_updated, short id, int sysid, int compid, boolean isMavlink2) {
        this.msgid = MAVLINK_MSG_ID_HIL_SENSOR;
        this.sysid = sysid;
        this.compid = compid;
        this.isMavlink2 = isMavlink2;

        this.time_usec = time_usec;
        this.xacc = xacc;
        this.yacc = yacc;
        this.zacc = zacc;
        this.xgyro = xgyro;
        this.ygyro = ygyro;
        this.zgyro = zgyro;
        this.xmag = xmag;
        this.ymag = ymag;
        this.zmag = zmag;
        this.abs_pressure = abs_pressure;
        this.diff_pressure = diff_pressure;
        this.pressure_alt = pressure_alt;
        this.temperature = temperature;
        this.fields_updated = fields_updated;
        this.id = id;
        
    }

    /**
     * Constructor for a new message, initializes the message with the payload
     * from a mavlink packet
     *
     */
    public msg_hil_sensor(MAVLinkPacket mavLinkPacket) {
        this.msgid = MAVLINK_MSG_ID_HIL_SENSOR;

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
        return "MAVLINK_MSG_ID_HIL_SENSOR - sysid:"+sysid+" compid:"+compid+" time_usec:"+time_usec+" xacc:"+xacc+" yacc:"+yacc+" zacc:"+zacc+" xgyro:"+xgyro+" ygyro:"+ygyro+" zgyro:"+zgyro+" xmag:"+xmag+" ymag:"+ymag+" zmag:"+zmag+" abs_pressure:"+abs_pressure+" diff_pressure:"+diff_pressure+" pressure_alt:"+pressure_alt+" temperature:"+temperature+" fields_updated:"+fields_updated+" id:"+id+"";
    }

    /**
     * Returns a human-readable string of the name of the message
     */
    @Override
    public String name() {
        return "MAVLINK_MSG_ID_HIL_SENSOR";
    }
}
        