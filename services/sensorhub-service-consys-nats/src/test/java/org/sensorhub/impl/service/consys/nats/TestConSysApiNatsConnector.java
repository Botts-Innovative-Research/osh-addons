/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.nats;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.sensorhub.impl.service.consys.ConSysApiServlet;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.InvalidRequestException.ErrorCode;
import org.sensorhub.impl.service.consys.RestApiSecurity;
import org.sensorhub.impl.service.consys.nats.ConSysApiNatsServiceConfig.DataStreamingMode;
import org.sensorhub.impl.service.consys.nats.publish.NatsOutputStream;
import org.sensorhub.impl.service.consys.resource.IResourceHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.slf4j.LoggerFactory;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.impl.Headers;


/**
 * Unit tests for {@link ConSysApiNatsConnector}: inbound message routing (echo
 * prevention, ingest POST mapping, event-subject rejection), the
 * {@code _control.get} request-reply read verb, and the lease-based ON_DEMAND
 * flow control (create-once semantics, renewal, unsubscribe, expiry sweep).
 *
 * <p>The CS API servlet and the NATS connection are mocked; connector entry
 * points are driven directly (no dispatcher), which is why the tests live in
 * the same package.</p>
 */
public class TestConSysApiNatsConnector
{
    static final String NODE_ID = "api";
    static final String DATA_SUBJECT = "api.systems.s1.datastreams.d1.observations:data.swe-json";
    static final String INBOX = "_INBOX.test.1";

    ConSysApiServlet servlet;
    IResourceHandler root;
    Connection nats;


    @Before
    public void setup()
    {
        servlet = mock(ConSysApiServlet.class);
        root = mock(IResourceHandler.class);
        var security = mock(RestApiSecurity.class);
        when(servlet.getLogger()).thenReturn(LoggerFactory.getLogger(TestConSysApiNatsConnector.class));
        when(servlet.getRootHandler()).thenReturn(root);
        when(servlet.getSecurityHandler()).thenReturn(security);

        nats = mock(Connection.class);
        when(nats.getMaxPayload()).thenReturn(1024L * 1024);
    }


    ConSysApiNatsConnector newConnector(DataStreamingMode mode, int leaseSeconds)
    {
        return new ConSysApiNatsConnector(servlet, nats, NODE_ID, null, mode, leaseSeconds);
    }


