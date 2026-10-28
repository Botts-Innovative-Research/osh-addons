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

import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit


/**
 * An InputStream backed by BLE GATT characteristic reads.
 *
 * Each discrete GATT message is prepended with a 2-byte big-endian length prefix
 * before being placed into the stream. This allows stream consumers to detect
 * message boundaries without knowing the underlying transport.
 *
 * Layout per message in the stream:
 *   [length_MSB] [length_LSB] [payload bytes ...]
 *
 * This framing is generic and not specific to any device protocol.
 */
class BleInputStream : InputStream() {

    private val messageQueue = LinkedBlockingQueue<ByteArray>()
    private var currentBuffer: ByteArray? = null
    private var currentPos = 0

    @Volatile
    private var closed = false

    companion object {
        private val POISON = ByteArray(0)
    }

    /**
     * Enqueue a message received from a BLE GATT characteristic.
     * The message is wrapped with a 2-byte big-endian length prefix.
     */
    fun enqueueMessage(data: ByteArray) {
        if (closed) return
        val length = data.size
        val framed = ByteArray(2 + length)
        framed[0] = ((length shr 8) and 0xFF).toByte()
        framed[1] = (length and 0xFF).toByte()
        System.arraycopy(data, 0, framed, 2, length)
        messageQueue.put(framed)
    }

    override fun read(): Int {
        val buf = nextBuffer() ?: return -1
        val b = buf[currentPos++].toInt() and 0xFF
        if (currentPos >= buf.size) {
            currentBuffer = null
            currentPos = 0
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val buf = nextBuffer() ?: return -1
        val available = buf.size - currentPos
        val toRead = minOf(len, available)
        System.arraycopy(buf, currentPos, b, off, toRead)
        currentPos += toRead
        if (currentPos >= buf.size) {
            currentBuffer = null
            currentPos = 0
        }
        return toRead
    }

    /**
     * Returns the current message buffer, or blocks until the next message
     * arrives. Returns null if the stream has been closed.
     */
    private fun nextBuffer(): ByteArray? {
        // Still consuming the current buffer
        if (currentBuffer != null && currentPos < currentBuffer!!.size) {
            return currentBuffer
        }

        // Wait for the next message
        while (!closed) {
            val msg = try {
                messageQueue.poll(500, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return null
            }
            if (msg == null) continue          // timeout — check closed flag and retry
            if (msg === POISON) return null     // shutdown signal
            currentBuffer = msg
            currentPos = 0
            return currentBuffer
        }
        return null
    }

    /**
     * Signal that no more data will arrive and unblock any waiting read().
     */
    fun closeStream() {
        closed = true
        messageQueue.clear()
        messageQueue.put(POISON)
    }

    override fun close() {
        closeStream()
    }
}
