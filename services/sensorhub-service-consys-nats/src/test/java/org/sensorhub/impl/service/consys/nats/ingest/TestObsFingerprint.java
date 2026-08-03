/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.nats.ingest;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.slf4j.LoggerFactory;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.Quantity;
import net.opengis.swe.v20.Time;


/**
 * Unit tests for {@link ObsFingerprint}: phenomenonTime extraction from the
 * supported wire formats (ISO string + epoch-seconds double, ms truncation),
 * fail-open behavior on everything else, time-field discovery in a record
 * structure, and the ms-granularity existence query.
 */
public class TestObsFingerprint
{
    @org.junit.Before
    public void clearSharedMemory()
    {
        ObsFingerprint.clearRecentIngestsForTests(); // JVM-shared static cache
    }


    static byte[] bytes(String s)
    {
        return s.getBytes(StandardCharsets.UTF_8);
    }


    @Test
    public void omJsonIsoPhenomenonTimeExtracted()
    {
        var t = ObsFingerprint.extractPhenTimeMs(
            bytes("{\"phenomenonTime\":\"2026-08-03T10:15:30.123Z\",\"result\":{}}"), "json", null);
        assertEquals((Long)Instant.parse("2026-08-03T10:15:30.123Z").toEpochMilli(), t);

        var t2 = ObsFingerprint.extractPhenTimeMs(
            bytes("{\"phenomenonTime\":\"2026-08-03T10:15:30.123Z\"}"), "om-json", null);
        assertEquals(t, t2);
    }


    @Test
    public void numericEpochSecondsTruncatedToMs()
    {
        // 1234.5678 s => 1234567.8 ms => floor 1234567
        var t = ObsFingerprint.extractPhenTimeMs(
            bytes("{\"phenomenonTime\":1234.5678}"), "json", null);
        assertEquals((Long)1234567L, t);
    }


    @Test
    public void sweJsonTimeFieldByName()
    {
        var iso = ObsFingerprint.extractPhenTimeMs(
            bytes("{\"sampleTime\":\"2026-08-03T10:15:30Z\",\"value\":1}"), "swe-json", "sampleTime");
        assertEquals((Long)Instant.parse("2026-08-03T10:15:30Z").toEpochMilli(), iso);

        var dbl = ObsFingerprint.extractPhenTimeMs(
            bytes("{\"sampleTime\":1000.25,\"value\":1}"), "swe-json", "sampleTime");
        assertEquals((Long)1000250L, dbl);
    }


    @Test
    public void failsOpenOnEverythingElse()
    {
        assertNull("binary format", ObsFingerprint.extractPhenTimeMs(bytes("{}"), "swe-proto", null));
        assertNull("unknown format", ObsFingerprint.extractPhenTimeMs(bytes("{}"), "bogus", null));
        assertNull("null format", ObsFingerprint.extractPhenTimeMs(bytes("{}"), null, null));
        assertNull("missing field", ObsFingerprint.extractPhenTimeMs(bytes("{\"a\":1}"), "json", null));
        assertNull("swe-json without field name", ObsFingerprint.extractPhenTimeMs(bytes("{\"t\":1}"), "swe-json", null));
        assertNull("malformed json", ObsFingerprint.extractPhenTimeMs(bytes("not json"), "json", null));
        assertNull("malformed time", ObsFingerprint.extractPhenTimeMs(bytes("{\"phenomenonTime\":\"yesterday\"}"), "json", null));
        assertNull("non-object payload", ObsFingerprint.extractPhenTimeMs(bytes("[1,2]"), "json", null));
        assertNull("null payload", ObsFingerprint.extractPhenTimeMs(null, "json", null));
    }


    @Test
    public void findsFirstTimeComponentName()
    {
        var scalar = mock(Quantity.class);
        when(scalar.getName()).thenReturn("value");
        var time = mock(Time.class);
        when(time.getName()).thenReturn("sampleTime");
        var rec = mock(DataRecord.class);
        when(rec.getComponentCount()).thenReturn(2);
        when(rec.getComponent(0)).thenReturn(scalar);
        when(rec.getComponent(1)).thenReturn(time);

        assertEquals("sampleTime", ObsFingerprint.findTimeFieldName(rec));
    }


    @Test
    public void noTimeComponentOrNonRecordYieldsNull()
    {
        var rec = mock(DataRecord.class);
        when(rec.getComponentCount()).thenReturn(1);
        when(rec.getComponent(0)).thenReturn(mock(Quantity.class));
        assertNull(ObsFingerprint.findTimeFieldName(rec));

        assertNull(ObsFingerprint.findTimeFieldName(mock(DataComponent.class)));
        assertNull(ObsFingerprint.findTimeFieldName(null));
    }


    @Test
    public void existsQueriesMsWindowWithLimitOne()
    {
        var obsStore = mock(IObsStore.class);
        when(obsStore.select(any(ObsFilter.class))).thenAnswer(inv -> Stream.of(mock(IObsData.class)));
        var db = mock(IObsSystemDatabase.class);
        when(db.getObservationStore()).thenReturn(obsStore);

        var fp = new ObsFingerprint(db, LoggerFactory.getLogger(TestObsFingerprint.class));
        var dsId = BigId.fromLong(1, 42);
        assertTrue(fp.exists(dsId, 1700000000123L));

        var captor = ArgumentCaptor.forClass(ObsFilter.class);
        org.mockito.Mockito.verify(obsStore).select(captor.capture());
        var filter = captor.getValue();
        assertEquals(Instant.ofEpochMilli(1700000000123L), filter.getPhenomenonTime().getMin());
        assertEquals(Instant.ofEpochMilli(1700000000124L), filter.getPhenomenonTime().getMax());
        assertEquals(1L, filter.getLimit());
    }


    @Test
    public void existsFalseOnEmptyResult()
    {
        var obsStore = mock(IObsStore.class);
        when(obsStore.select(any(ObsFilter.class))).thenAnswer(inv -> Stream.empty());
        var db = mock(IObsSystemDatabase.class);
        when(db.getObservationStore()).thenReturn(obsStore);

        var fp = new ObsFingerprint(db, LoggerFactory.getLogger(TestObsFingerprint.class));
        assertFalse(fp.exists(BigId.fromLong(1, 42), 1700000000123L));
    }


    @Test
    public void recentIngestCacheCatchesDuplicatesWithoutStoreHistory()
    {
        // a latest-only backing store (the hub's state db) never reports history —
        // the recent-ingest cache must still catch an immediate re-delivery
        var obsStore = mock(IObsStore.class);
        when(obsStore.select(any(ObsFilter.class))).thenAnswer(inv -> Stream.empty());
        var db = mock(IObsSystemDatabase.class);
        when(db.getObservationStore()).thenReturn(obsStore);

        var fp = new ObsFingerprint(db, LoggerFactory.getLogger(TestObsFingerprint.class));
        var dsId = BigId.fromLong(1, 42);

        assertFalse("unknown before ingest", fp.exists(dsId, 1700000000123L));
        fp.markIngested(dsId, 1700000000123L);
        assertTrue("cached after ingest", fp.exists(dsId, 1700000000123L));
        assertFalse("different ms is a different event", fp.exists(dsId, 1700000000124L));
        assertFalse("different datastream is a different event",
            fp.exists(BigId.fromLong(1, 43), 1700000000123L));
    }
}