    static Message msg(String subject, String body, String replyTo, Headers headers)
    {
        var m = mock(Message.class);
        when(m.getSubject()).thenReturn(subject);
        when(m.getData()).thenReturn(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        when(m.getReplyTo()).thenReturn(replyTo);
        when(m.getHeaders()).thenReturn(headers);
        return m;
    }


    static Headers clientId(String id)
    {
        return new Headers().add(ConSysApiNatsConnector.CLIENT_ID_HEADER, id);
    }


    String lastReplyTo(String inbox)
    {
        var captor = ArgumentCaptor.forClass(byte[].class);
        verify(nats, atLeastOnce()).publish(eq(inbox), captor.capture());
        return new String(captor.getValue(), StandardCharsets.UTF_8);
    }


    // -------------------------------------------------------------------------
    // Inbound routing
    // -------------------------------------------------------------------------

    @Test
    public void echoedServerMessageIsIgnored() throws Exception
    {
        var connector = newConnector(DataStreamingMode.PROACTIVE, 0);
        var echoHeaders = new Headers().add(NatsOutputStream.ORIGIN_HEADER, NatsOutputStream.ORIGIN_SERVER);

        connector.onMessage(msg(DATA_SUBJECT, "{}", INBOX, echoHeaders));

        verifyNoInteractions(root);
        verify(nats, never()).publish(anyString(), any(byte[].class));
    }


    @Test
    public void dataPublishIsIngestedAsPost() throws Exception
    {
        var connector = newConnector(DataStreamingMode.PROACTIVE, 0);

        connector.onMessage(msg(DATA_SUBJECT, "{\"result\":1}", INBOX, null));

        var ctx = ArgumentCaptor.forClass(RequestContext.class);
        verify(root).doPost(ctx.capture());
        verify(root, never()).doGet(any());
        assertTrue("ack should be ok", lastReplyTo(INBOX).contains("\"status\":\"ok\""));
    }


    @Test
    public void dataPublishCarriesCorrelationId() throws Exception
    {
        var connector = newConnector(DataStreamingMode.PROACTIVE, 0);
        var headers = new Headers().add(NatsOutputStream.CORREL_ID_HEADER, "42");

        connector.onMessage(msg(DATA_SUBJECT, "{}", null, headers));

        var ctx = ArgumentCaptor.forClass(RequestContext.class);
        verify(root).doPost(ctx.capture());
        assertEquals(42L, ctx.getValue().getCorrelationID());
    }


    @Test
    public void dataPublishRejectionIsReportedToReplySubject() throws Exception
    {
        var connector = newConnector(DataStreamingMode.PROACTIVE, 0);
        doThrow(new InvalidRequestException(ErrorCode.BAD_PAYLOAD, "bad record"))
            .when(root).doPost(any());

        connector.onMessage(msg(DATA_SUBJECT, "junk", INBOX, null));

        assertTrue(lastReplyTo(INBOX).contains("bad record"));
    }


    @Test
    public void clientPublishToEventSubjectIsRejected() throws Exception
    {
        var connector = newConnector(DataStreamingMode.PROACTIVE, 0);

        connector.onMessage(msg("api.systems.s1", "{}", INBOX, null));

        verify(root, never()).doPost(any());
        assertTrue(lastReplyTo(INBOX).contains("not permitted"));
    }


    // -------------------------------------------------------------------------
    // _control.get request-reply reads
    // -------------------------------------------------------------------------

    @Test
    public void controlGetRepliesWithApiResponseBody() throws Exception
    {
        var connector = newConnector(DataStreamingMode.PROACTIVE, 0);
        doAnswer(inv -> {
            RequestContext ctx = inv.getArgument(0);
            ctx.getOutputStream().write("{\"items\":[]}".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(root).doGet(any());

        connector.onMessage(msg(NODE_ID + "._control.get", "systems?limit=1", INBOX, null));

        verify(root).doGet(any());
        assertEquals("{\"items\":[]}", lastReplyTo(INBOX));
    }


    @Test
    public void controlGetErrorRepliesWithErrorJson() throws Exception
    {
        var connector = newConnector(DataStreamingMode.PROACTIVE, 0);
        doThrow(new InvalidRequestException(ErrorCode.NOT_FOUND, "Resource not found"))
            .when(root).doGet(any());

        connector.onMessage(msg(NODE_ID + "._control.get", "nope/xyz", INBOX, null));

        assertTrue(lastReplyTo(INBOX).contains("Resource not found"));
    }


    @Test
    public void controlGetWithoutReplySubjectIsIgnored() throws Exception
    {
        var connector = newConnector(DataStreamingMode.PROACTIVE, 0);

        connector.onMessage(msg(NODE_ID + "._control.get", "systems", null, null));

        verifyNoInteractions(root);
    }


    @Test
    public void controlGetOversizedResponseRepliesWithError() throws Exception
    {
        when(nats.getMaxPayload()).thenReturn(4L);
        var connector = newConnector(DataStreamingMode.PROACTIVE, 0);
        doAnswer(inv -> {
            RequestContext ctx = inv.getArgument(0);
            ctx.getOutputStream().write("way more than four bytes".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(root).doGet(any());

        connector.onMessage(msg(NODE_ID + "._control.get", "systems", INBOX, null));

        var reply = lastReplyTo(INBOX);
        assertTrue("expected too-large error but got: " + reply, reply.contains("too large"));
    }


    @Test
    public void controlGetWorksInOnDemandModeToo() throws Exception
    {
        var connector = newConnector(DataStreamingMode.ON_DEMAND, 300);
        doAnswer(inv -> {
            RequestContext ctx = inv.getArgument(0);
            ctx.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(root).doGet(any());

        connector.onMessage(msg(NODE_ID + "._control.get", "systems", INBOX, null));

        assertEquals("{}", lastReplyTo(INBOX));
    }


    // -------------------------------------------------------------------------
    // ON_DEMAND flow control: leases
    // -------------------------------------------------------------------------

    @Test
    public void subscribeStartsStreamAndGrantsLease() throws Exception
    {
        var connector = newConnector(DataStreamingMode.ON_DEMAND, 300);

        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, INBOX, clientId("c1")));

        verify(root).doGet(any());
        assertTrue(connector.streams.containsKey(DATA_SUBJECT));
        assertEquals(1, connector.streams.get(DATA_SUBJECT).leaseExpiryByClient.size());
        assertTrue(lastReplyTo(INBOX).contains("\"leaseSeconds\":300"));
    }


    @Test
    public void secondClientJoinsWithoutSecondDoGet() throws Exception
    {
        var connector = newConnector(DataStreamingMode.ON_DEMAND, 300);

        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, null, clientId("c1")));
        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, null, clientId("c2")));

        // a second doGet would stack a duplicate event-bus subscription onto the
        // same handler and double every published message
        verify(root, times(1)).doGet(any());
        assertEquals(2, connector.streams.get(DATA_SUBJECT).leaseExpiryByClient.size());
    }


    @Test
    public void renewalBySameClientKeepsSingleLease() throws Exception
    {
        var connector = newConnector(DataStreamingMode.ON_DEMAND, 300);

        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, null, clientId("c1")));
        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, null, clientId("c1")));

