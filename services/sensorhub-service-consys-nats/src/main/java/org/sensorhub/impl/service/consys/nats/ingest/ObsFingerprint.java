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

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.slf4j.Logger;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.Time;


/**
 * <p>
 * Observation identity for idempotent ingest: an observation is identified
 * fleet-wide by {@code (datastream, phenomenonTime @ epoch-ms)}. Before POSTing
 * an ingested observation, callers check {@link #exists} — a hit means the
 * event is already stored locally and the POST is skipped, making double
 * delivery (re-POST, JetStream replay, reconnect overlap, relay loops) a no-op.
 * </p>
 *
 * <p>
 * Time extraction is format-scoped: {@code json}/{@code om-json} payloads carry
 * a top-level {@code phenomenonTime} property; {@code swe-json} records carry
 * it in the field named by the schema's first {@link Time} component. Binary
 * formats return null and the check is skipped for them. Every failure mode
 * fails OPEN: a null/unparseable time means the POST proceeds — dedupe must
 * never lose data.
 * </p>
 *
 * @author CR31
 * @since August 3, 2026
 */
public class ObsFingerprint
{
    /** Bound of the recently-ingested fingerprint cache. */
    static final int RECENT_CACHE_SIZE = 10_000;

    final IObsSystemDatabase db;
    final Logger log;

    /** Recently-ingested fingerprints (LRU), JVM-shared. Two consumers:
     *  ingest-side dedupe (the store query is blind when the backing store
     *  keeps no history — the hub's default state database retains only the
     *  latest observation per output — so the cache covers the
     *  replay/reconnect window regardless), and the publisher's egress check
     *  (an observation held here was received from the broker and must not be
     *  republished). Static because the marker (connector or mirror client)
     *  and the checker (publisher) are separate modules sharing only the JVM. */
    static final Set<String> recentIngests = Collections.newSetFromMap(Collections.synchronizedMap(
        new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest)
            {
                return size() > RECENT_CACHE_SIZE;
            }
        }));


    public ObsFingerprint(IObsSystemDatabase db, Logger log)
    {
        this.db = db;
        this.log = log;
    }


    /**
     * True iff an observation already exists in the given datastream with
     * phenomenonTime in {@code [tMs, tMs+1ms)}. Checks the recent-ingest cache
     * first, then the store.
     */
    public boolean exists(BigId dsInternalId, long phenTimeMs)
    {
        if (recentIngests.contains(key(dsInternalId, phenTimeMs)))
            return true;

        try
        {
            return db.getObservationStore().select(new ObsFilter.Builder()
                    .withDataStreams(dsInternalId)
                    .withPhenomenonTimeDuring(
                        Instant.ofEpochMilli(phenTimeMs),
                        Instant.ofEpochMilli(phenTimeMs + 1))
                    .withLimit(1)
                    .build())
                .findAny().isPresent();
        }
        catch (Exception e)
        {
            // a store that cannot answer the point query (state-db mirrors) must
            // not break the check — the recent-ingest cache above still dedupes
            log.debug("Fingerprint store query failed for datastream {}: {}", dsInternalId, e.getMessage());
            return false;
        }
    }


    /**
     * Record an ingested observation's fingerprint so an immediate re-delivery
     * is caught even when the backing store keeps no history, and so the
     * publisher suppresses its republication. Call BEFORE the POST — osh-core
     * publishes the obs event to the bus before the store insert returns, so a
     * mark placed after the POST can lose the race with the publisher's egress
     * check. Use {@link #unmarkIngested} if the POST fails.
     */
    public void markIngested(BigId dsInternalId, long phenTimeMs)
    {
        recentIngests.add(key(dsInternalId, phenTimeMs));
    }


    /** Roll back a {@link #markIngested} after a failed POST. */
    public void unmarkIngested(BigId dsInternalId, long phenTimeMs)
    {
        recentIngests.remove(key(dsInternalId, phenTimeMs));
    }


    /** True iff this observation was recently ingested off the broker and
     *  must not be republished. Static — usable without a db. */
    public static boolean wasRecentlyIngested(BigId dsInternalId, long phenTimeMs)
    {
        return recentIngests.contains(key(dsInternalId, phenTimeMs));
    }


    /** Test hook: clear the JVM-shared recent-ingest memory. */
    public static void clearRecentIngestsForTests()
    {
        recentIngests.clear();
    }


    private static String key(BigId dsInternalId, long phenTimeMs)
    {
        return dsInternalId + "@" + phenTimeMs;
    }


    /**
     * Extract the observation's phenomenonTime as epoch-ms from a wire payload,
     * or null if not extractable (unknown/binary format, missing field, parse
     * failure — callers treat null as "skip the check, proceed with the POST").
     *
     * @param payload raw message body
     * @param formatToken subject format token ({@code json}, {@code om-json},
     *        {@code swe-json}, …); null/unknown ⇒ null
     * @param timeFieldName record field holding the time, for {@code swe-json}
     *        (from {@link #findTimeFieldName}); ignored for json/om-json
     */
    public static Long extractPhenTimeMs(byte[] payload, String formatToken, String timeFieldName)
    {
        if (payload == null || formatToken == null)
            return null;

        try
        {
            String fieldName;
            if ("json".equals(formatToken) || "om-json".equals(formatToken))
                fieldName = "phenomenonTime";
            else if ("swe-json".equals(formatToken))
                fieldName = timeFieldName;
            else
                return null; // binary/unknown formats: no cheap time access

            if (fieldName == null)
                return null;

            var root = JsonParser.parseReader(new InputStreamReader(
                new ByteArrayInputStream(payload), StandardCharsets.UTF_8));
            if (!root.isJsonObject())
                return null;
            return parseTimeMs(((JsonObject)root).get(fieldName));
        }
        catch (Exception e)
        {
            return null; // fail open
        }
    }


    /** ISO-8601 string or numeric epoch-seconds (double) → epoch-ms, else null. */
    private static Long parseTimeMs(JsonElement el)
    {
        if (el == null || !el.isJsonPrimitive())
            return null;
        var prim = el.getAsJsonPrimitive();
        if (prim.isNumber())
            return (long)Math.floor(prim.getAsDouble() * 1000.0);
        if (prim.isString())
            return OffsetDateTime.parse(prim.getAsString()).toInstant().toEpochMilli();
        return null;
    }


    /**
     * Name of the first {@link Time} component among the top-level fields of a
     * record structure (the swe-json time field), or null if none.
     */
    public static String findTimeFieldName(DataComponent recordStruct)
    {
        if (recordStruct instanceof DataRecord rec)
        {
            for (int i = 0; i < rec.getComponentCount(); i++)
            {
                var field = rec.getComponent(i);
                if (field instanceof Time)
                    return field.getName();
            }
        }
        return null;
    }
}
