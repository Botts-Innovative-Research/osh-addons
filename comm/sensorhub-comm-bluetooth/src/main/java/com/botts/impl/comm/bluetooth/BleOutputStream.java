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

import org.simplejavable.BluetoothUUID;
import org.simplejavable.Peripheral;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;


/**
 * An OutputStream that buffers writes and sends the complete buffer as a
 * single BLE GATT characteristic write on flush().
 * <p>
 * Callers should write their payload bytes and then call flush() to trigger
 * the actual BLE transmission. Each flush() results in exactly one GATT write.
 */
public class BleOutputStream extends OutputStream {

    private final Peripheral peripheral;
    private final BluetoothUUID serviceUuid;
    private final BluetoothUUID writeCharUuid;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile boolean closed = false;


    BleOutputStream(Peripheral peripheral, BluetoothUUID serviceUuid, BluetoothUUID writeCharUuid) {
        this.peripheral = peripheral;
        this.serviceUuid = serviceUuid;
        this.writeCharUuid = writeCharUuid;
    }

    @Override
    public void write(int b) throws IOException {
        if (closed) throw new IOException("Stream is closed");
        synchronized (buffer) {
            buffer.write(b);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("Stream is closed");
        synchronized (buffer) {
            buffer.write(b, off, len);
        }
    }

    /**
     * Send all buffered bytes as a single GATT characteristic write (with response).
     * Blocks until the BLE write completes.
     */
    @Override
    public void flush() throws IOException {
        if (closed) throw new IOException("Stream is closed");
        byte[] data;
        synchronized (buffer) {
            data = buffer.toByteArray();
            buffer.reset();
        }
        if (data.length == 0) return;

        try {
            peripheral.writeRequest(serviceUuid, writeCharUuid, data);
        } catch (Exception e) {
            throw new IOException("Failed to write to BLE characteristic", e);
        }
    }

    void closeStream() {
        closed = true;
        synchronized (buffer) {
            buffer.reset();
        }
    }

    @Override
    public void close() {
        closeStream();
    }
}
