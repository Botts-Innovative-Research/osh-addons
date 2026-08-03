/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.nats.publish;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.util.Asserts;
import io.nats.client.Connection;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;


/**
 * <p>
 * Adapter output stream for sending data to one or more NATS subjects.<br/>
 * Data is buffered in a byte array, then packaged into a NATS message and
 * published to every configured subject when {@link #send()} is called (one
 * serialization, N publishes — used to feed the bare {@code :data} parent
 * subject and its explicit-format leaf from a single stream). This mirrors
 * the role of {@code MqttOutputStream} in the MQTT binding.
 * </p>
 *
 * <p>
 * Every message published by this stream carries the {@link #ORIGIN_HEADER}
 * header set to {@link #ORIGIN_SERVER}. The connector's inbound subscription
 * skips messages bearing this header so that OSH never re-ingests data it
 * published itself. This echo-prevention step is required on NATS because —
 * unlike an embedded MQTT broker — an external NATS server delivers a
 * publisher's own messages back to its wildcard subscriptions.
 * </p>
 *
 * <p>
 * When the payload MIME type is known, messages also carry a
 * {@link #CONTENT_TYPE_HEADER} header so subscribers can identify the wire
 * format without parsing the subject (essential on the bare {@code :data}
 * parent subject, whose format is the server default).
 * </p>
 *
 * @author CR31
 * @since June 29, 2026
 */
public class NatsOutputStream extends ByteArrayOutputStream
{
    /** Header carrying message provenance; OSH-originated messages set it to {@link #ORIGIN_SERVER}. */
    public static final String ORIGIN_HEADER = "CS-Origin";
    /** {@link #ORIGIN_HEADER} value marking a server-originated (OSH-published) message. */
    public static final String ORIGIN_SERVER = "server";
    /** Header carrying the publishing node's identity UUID: lets any node drop
     *  its own data arriving back via a third party, and lets bridges preserve
     *  origin. Distinct from {@link #ORIGIN_HEADER}, whose exact-match
     *  semantics must not change. */
    public static final String ORIGIN_NODE_HEADER = "CS-Origin-Node";
    /** Header carrying a Connected Systems correlation id (e.g. command id). */
    public static final String CORREL_ID_HEADER = "CS-Correlation-Id";
    /** Header carrying the MIME type of the message payload. */
    public static final String CONTENT_TYPE_HEADER = "Content-Type";

    static final Logger log = LoggerFactory.getLogger(NatsOutputStream.class);

    protected Connection connection;
    protected List<String> subjects;
    protected String contentType;
    protected String originNode;
    protected boolean autoSendOnFlush;
    protected boolean warnedOversize;
    /** Optional egress filter: called with each serialized payload before
     *  publishing; false = silently skip this message (buffer is still reset).
     *  Used to suppress republication of ingested observations. */
    protected java.util.function.Predicate<byte[]> egressFilter;


    /** Set the egress filter (see {@link #egressFilter}). */
    public void setEgressFilter(java.util.function.Predicate<byte[]> filter)
    {
        this.egressFilter = filter;
    }


    public NatsOutputStream(Connection connection, String subject, int bufferSize, boolean autoSendOnFlush)
    {
        this(connection, List.of(Asserts.checkNotNull(subject, "subject")), null, null, bufferSize, autoSendOnFlush);
    }


    public NatsOutputStream(Connection connection, List<String> subjects, String contentType,
        int bufferSize, boolean autoSendOnFlush)
    {
        this(connection, subjects, contentType, null, bufferSize, autoSendOnFlush);
    }


    /**
     * Full constructor. {@code originNode} (may be null) is this node's identity
     * UUID, attached to every message as {@link #ORIGIN_NODE_HEADER}.
     */
    public NatsOutputStream(Connection connection, List<String> subjects, String contentType,
        String originNode, int bufferSize, boolean autoSendOnFlush)
    {
        super(bufferSize);
        this.connection = Asserts.checkNotNull(connection, Connection.class);
        this.subjects = List.copyOf(Asserts.checkNotNullOrEmpty(subjects, "subjects"));
        this.contentType = contentType;
        this.originNode = originNode;
        this.autoSendOnFlush = autoSendOnFlush;
    }


    @Override
    public void close()
    {
    }


    @Override
    public void flush() throws IOException
    {
        if (autoSendOnFlush)
            send();
    }


    public void send() throws IOException
    {
        send(0);
    }


    public synchronized void send(long correlId) throws IOException
    {
        // do nothing if no more bytes have been written since last call
        if (count == 0)
            return;

        // Drop (don't throw) messages over the server's max payload: an exception
        // here propagates to the EventBus subscriber thread and cancels the whole
        // stream subscription, stalling publishing for this datastream
        var maxPayload = connection.getMaxPayload();
        if (maxPayload > 0 && count > maxPayload)
        {
            if (!warnedOversize)
            {
                log.warn("Dropping {}-byte message on subject(s) {} — exceeds NATS server max payload "
                    + "({} bytes). Raise the server's max_payload or use a more compact data format. "
                    + "(Further drops on these subjects logged at debug level.)",
                    count, subjects, maxPayload);
                warnedOversize = true;
            }
            else
                log.debug("Dropping {}-byte message on subject(s) {} (> max payload {})", count, subjects, maxPayload);
            this.reset();
            return;
        }

        var data = toByteArray();

        // a message failing the egress filter was received from the broker
        // and must not go back out
        if (egressFilter != null && !egressFilter.test(data))
        {
            this.reset();
            return;
        }

        var headers = new Headers().add(ORIGIN_HEADER, ORIGIN_SERVER);
        if (contentType != null)
            headers.add(CONTENT_TYPE_HEADER, contentType);
        if (originNode != null)
            headers.add(ORIGIN_NODE_HEADER, originNode);
        if (correlId != 0)
            headers.add(CORREL_ID_HEADER, Long.toString(correlId));
        for (var subject : subjects)
        {
            connection.publish(NatsMessage.builder()
                .subject(subject)
                .headers(headers)
                .data(data)
                .build());
        }

        // reset so we can write again into the same buffer
        this.reset();
    }
}
