# sensorhub-comm-reticulum

OpenSensorHub communication addon providing Reticulum Network (RNS) connectivity, including LXMF messaging and LXST stream metadata integration, mapped to the OGC Connected Systems comm module model.

## Module Identity

- **Slug**: reticulum-network
- **Group**: org.sensorhub.impl.comm.reticulum
- **Artifact**: sensorhub-comm-reticulum
- **Provider**: Reticulum Network
- **Package**: org.sensorhub.impl.comm.reticulum
- **Driver Path**: include/osh-addons/comm/sensorhub-comm-reticulum
- **Reference Driver**: sensorhub-comm-ip-zeroconf

## Build

```
./gradlew -q :sensorhub-comm-reticulum:compileJava
```

## Test

```
./gradlew -q :sensorhub-comm-reticulum:test
```

## Description

This module implements an OSH communication driver for the Reticulum Network stack. It exposes RNS transports, LXMF message dispatch, and LXST stream metadata as OSH comm services and datastreams, conforming to the OGC Connected Systems specification (23-001) and SensorThings API mapping conventions.

The driver supports a deterministic no-hardware fixture and process-bridge runtime across Windows, Linux, and macOS, enabling continuous integration and offline testing without a live RNS node.

## Features

- RNS transport registration and link status reporting
- LXMF outbound message dispatch and inbound event mapping
- LXST stream metadata translation into OSH datastreams
- Identity and destination mapping between RNS addresses and OSH resource identifiers
- Service discovery integration compatible with OSH module lifecycle

## Dependencies

- OpenSensorHub core comm module APIs
- Reticulum Network core library (RNS)
- LXMF reference implementation
- LXST reference implementation

## License

Distributed under the same license terms as the parent OpenSensorHub addons repository. Refer to the repository root LICENSE for details.