        verify(root, times(1)).doGet(any());
        assertEquals(1, connector.streams.get(DATA_SUBJECT).leaseExpiryByClient.size());
    }


    @Test
    public void unsubscribeLastClientStopsStream() throws Exception
    {
        var connector = newConnector(DataStreamingMode.ON_DEMAND, 300);
        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, null, clientId("c1")));
        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, null, clientId("c2")));

        connector.onMessage(msg(NODE_ID + "._control.unsubscribe", DATA_SUBJECT, null, clientId("c1")));
        assertTrue("stream must survive while a lease remains", connector.streams.containsKey(DATA_SUBJECT));

        connector.onMessage(msg(NODE_ID + "._control.unsubscribe", DATA_SUBJECT, null, clientId("c2")));
        assertFalse("stream must stop when the last lease is dropped", connector.streams.containsKey(DATA_SUBJECT));
    }


    @Test
    public void expiredLeasesAreSweptAndStreamStopped() throws Exception
    {
        var connector = newConnector(DataStreamingMode.ON_DEMAND, 300);
        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, null, clientId("c1")));
        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, null, clientId("c2")));

        // c1's lease is already expired, c2's is still live
        connector.streams.get(DATA_SUBJECT).leaseExpiryByClient.put("c1", System.currentTimeMillis() - 1);
        connector.sweepExpiredLeases();
        assertEquals(1, connector.streams.get(DATA_SUBJECT).leaseExpiryByClient.size());
        assertTrue(connector.streams.containsKey(DATA_SUBJECT));

        // now c2's expires as well → stream must be reclaimed
        connector.streams.get(DATA_SUBJECT).leaseExpiryByClient.put("c2", System.currentTimeMillis() - 1);
        connector.sweepExpiredLeases();
        assertFalse("stream leaked after all leases expired", connector.streams.containsKey(DATA_SUBJECT));
    }


    @Test
    public void zeroLeaseSecondsMeansNoExpiry() throws Exception
    {
        var connector = newConnector(DataStreamingMode.ON_DEMAND, 0);
        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, null, clientId("c1")));

        connector.sweepExpiredLeases();

        assertTrue("lease must never expire when leaseSeconds=0", connector.streams.containsKey(DATA_SUBJECT));
    }


    @Test
    public void subscribeToNonDataSubjectIsRejected() throws Exception
    {
        var connector = newConnector(DataStreamingMode.ON_DEMAND, 300);

        connector.onMessage(msg(NODE_ID + "._control.subscribe", "api.systems.s1", INBOX, clientId("c1")));

        verify(root, never()).doGet(any());
        assertTrue(connector.streams.isEmpty());
        assertTrue(lastReplyTo(INBOX).contains("Not a resource data subject"));
    }


    @Test
    public void subscribeWithWildcardIsRejected() throws Exception
    {
        var connector = newConnector(DataStreamingMode.ON_DEMAND, 300);

        connector.onMessage(msg(NODE_ID + "._control.subscribe",
            "api.systems.*.datastreams.*.observations:data", INBOX, clientId("c1")));

        verify(root, never()).doGet(any());
        assertTrue(connector.streams.isEmpty());
        assertTrue(lastReplyTo(INBOX).toLowerCase().contains("wildcard"));
    }


    @Test
    public void subscribeFailureCleansUpStream() throws Exception
    {
        var connector = newConnector(DataStreamingMode.ON_DEMAND, 300);
        doThrow(new SecurityException("not authorized"))
            .when(root).doGet(any());

        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, INBOX, clientId("c1")));

        assertTrue("failed stream must not stay registered", connector.streams.isEmpty());
        assertTrue(lastReplyTo(INBOX).contains("not authorized"));
    }


    @Test
    public void controlChannelIgnoredInProactiveMode() throws Exception
    {
        var connector = newConnector(DataStreamingMode.PROACTIVE, 300);

        connector.onMessage(msg(NODE_ID + "._control.subscribe", DATA_SUBJECT, INBOX, clientId("c1")));

        verify(root, never()).doGet(any());
        assertTrue(connector.streams.isEmpty());
    }
}
