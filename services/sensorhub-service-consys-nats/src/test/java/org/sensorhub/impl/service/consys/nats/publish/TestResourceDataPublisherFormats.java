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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.impl.service.consys.ConSysApiServlet;
import org.sensorhub.impl.service.consys.RestApiSecurity;
import org.sensorhub.impl.service.consys.nats.ConSysApiNatsServiceConfig;
import org.sensorhub.impl.service.consys.nats.subject.ConSysSubjectValidator;
import org.sensorhub.impl.service.consys.resource.IResourceHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.slf4j.LoggerFactory;
import io.nats.client.Connection;
import net.opengis.swe.v20.BinaryEncoding;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.TextEncoding;


/**
 * Unit tests for {@link ResourceDataPublisher}'s output-format resolution and
 * subject planning: the configured token list becomes one proactive stream per
 * valid token on its {@code :data.<token>} leaf, with unknown/blank/duplicate
 * tokens dropped and an empty result falling back to a single server-default
 * stream whose concrete token is resolved per datastream at stream-open time.
 * The server-default format additionally feeds the bare {@code :data} parent
 * subject (piggybacked on the matching leaf stream when configured, as an
 * extra bare-only stream otherwise).
 */
public class TestResourceDataPublisherFormats
{
    private ResourceDataPublisher newPublisher(List<String> tokens)
    {
        return newPublisher(tokens, null);
    }


    private ResourceDataPublisher newPublisher(List<String> tokens, IObsSystemDatabase db)
    {
        return newPublisher(tokens, db, null);
    }


    private ResourceDataPublisher newPublisher(List<String> tokens, IObsSystemDatabase db, List<String> relayGlobs)
    {
        return newPublisher(tokens, db, relayGlobs, null);
    }


    private ResourceDataPublisher newPublisher(List<String> tokens, IObsSystemDatabase db,
        List<String> relayGlobs, List<String> obsExcludeGlobs)
    {
        // collaborators aren't touched by format resolution
        return new ResourceDataPublisher(null, null, "api", null, db, null, null,
            tokens, null, relayGlobs, obsExcludeGlobs, null,
            LoggerFactory.getLogger(TestResourceDataPublisherFormats.class));
    }


    @Test
    public void multipleValidTokensYieldOneStreamEach()
    {
        var pub = newPublisher(List.of("swe-json", "swe-proto", "swe-flatbuffers"));

        assertEquals(3, pub.outputFormats.size());
        assertEquals("swe-json", pub.outputFormats.get(0).token());
        assertEquals(ResourceFormat.SWE_JSON, pub.outputFormats.get(0).format());
        assertEquals("swe-proto", pub.outputFormats.get(1).token());
        assertEquals("swe-flatbuffers", pub.outputFormats.get(2).token());
    }


    @Test
    public void emptyListFallsBackToServerDefault()
    {
        var pub = newPublisher(List.of());

        assertEquals(1, pub.outputFormats.size());
        assertNull("server default has no token", pub.outputFormats.get(0).token());
        assertNull("server default has no explicit format", pub.outputFormats.get(0).format());
    }


    @Test
    public void nullListFallsBackToServerDefault()
    {
        var pub = newPublisher(null);

        assertEquals(1, pub.outputFormats.size());
        assertNull(pub.outputFormats.get(0).token());
    }


    @Test
    public void unknownBlankAndDuplicateTokensAreDropped()
    {
        var pub = newPublisher(Arrays.asList("swe-json", "bogus", "", null, "swe-json"));

        assertEquals(1, pub.outputFormats.size());
        assertEquals("swe-json", pub.outputFormats.get(0).token());
    }


    @Test
    public void allInvalidTokensFallBackToServerDefault()
    {
        var pub = newPublisher(Arrays.asList("bogus", ""));

        assertEquals(1, pub.outputFormats.size());
        assertNull(pub.outputFormats.get(0).token());
    }


    @Test
    public void serverDefaultResolvesToJsonTokenForTextStreams()
    {
        var pub = newPublisher(List.of(), mockDb(mock(TextEncoding.class)));

        var of = pub.resolveDefaultFormat(BigId.fromLong(1, 1));
        assertEquals("json", of.token());
        assertEquals(ResourceFormat.JSON, of.format());
    }


    @Test
    public void serverDefaultResolvesToSweBinaryTokenForBinaryStreams()
    {
        var pub = newPublisher(List.of(), mockDb(mock(BinaryEncoding.class)));

        var of = pub.resolveDefaultFormat(BigId.fromLong(1, 1));
        assertEquals("swe-binary", of.token());
        assertEquals(ResourceFormat.SWE_BINARY, of.format());
    }


