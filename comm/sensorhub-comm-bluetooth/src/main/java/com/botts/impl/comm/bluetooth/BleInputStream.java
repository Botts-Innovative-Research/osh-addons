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

package com.botts.impl.comm.bluetooth;

import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * An InputStream backed by BLE GATT characteristic reads.
 * <p>
 * Each discrete GATT message is prepended with a 2-byte big-endian length prefix
 * before being placed into the stream. This allows stream consumers to detect
 * message boundaries without knowing the underlying transport.
 * <p>
 * Layout per message in the stream:
 * <pre>
 *   [length_MSB] [length_LSB] [payload bytes ...]
 * </pre>
 * This framing is generic and not specific to any device protocol.
 */
public class BleInputStream extends InputStream {

    private static final byte[] POISON = new byte[0];

    private final LinkedBlockingQueue<byte[]> messageQueue = new LinkedBlockingQueue<>();
    private byte[] currentBuffer = null;
    private int currentPos = 0;
    private volatile boolean closed = false;


    /**
     * Enqueue a message received from a BLE GATT characteristic.
     * The message is wrapped with a 2-byte big-endian length prefix.
     */
    void enqueueMessage(byte[] data) {
        if (closed) return;
        int length = data.length;
        byte[] framed = new byte[2 + length];
        framed[0] = (byte) ((length >> 8) & 0xFF);
        framed[1] = (byte) (length & 0xFF);
        System.arraycopy(data, 0, framed, 2, length);
        messageQueue.offer(framed);
    }

    @Override
    public int read() {
        byte[] buf = nextBuffer();
        if (buf == null) return -1;
        int b = buf[currentPos++] & 0xFF;
        if (currentPos >= buf.length) {
            currentBuffer = null;
            currentPos = 0;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        byte[] buf = nextBuffer();
        if (buf == null) return -1;
        int available = buf.length - currentPos;
        int toRead = Math.min(len, available);
        System.arraycopy(buf, currentPos, b, off, toRead);
        currentPos += toRead;
        if (currentPos >= buf.length) {
            currentBuffer = null;
            currentPos = 0;
        }
        return toRead;
    }

    /**
     * Returns the current message buffer, or blocks until the next message
     * arrives. Returns null if the stream has been closed.
     */
    private byte[] nextBuffer() {
        // Still consuming the current buffer
        if (currentBuffer != null && currentPos < currentBuffer.length) {
            return currentBuffer;
        }

        // Wait for the next message
        while (!closed) {
            try {
                byte[] msg = messageQueue.poll(500, TimeUnit.MILLISECONDS);
                if (msg == null) continue;          // timeout — check closed flag and retry
                if (msg == POISON) return null;      // shutdown signal
                currentBuffer = msg;
                currentPos = 0;
                return currentBuffer;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Signal that no more data will arrive and unblock any waiting read().
     */
    void closeStream() {
        closed = true;
        messageQueue.clear();
        messageQueue.offer(POISON);
    }

    @Override
    public void close() {
        closeStream();
    }
}
