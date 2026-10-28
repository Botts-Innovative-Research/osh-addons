/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2026 the Initial Developer. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package com.botts.impl.comm.bluetooth

import com.juul.kable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.sensorhub.api.comm.ICommProvider
import org.sensorhub.api.common.SensorHubException
import org.sensorhub.impl.module.AbstractModule
import java.io.InputStream
import java.io.OutputStream


/**
 * Generic BLE GATT communication provider.
 *
 * Connects to a BLE peripheral, discovers the configured GATT service, and bridges
 * characteristic read/write/notify operations into standard Java InputStream/OutputStream.
 *
 * The InputStream prepends a 2-byte big-endian length prefix to each GATT message so that
 * consumers can detect message boundaries in the byte stream. The OutputStream buffers
 * bytes until flush() and sends the complete buffer as a single GATT write.
 *
 * This module is device-agnostic — configure the service and characteristic UUIDs
 * for any BLE peripheral.
 */
class BleCommProvider : AbstractModule<BleCommProviderConfig>(), ICommProvider<BleCommProviderConfig> {

    private var peripheral: Peripheral? = null
    private var scope: CoroutineScope? = null
    private var bleInputStream: BleInputStream? = null
    private var bleOutputStream: BleOutputStream? = null

    @Throws(SensorHubException::class)
    override fun doStart() {
        val proto = config.protocol

        if (proto.serviceUUID.isBlank())
            throw SensorHubException("Service UUID must be configured")
        if (proto.readCharUUID.isBlank())
            throw SensorHubException("Read Characteristic UUID must be configured")
        if (proto.writeCharUUID.isBlank())
            throw SensorHubException("Write Characteristic UUID must be configured")

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val readChar = characteristicOf(proto.serviceUUID, proto.readCharUUID)
        val writeChar = characteristicOf(proto.serviceUUID, proto.writeCharUUID)

        try {
            runBlocking {
                // Scan for and connect to the BLE device
                val advertisement = findDevice(proto)
                    ?: throw SensorHubException(
                        "No BLE device found (timeout=${proto.scanTimeoutMs}ms, " +
                        "address='${proto.deviceAddress}', nameFilter='${proto.deviceNameFilter}')"
                    )

                val p = scope!!.peripheral(advertisement)
                peripheral = p

                p.connect()
                logger.info("Connected to BLE device: {}", advertisement.name ?: advertisement.identifier)

                // Create the bridged streams
                val inputStream = BleInputStream()
                val outputStream = BleOutputStream(p, writeChar)
                bleInputStream = inputStream
                bleOutputStream = outputStream

                // If a notify characteristic is configured, observe it to trigger reads
                if (proto.notifyCharUUID.isNotBlank()) {
                    val notifyChar = characteristicOf(proto.serviceUUID, proto.notifyCharUUID)
                    scope!!.launch {
                        try {
                            p.observe(notifyChar).collect {
                                readAllAvailable(p, readChar, inputStream)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn("BLE notify observer ended: {}", e.message)
                            inputStream.closeStream()
                        }
                    }
                }

                // Initial read sweep — pick up any data already queued on the device
                readAllAvailable(p, readChar, inputStream)
            }
        } catch (e: SensorHubException) {
            throw e
        } catch (e: Exception) {
            throw SensorHubException("Failed to start BLE connection", e)
        }

        logger.info("BLE communication provider started")
    }

    /**
     * Scan for a BLE device matching the configured address, name filter,
     * or service UUID.
     */
    private suspend fun findDevice(proto: BleProtocolConfig): Advertisement? {
        logger.info("Scanning for BLE devices (timeout={}ms)...", proto.scanTimeoutMs)

        val scanner = Scanner()

        return withTimeoutOrNull(proto.scanTimeoutMs.toLong()) {
            scanner.advertisements.firstOrNull { adv ->
                val matches = when {
                    // Match by specific device address
                    proto.deviceAddress.isNotBlank() ->
                        adv.identifier.equals(proto.deviceAddress, ignoreCase = true)
                    // Match by name substring
                    proto.deviceNameFilter.isNotBlank() ->
                        adv.name?.contains(proto.deviceNameFilter, ignoreCase = true) == true
                    // Accept any device (rely on service UUID filter if set)
                    else -> true
                }
                if (matches) {
                    logger.debug("Found BLE device: name='{}', id='{}'", adv.name, adv.identifier)
                }
                matches
            }
        }
    }

    /**
     * Read all available messages from the read characteristic until the device
     * returns an empty response, and enqueue each into the input stream.
     */
    private suspend fun readAllAvailable(
        peripheral: Peripheral,
        readChar: Characteristic,
        inputStream: BleInputStream
    ) {
        try {
            while (true) {
                val data = peripheral.read(readChar)
                if (data.isEmpty()) break
                inputStream.enqueueMessage(data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("BLE read sweep ended: {}", e.message)
        }
    }

    override fun getInputStream(): InputStream {
        return bleInputStream ?: throw IllegalStateException("BLE provider not started")
    }

    override fun getOutputStream(): OutputStream {
        return bleOutputStream ?: throw IllegalStateException("BLE provider not started")
    }

    @Throws(SensorHubException::class)
    override fun doStop() {
        bleInputStream?.closeStream()
        bleOutputStream?.closeStream()

        runBlocking {
            try {
                peripheral?.disconnect()
            } catch (e: Exception) {
                logger.debug("Error disconnecting BLE: {}", e.message)
            }
        }

        scope?.cancel()

        peripheral = null
        bleInputStream = null
        bleOutputStream = null
        scope = null

        logger.info("BLE communication provider stopped")
    }
}
