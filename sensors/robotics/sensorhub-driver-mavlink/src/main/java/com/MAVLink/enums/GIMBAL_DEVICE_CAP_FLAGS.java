/* AUTO-GENERATED FILE.  DO NOT MODIFY.
 *
 * This class was automatically generated by the
 * java mavlink generator tool. It should not be modified by hand.
 */

package com.MAVLink.enums;

/**
 * Gimbal device (low level) capability flags (bitmap)
 */
public class GIMBAL_DEVICE_CAP_FLAGS {
   public static final int GIMBAL_DEVICE_CAP_FLAGS_HAS_RETRACT = 1; /* Gimbal device supports a retracted position | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_HAS_NEUTRAL = 2; /* Gimbal device supports a horizontal, forward looking position, stabilized | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_HAS_ROLL_AXIS = 4; /* Gimbal device supports rotating around roll axis. | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_HAS_ROLL_FOLLOW = 8; /* Gimbal device supports to follow a roll angle relative to the vehicle | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_HAS_ROLL_LOCK = 16; /* Gimbal device supports locking to an roll angle (generally that's the default with roll stabilized) | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_HAS_PITCH_AXIS = 32; /* Gimbal device supports rotating around pitch axis. | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_HAS_PITCH_FOLLOW = 64; /* Gimbal device supports to follow a pitch angle relative to the vehicle | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_HAS_PITCH_LOCK = 128; /* Gimbal device supports locking to an pitch angle (generally that's the default with pitch stabilized) | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_HAS_YAW_AXIS = 256; /* Gimbal device supports rotating around yaw axis. | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_HAS_YAW_FOLLOW = 512; /* Gimbal device supports to follow a yaw angle relative to the vehicle (generally that's the default) | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_HAS_YAW_LOCK = 1024; /* Gimbal device supports locking to an absolute heading (often this is an option available) | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_SUPPORTS_INFINITE_YAW = 2048; /* Gimbal device supports yawing/panning infinetely (e.g. using slip disk). | */
   public static final int GIMBAL_DEVICE_CAP_FLAGS_ENUM_END = 2049; /*  | */
}
            