    @Test
    public void serverDefaultResolvesToJsonTokenForUnknownDatastream()
    {
        var store = mock(IDataStreamStore.class);
        when(store.get(any())).thenReturn(null);
        var db = mock(IObsSystemDatabase.class);
        when(db.getDataStreamStore()).thenReturn(store);

        var of = newPublisher(List.of(), db).resolveDefaultFormat(BigId.fromLong(1, 1));
        assertEquals("json", of.token());
    }


    private IObsSystemDatabase mockDb(DataEncoding recordEncoding)
    {
        var dsInfo = mock(IDataStreamInfo.class);
        when(dsInfo.getRecordEncoding()).thenReturn(recordEncoding);
        var store = mock(IDataStreamStore.class);
        when(store.get(any())).thenReturn(dsInfo);
        var db = mock(IObsSystemDatabase.class);
        when(db.getDataStreamStore()).thenReturn(store);
        return db;
    }


    @Test
    public void planFeedsBareSubjectFromMatchingConfiguredStream()
    {
        var pub = newPublisher(List.of("json", "swe-proto"));
        var def = new ResourceDataPublisher.OutputFormat("json", ResourceFormat.JSON);

        var plans = pub.planStreams("api.x.observations:data", def);

        assertEquals(2, plans.size());
        assertEquals(List.of("api.x.observations:data", "api.x.observations:data.json"),
            plans.get(0).subjects());
        assertEquals(List.of("api.x.observations:data.swe-proto"), plans.get(1).subjects());
    }


    @Test
    public void planAddsBareOnlyStreamWhenDefaultNotConfigured()
    {
        var pub = newPublisher(List.of("swe-proto"));
        var def = new ResourceDataPublisher.OutputFormat("json", ResourceFormat.JSON);

        var plans = pub.planStreams("api.x.observations:data", def);

        assertEquals(2, plans.size());
        assertEquals(List.of("api.x.observations:data.swe-proto"), plans.get(0).subjects());
        assertEquals(List.of("api.x.observations:data"), plans.get(1).subjects());
        assertEquals(def, plans.get(1).output());
    }


    @Test
    public void planServerDefaultCoversBareAndResolvedLeaf()
    {
        var pub = newPublisher(List.of()); // empty config => single server-default stream
        var def = new ResourceDataPublisher.OutputFormat("swe-binary", ResourceFormat.SWE_BINARY);

        var plans = pub.planStreams("api.x.observations:data", def);

        assertEquals(1, plans.size());
        assertEquals(List.of("api.x.observations:data", "api.x.observations:data.swe-binary"),
            plans.get(0).subjects());
        assertEquals(def, plans.get(0).output());
    }


    @Test
    public void emptyRelayPatternsRelayEveryStream()
    {
        var pub = newPublisher(List.of(), null, List.of());
        assertTrue(pub.shouldRelayCommands(BigId.fromLong(1, 1)));
    }


    @Test
    public void relayPatternsScopeRelayByParentSystemUid()
    {
        var pub = newPublisher(List.of(), mockSystemDb("urn:osh:sensor:counter:c1:mirror"),
            List.of("*:mirror"));
        assertTrue(pub.shouldRelayCommands(BigId.fromLong(1, 1)));

        var pub2 = newPublisher(List.of(), mockSystemDb("urn:osh:sensor:counter:c1"),
            List.of("*:mirror", "urn:osh:system:remote:*"));
        assertFalse("non-matching UID must fall back to echo", pub2.shouldRelayCommands(BigId.fromLong(1, 1)));

        var pub3 = newPublisher(List.of(), mockSystemDb("urn:osh:system:remote:drone42"),
            List.of("*:mirror", "urn:osh:system:remote:*"));
        assertTrue(pub3.shouldRelayCommands(BigId.fromLong(1, 1)));

        // no '*' = exact literal match only
        var pub4 = newPublisher(List.of(), mockSystemDb("urn:osh:sensor:counter:c1"),
            List.of("urn:osh:sensor:counter:c1"));
        assertTrue(pub4.shouldRelayCommands(BigId.fromLong(1, 1)));
    }


    @Test
    public void unresolvableSystemUidFallsBackToEcho()
    {
        var pub = newPublisher(List.of(), mockSystemDb(null), List.of("*:mirror"));
        assertFalse(pub.shouldRelayCommands(BigId.fromLong(1, 1)));
    }


    private IObsSystemDatabase mockSystemDb(String sysUid)
    {
        var store = mock(org.sensorhub.api.datastore.system.ISystemDescStore.class);
        if (sysUid != null)
        {
            var sys = mock(org.sensorhub.api.system.ISystemWithDesc.class);
            when(sys.getUniqueIdentifier()).thenReturn(sysUid);
            when(store.getCurrentVersion(any(BigId.class))).thenReturn(sys);
        }
        var db = mock(IObsSystemDatabase.class);
        when(db.getSystemDescStore()).thenReturn(store);
        return db;
    }


