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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import io.nats.client.Connection;
import io.nats.client.Message;


/**
 * Unit tests for {@link NatsOutputStream}: buffered publish semantics, the
 * {@code CS-Origin} echo-prevention header, correlation-id propagation, and
 * the max-payload guard (oversized messages must be dropped and logged, never
 * thrown — an exception would propagate to the EventBus subscriber thread and
 * cancel the whole stream subscription).
 */
public class TestNatsOutputStream
{
    static final String SUBJECT = "api.systems.s1.datastreams.d1.observations:data";

    Connection nats;


    @Before
    public void setup()
    {
        nats = mock(Connection.class);
        when(nats.getMaxPayload()).thenReturn(1024L * 1024);
    }


    @Test
    public void sendPublishesBufferWithOriginHeaderAndResets() throws Exception
    {
        var os = new NatsOutputStream(nats, SUBJECT, 64, false);
        os.write("hello".getBytes(StandardCharsets.UTF_8));
        os.send();

        var captor = ArgumentCaptor.forClass(Message.class);
        verify(nats).publish(captor.capture());
        var msg = captor.getValue();
        assertEquals(SUBJECT, msg.getSubject());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), msg.getData());
        assertEquals(NatsOutputStream.ORIGIN_SERVER,
            msg.getHeaders().getFirst(NatsOutputStream.ORIGIN_HEADER));

        // buffer was reset — a send with no new bytes must not publish again
        os.send();
        verify(nats, times(1)).publish(any(Message.class));
    }


    @Test
    public void sendWithCorrelationIdAddsHeader() throws Exception
    {
        var os = new NatsOutputStream(nats, SUBJECT, 64, false);
        os.write("x".getBytes(StandardCharsets.UTF_8));
        os.send(42L);

        var captor = ArgumentCaptor.forClass(Message.class);
        verify(nats).publish(captor.capture());
        assertEquals("42", captor.getValue().getHeaders().getFirst(NatsOutputStream.CORREL_ID_HEADER));
    }


    @Test
    public void originNodeHeaderAddedWhenSet() throws Exception
    {
        var os = new NatsOutputStream(nats, java.util.List.of(SUBJECT), null, "uuid-X", 64, false);
        os.write("x".getBytes(StandardCharsets.UTF_8));
        os.send();

        var captor = ArgumentCaptor.forClass(Message.class);
        verify(nats).publish(captor.capture());
        assertEquals("uuid-X", captor.getValue().getHeaders().getFirst(NatsOutputStream.ORIGIN_NODE_HEADER));
    }


    @Test
    public void noOriginNodeHeaderWhenNull() throws Exception
    {
        var os = new NatsOutputStream(nats, SUBJECT, 64, false);
        os.write("x".getBytes(StandardCharsets.UTF_8));
        os.send();

        var captor = ArgumentCaptor.forClass(Message.class);
        verify(nats).publish(captor.capture());
        assertNull(captor.getValue().getHeaders().getFirst(NatsOutputStream.ORIGIN_NODE_HEADER));
    }


    @Test
    public void egressFilterSuppressesPublishAndResetsBuffer() throws Exception
    {
        var os = new NatsOutputStream(nats, SUBJECT, 64, false);
        os.setEgressFilter(payload -> false); // suppress everything
        os.write("held".getBytes(StandardCharsets.UTF_8));
        os.send();
        verify(nats, never()).publish(any(Message.class));

        // buffer was reset by the suppressed send; a passing message flows
        os.setEgressFilter(payload -> true);
        os.write("pass".getBytes(StandardCharsets.UTF_8));
        os.send();

        var captor = ArgumentCaptor.forClass(Message.class);
        verify(nats).publish(captor.capture());
        assertArrayEquals("pass".getBytes(StandardCharsets.UTF_8), captor.getValue().getData());
    }


    @Test
    public void flushOnlySendsWhenAutoSendEnabled() throws Exception
    {
        var buffered = new NatsOutputStream(nats, SUBJECT, 64, false);
        buffered.write("x".getBytes(StandardCharsets.UTF_8));
        buffered.flush();
        verify(nats, never()).publish(any(Message.class));

        var autoSend = new NatsOutputStream(nats, SUBJECT, 64, true);
        autoSend.write("x".getBytes(StandardCharsets.UTF_8));
        autoSend.flush();
        verify(nats, times(1)).publish(any(Message.class));
    }


    @Test
    public void contentTypeHeaderAddedWhenSet() throws Exception
    {
        var os = new NatsOutputStream(nats, java.util.List.of(SUBJECT), "application/json", 64, false);
        os.write("x".getBytes(StandardCharsets.UTF_8));
        os.send();

        var captor = ArgumentCaptor.forClass(Message.class);
        verify(nats).publish(captor.capture());
        assertEquals("application/json",
            captor.getValue().getHeaders().getFirst(NatsOutputStream.CONTENT_TYPE_HEADER));
    }


    @Test
    public void noContentTypeHeaderWhenUnknown() throws Exception
    {
        var os = new NatsOutputStream(nats, SUBJECT, 64, false);
        os.write("x".getBytes(StandardCharsets.UTF_8));
        os.send();

        var captor = ArgumentCaptor.forClass(Message.class);
        verify(nats).publish(captor.capture());
        assertNull(captor.getValue().getHeaders().getFirst(NatsOutputStream.CONTENT_TYPE_HEADER));
    }


    @Test
    public void multiSubjectSendPublishesSamePayloadToEachSubject() throws Exception
    {
        var leaf = SUBJECT + ".json";
        var os = new NatsOutputStream(nats, java.util.List.of(SUBJECT, leaf), "application/json", 64, false);
        os.write("hello".getBytes(StandardCharsets.UTF_8));
        os.send();

        var captor = ArgumentCaptor.forClass(Message.class);
        verify(nats, times(2)).publish(captor.capture());
        var msgs = captor.getAllValues();
        assertEquals(SUBJECT, msgs.get(0).getSubject());
        assertEquals(leaf, msgs.get(1).getSubject());
        for (var msg : msgs)
        {
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), msg.getData());
            assertEquals(NatsOutputStream.ORIGIN_SERVER,
                msg.getHeaders().getFirst(NatsOutputStream.ORIGIN_HEADER));
        }

        // buffer reset applies to the whole send, not per subject
        os.send();
        verify(nats, times(2)).publish(any(Message.class));
    }


    @Test
    public void oversizedMessageIsDroppedNotThrown() throws Exception
    {
        when(nats.getMaxPayload()).thenReturn(10L);
        var os = new NatsOutputStream(nats, SUBJECT, 64, false);

        os.write("this is well over ten bytes".getBytes(StandardCharsets.UTF_8));
        os.send();  // must not throw
        verify(nats, never()).publish(any(Message.class));

        // buffer must be reset so the stream keeps working for the next message
        os.write("small".getBytes(StandardCharsets.UTF_8));
        os.send();
        var captor = ArgumentCaptor.forClass(Message.class);
        verify(nats).publish(captor.capture());
        assertArrayEquals("small".getBytes(StandardCharsets.UTF_8), captor.getValue().getData());
    }


    @Test
    public void unknownMaxPayloadDisablesGuard() throws Exception
    {
        when(nats.getMaxPayload()).thenReturn(0L);
        var os = new NatsOutputStream(nats, SUBJECT, 64, false);

        os.write(new byte[100_000]);
        os.send();

        verify(nats, times(1)).publish(any(Message.class));
    }
}
