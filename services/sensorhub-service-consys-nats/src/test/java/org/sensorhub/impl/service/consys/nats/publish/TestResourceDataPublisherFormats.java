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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.impl.service.consys.nats.ConSysApiNatsServiceConfig;
import org.sensorhub.impl.service.consys.nats.subject.ConSysSubjectValidator;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.slf4j.LoggerFactory;
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
        // collaborators aren't touched by format resolution
        return new ResourceDataPublisher(null, null, "api", null, db, null, null,
            tokens, null, relayGlobs, LoggerFactory.getLogger(TestResourceDataPublisherFormats.class));
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
}