    @Test
    public void configEnumTokensAllResolve()
    {
        // every ProactiveFormat enum constant must map to a known subject token
        for (var f : ConSysApiNatsServiceConfig.ProactiveFormat.values())
        {
            assertNotNull("enum token '" + f.token + "' missing from FORMAT_SUBTOPICS",
                ConSysSubjectValidator.FORMAT_SUBTOPICS.get(f.token));
        }
    }


    /*
     * A datastream that cannot be served in one of the configured proactive
     * formats (the streaming GET fails for that format) must still be published
     * in the formats it does support — and not at all if it supports none.
     */

    private ResourceDataPublisher newStreamingPublisher(ConSysApiServlet servlet, List<String> tokens)
    {
        return newStreamingPublisher(servlet, tokens, null, null);
    }


    /** Streaming publisher whose db resolves every datastream to a text-encoded
     *  stream and (when {@code sysUid} != null) every system to that UID. */
    private ResourceDataPublisher newStreamingPublisher(ConSysApiServlet servlet, List<String> tokens,
        List<String> obsExcludeGlobs, String sysUid)
    {
        var idEncoders = mock(IdEncoders.class);
        var sysEnc = mock(IdEncoder.class);
        var dsEnc = mock(IdEncoder.class);
        var csEnc = mock(IdEncoder.class);
        when(sysEnc.encodeID(any(BigId.class))).thenReturn("sys1");
        when(dsEnc.encodeID(any(BigId.class))).thenReturn("ds1");
        when(csEnc.encodeID(any(BigId.class))).thenReturn("cs1");
        when(idEncoders.getSystemIdEncoder()).thenReturn(sysEnc);
        when(idEncoders.getDataStreamIdEncoder()).thenReturn(dsEnc);
        when(idEncoders.getCommandStreamIdEncoder()).thenReturn(csEnc);

        var dsInfo = mock(IDataStreamInfo.class);
        when(dsInfo.getRecordEncoding()).thenReturn(mock(TextEncoding.class));
        var dsStore = mock(IDataStreamStore.class);
        when(dsStore.get(any())).thenReturn(dsInfo);
        when(dsStore.selectEntries(any(org.sensorhub.api.datastore.obs.DataStreamFilter.class)))
            .thenAnswer(inv -> java.util.stream.Stream.of(java.util.Map.entry(
                new org.sensorhub.api.datastore.obs.DataStreamKey(BigId.fromLong(1, 2)), dsInfo)));
        var db = mock(IObsSystemDatabase.class);
        when(db.getDataStreamStore()).thenReturn(dsStore);
        if (sysUid != null)
        {
            var sys = mock(org.sensorhub.api.system.ISystemWithDesc.class);
            when(sys.getUniqueIdentifier()).thenReturn(sysUid);
            var sysStore = mock(org.sensorhub.api.datastore.system.ISystemDescStore.class);
            when(sysStore.getCurrentVersion(any(BigId.class))).thenReturn(sys);
            when(db.getSystemDescStore()).thenReturn(sysStore);
        }
        else
        {
            when(db.getSystemDescStore()).thenReturn(
                mock(org.sensorhub.api.datastore.system.ISystemDescStore.class));
        }

        return new ResourceDataPublisher(servlet, mock(Connection.class), "api", null,
            db, idEncoders, null,
            tokens, null, null, obsExcludeGlobs, null,
            LoggerFactory.getLogger(TestResourceDataPublisherFormats.class));
    }


    private ConSysApiServlet mockServlet(ResourceFormat failingFormat) throws Exception
    {
        var servlet = mock(ConSysApiServlet.class);
        var rootHandler = mock(IResourceHandler.class);
        when(servlet.getRootHandler()).thenReturn(rootHandler);
        when(servlet.getSecurityHandler()).thenReturn(mock(RestApiSecurity.class));
        doAnswer(inv -> {
            RequestContext ctx = inv.getArgument(0);
            if (failingFormat == null || failingFormat.equals(ctx.getFormat()))
                throw new IOException("datastream cannot be represented in " + ctx.getFormat());
            return null;
        }).when(rootHandler).doGet(any(RequestContext.class));
        return servlet;
    }


    @Test
    public void unsupportedFormatIsSkippedOtherFormatsStillPublished() throws Exception
    {
        var servlet = mockServlet(ResourceFormat.SWE_JSON); // swe-json GETs fail, json GETs succeed
        var pub = newStreamingPublisher(servlet, List.of("json", "swe-json"));

        pub.startStream(BigId.fromLong(1, 2), BigId.fromLong(1, 3));

        var handlers = pub.streams.get(BigId.fromLong(1, 2));
        assertNotNull("datastream must still be published in the supported format", handlers);
        assertEquals("only the json stream should be open", 1, handlers.size());
    }


