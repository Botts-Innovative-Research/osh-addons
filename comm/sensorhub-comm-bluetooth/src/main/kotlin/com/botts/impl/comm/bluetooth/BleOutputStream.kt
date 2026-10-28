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

import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream


/**
 * An OutputStream that buffers writes and sends the complete buffer as a
 * single BLE GATT characteristic write on flush().
 *
 * Callers should write their payload bytes and then call flush() to trigger
 * the actual BLE transmission. Each flush() results in exactly one GATT write.
 */
class BleOutputStream(
    private val peripheral: Peripheral,
    private val writeCharacteristic: Characteristic
) : OutputStream() {

    private val buffer = ByteArrayOutputStream()

    @Volatile
    private var closed = false

    override fun write(b: Int) {
        if (closed) throw IOException("Stream is closed")
        synchronized(buffer) {
            buffer.write(b)
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("Stream is closed")
        synchronized(buffer) {
            buffer.write(b, off, len)
        }
    }

    /**
     * Send all buffered bytes as a single GATT characteristic write.
     * Blocks until the BLE write completes (WriteType.WithResponse).
     */
    override fun flush() {
        if (closed) throw IOException("Stream is closed")
        val data: ByteArray
        synchronized(buffer) {
            data = buffer.toByteArray()
            buffer.reset()
        }
        if (data.isEmpty()) return

        try {
            runBlocking {
                peripheral.write(writeCharacteristic, data, WriteType.WithResponse)
            }
        } catch (e: Exception) {
            throw IOException("Failed to write to BLE characteristic", e)
        }
    }

    fun closeStream() {
        closed = true
        synchronized(buffer) {
            buffer.reset()
        }
    }

    override fun close() {
        closeStream()
    }
}
