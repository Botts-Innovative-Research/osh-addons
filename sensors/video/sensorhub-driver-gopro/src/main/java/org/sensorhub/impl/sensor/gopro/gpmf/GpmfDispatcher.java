/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.gopro.gpmf;

import org.sensorhub.mpegts.DataBufferListener;
import org.sensorhub.mpegts.DataBufferRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.util.Asserts;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Listens to a GPMF stream, decodes each packet once, and hands the result to every registered output.
 * <p>
 * A {@link org.sensorhub.mpegts.StreamContext} holds a single listener, so the outputs that publish GPMF
 * telemetry cannot each register for the stream. This sits in front of them: it does the decode on the
 * driver's executor and fans the decoded payload out, so a packet is parsed once no matter how many
 * outputs are interested in it.
 * <p>
 * A packet that cannot be decoded is logged and dropped, since one malformed payload should not stop the
 * telemetry that follows it.
 */
public class GpmfDispatcher implements DataBufferListener {
    private static final Logger logger = LoggerFactory.getLogger(GpmfDispatcher.class.getSimpleName());

    private final List<GpmfPayloadListener> listeners = new CopyOnWriteArrayList<>();
    private final String streamLabel;

    private Executor executor;

    /**
     * @param streamLabel Name used to identify this stream in log messages.
     */
    public GpmfDispatcher(String streamLabel) {
        this.streamLabel = streamLabel;
    }

    /**
     * Registers an output to receive decoded payloads.
     *
     * @param listener The output to notify.
     */
    public void addListener(GpmfPayloadListener listener) {
        listeners.add(Asserts.checkNotNull(listener, GpmfPayloadListener.class));
    }

    /**
     * Sets the thread the decode and the publishing run on.
     */
    public void setExecutor(Executor executor) {
        this.executor = Asserts.checkNotNull(executor, Executor.class);
    }

    @Override
    public void onDataBuffer(DataBufferRecord dataBufferRecord) {
        executor.execute(() -> {
            try {
                dispatch(dataBufferRecord);
            } catch (Exception e) {
                logger.error("Error while publishing GPMF telemetry for {}.", streamLabel, e);
            }
        });
    }

    /**
     * Decodes one packet and notifies the registered outputs.
     */
    private void dispatch(DataBufferRecord dataBufferRecord) {
        long timestamp = System.currentTimeMillis();
        byte[] data = dataBufferRecord.getDataBuffer();

        GpmfPayload payload;
        try {
            payload = GpmfPayload.parse(data);
        } catch (RuntimeException e) {
            logger.warn("Dropping malformed GPMF payload of {} bytes on {}: {}", data.length, streamLabel, e.getMessage());
            return;
        }

        if (payload.isEmpty()) {
            logger.debug("GPMF payload on {} carried no sensor streams.", streamLabel);
            return;
        }

        for (GpmfPayloadListener listener : listeners) {
            try {
                listener.onGpmfPayload(payload, timestamp);
            } catch (Exception e) {
                // Keep one output's failure from starving the others
                logger.error("Error publishing GPMF telemetry to {}.", listener.getClass().getSimpleName(), e);
            }
        }
    }
}