    @Test
    public void datastreamSupportingNoConfiguredFormatIsNotPublished() throws Exception
    {
        var servlet = mockServlet(null); // every GET fails
        var pub = newStreamingPublisher(servlet, List.of("json", "swe-json"));

        pub.startStream(BigId.fromLong(1, 2), BigId.fromLong(1, 3));

        assertTrue("no stream must be registered", pub.streams.isEmpty());
    }


    /*
     * Systems this node did not originate get NO proactive observation data
     * streams — via UID exclude globs or the IngestOriginRegistry — while
     * native systems and command streams are unaffected. The registry is
     * JVM-global, so tests reset it.
     */

    @org.junit.Before
    @org.junit.After
    public void clearRegistry()
    {
        IngestOriginRegistry.clearForTests();
    }


    /** Servlet whose streaming GETs always succeed (failing format never matches). */
    private ConSysApiServlet okServlet() throws Exception
    {
        return mockServlet(ResourceFormat.SWE_XML);
    }


    @Test
    public void excludeGlobSuppressesObsStreams() throws Exception
    {
        var pub = newStreamingPublisher(okServlet(), List.of("json"),
            List.of("*:mirror"), "urn:osh:sensor:test:001:mirror");

        pub.startStream(BigId.fromLong(1, 2), BigId.fromLong(1, 3));

        assertTrue("excluded system must get no obs streams", pub.streams.isEmpty());
    }


    @Test
    public void registryForeignSuppressesObsStreams() throws Exception
    {
        IngestOriginRegistry.record("urn:osh:sensor:remote:x", "remote-node");
        var pub = newStreamingPublisher(okServlet(), List.of("json"),
            null, "urn:osh:sensor:remote:x");

        pub.startStream(BigId.fromLong(1, 2), BigId.fromLong(1, 3));

        assertTrue("registry-foreign system must get no obs streams", pub.streams.isEmpty());
    }


    @Test
    public void nativeSystemStillPublishes() throws Exception
    {
        IngestOriginRegistry.record("urn:osh:sensor:remote:x", "remote-node");
        var pub = newStreamingPublisher(okServlet(), List.of("json"),
            List.of("*:mirror"), "urn:osh:sensor:native:001");

        pub.startStream(BigId.fromLong(1, 2), BigId.fromLong(1, 3));

        assertNotNull("native system must keep publishing", pub.streams.get(BigId.fromLong(1, 2)));
    }


    @Test
    public void unresolvableUidStillPublishes() throws Exception
    {
        // fail OPEN — deliberately the opposite of shouldRelayCommands: a native
        // system must never go silent because its UID could not be resolved
        var pub = newStreamingPublisher(okServlet(), List.of("json"),
            List.of("*:mirror"), null); // system store resolves nothing

        pub.startStream(BigId.fromLong(1, 2), BigId.fromLong(1, 3));

        assertNotNull("unresolvable UID must fail open (publish)", pub.streams.get(BigId.fromLong(1, 2)));
    }


    @Test
    public void lateRegistryRecordClosesOpenObsStreams() throws Exception
    {
        var uid = "urn:osh:sensor:late:001";
        var pub = newStreamingPublisher(okServlet(), List.of("json"), null, uid);

        pub.startStream(BigId.fromLong(1, 2), BigId.fromLong(1, 3));
        assertNotNull("stream open before the system is marked foreign", pub.streams.get(BigId.fromLong(1, 2)));

        // simulate client discovery marking the system foreign after the scan
        // (the listener is wired in start(); the handling logic is what matters)
        IngestOriginRegistry.record(uid, "remote-node");
        pub.onSystemMarkedForeign(uid);

        assertTrue("open obs streams must be closed on late foreign mark", pub.streams.isEmpty());
    }


    @Test
    public void exclusionDoesNotAffectCommandStreams() throws Exception
    {
        IngestOriginRegistry.record("urn:osh:sensor:remote:x", "remote-node");
        var pub = newStreamingPublisher(okServlet(), List.of("json"),
            null, "urn:osh:sensor:remote:x");

        // obs side suppressed...
        pub.startStream(BigId.fromLong(1, 2), BigId.fromLong(1, 3));
        assertTrue(pub.streams.isEmpty());

        // ...but command/status streams still open (command passback must work;
        // the echo half dies on the null command store and is skipped, the
        // status half is an ordinary streaming GET and succeeds)
        pub.startCommandStreams(BigId.fromLong(1, 9), BigId.fromLong(1, 3));
        assertNotNull("command/status streams must stay on for excluded systems",
            pub.cmdStreams.get(BigId.fromLong(1, 9)));
    }
}
