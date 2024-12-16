# HC-SR04 Range Driver
This is a driver for HC-SR04 sensors that are connected to a Raspberry Pi.

***

## Prerequisites
To use the HC-SR04 Driver, you will need:

- Raspberry Pi.
- HC-SR04 sensor
- 1kΩ Resistor
- 2kΩ Resistor
- Jumper wires
- Java 11 or later installed on the Raspberry Pi.
- OpenSensorHub node installed on the Raspberry Pi.

***

## Usage
To use the driver, follow these steps:

1. Wire the sensor to the Raspberry Pi as described in the Wiring section.
2. Start the OpenSensorHub node and log in to the web interface. Make sure to run the start script with `sudo`.
3. Add a new driver to the node by selecting Drivers from the left-hand accordion control and right-clicking to bring up the context-sensitive menu in the accordion control.
4. Select the HC-SR04 driver from the list of available drivers.
5. Configure the driver as described in the Configuration section.
6. Start the driver by selecting Drivers from the left-hand accordion control and right-clicking the driver in the list of drivers to bring up the context-sensitive menu.

***

## Wiring
Follow the instructions [here](https://thepihut.com/blogs/raspberry-pi-tutorials/hc-sr04-ultrasonic-range-sensor-on-the-raspberry-pi?srsltid=AfmBOoq_Udfbx0UPn1Z5VKr58l2wQRI1GPa0pGuQz-0tSfpboOmW_41g).

***

## Configuration
When added to an OpenSensorHub node, you must set the gpio pins that you used on the raspberry pi:

- Set the gpio output pin to the pin number that the trigger pin is connected to. By default this is 23. Change it if you did not connect trigger to 23.
- Set the gpio input pin to the pin number that the echo pin is connected to. By default this is 24. Change it if you did not connect echo to 24.

