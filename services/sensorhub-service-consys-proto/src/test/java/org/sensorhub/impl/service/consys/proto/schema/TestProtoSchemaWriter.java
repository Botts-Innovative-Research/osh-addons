/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 GeoRobotix Innovative Research. All Rights Reserved.

Author: Ian Patterson <ian.patterson@georobotix.us>

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.proto.schema;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;
import org.vast.swe.SWEHelper;
import com.georobotix.swecommon.SweOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Timestamp;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataType;


/**
 * Tests {@link ProtoSchemaWriter} end-to-end: a nested SWE {@link DataComponent}
 * is translated to a {@code FileDescriptorProto}, resolved through a
 * {@link DataStreamSchemaCache} bootstrapped exactly like
 * {@code ConSysApiProtoService.doStart()}, and then exercised both for SWE
 * annotation read-back and for {@link DynamicMessage} I/O.
 */
public class TestProtoSchemaWriter
{
    static final String PKG = "test.weather";
    static final String MSG = "WeatherObs";
    static final String FQN = PKG + "." + MSG;
    static final String FRAME_WGS84_3D = "http://www.opengis.net/def/crs/EPSG/0/4979";
    static final String TEMP_DEF = "http://mmisw.org/ont/cf/parameter/air_temperature";

    ProtoSchemaWriter writer;
    DataStreamSchemaCache cache;


    @Before
    public void setup()
    {
        writer = new ProtoSchemaWriter();

        // configure the cache the way the service does
        cache = new DataStreamSchemaCache();
        var extReg = ExtensionRegistry.newInstance();
        SweOptions.registerAllExtensions(extReg);
        cache.setExtensionRegistry(extReg);
        cache.registerBootstrapTree(Timestamp.getDescriptor().getFile());
        cache.registerBootstrapTree(SweOptions.getDescriptor());
    }


    /** Build a record with: ISO time, an annotated scalar, a nested record,
     *  and a Vector with a referenceFrame — covers the nesting paths. */
    static DataComponent buildRecord()
    {
        var swe = new SWEHelper();
        return swe.createRecord()
            .addSamplingTimeIsoUTC("time")
            .addField("airTemp", swe.createQuantity()
                .definition(TEMP_DEF)
                .label("Air Temperature")
                .uomCode("Cel")
                .dataType(DataType.FLOAT))
            .addField("count", swe.createCount()
                .definition("http://x/count"))
            .addField("status", swe.createRecord()
                .addField("ok", swe.createBoolean().definition("http://x/ok"))
                .addField("code", swe.createCount().definition("http://x/code")))
            .addField("location", swe.createVector()
                .definition("http://x/location")
                .refFrame(FRAME_WGS84_3D)
                .addCoordinate("lat", swe.createQuantity()
                    .definition("http://x/lat").uomCode("deg").dataType(DataType.DOUBLE).build())
                .addCoordinate("lon", swe.createQuantity()
                    .definition("http://x/lon").uomCode("deg").dataType(DataType.DOUBLE).build())
                .addCoordinate("alt", swe.createQuantity()
                    .definition("http://x/alt").uomCode("m").dataType(DataType.DOUBLE).build()))
            .build();
    }


    @Test
    public void testResolvesThroughCache() throws Exception
    {
        var result = writer.write(buildRecord(), "datastreams/ds1/obs.proto", PKG, MSG);
        assertEquals(FQN, result.messageTypeName);

        // both imports present, names matching the bootstrap contract
        var deps = result.fileDescriptor.getDependencyList();
        assertTrue("swe_options import", deps.contains(ProtoSchemaWriter.SWE_OPTIONS_PROTO));
        assertTrue("timestamp import", deps.contains(ProtoSchemaWriter.TIMESTAMP_PROTO));

        var desc = cache.register("ds1", result.toByteArray(), result.messageTypeName);
        assertEquals(FQN, desc.getFullName());
    }


    @Test
    public void testFieldTypesAndAnnotations() throws Exception
    {
        var result = writer.write(buildRecord(), "datastreams/ds1/obs.proto", PKG, MSG);
        var desc = cache.register("ds1", result.toByteArray(), result.messageTypeName);

        // ISO time -> Timestamp message field
        var time = desc.findFieldByName("time");
        assertEquals(FieldDescriptor.Type.MESSAGE, time.getType());
        assertEquals("google.protobuf.Timestamp", time.getMessageType().getFullName());

        // annotated scalar: float + uomCode read back
        var temp = desc.findFieldByName("airTemp");
        assertEquals(FieldDescriptor.Type.FLOAT, temp.getType());
        assertEquals("Cel", temp.getOptions().getExtension(SweOptions.uomCode));
        assertEquals(TEMP_DEF, temp.getOptions().getExtension(SweOptions.definition));

        var count = desc.findFieldByName("count");
        assertEquals(FieldDescriptor.Type.INT32, count.getType());

        // nested record resolves and carries its own fields
        var status = desc.findFieldByName("status");
        assertEquals(FieldDescriptor.Type.MESSAGE, status.getType());
        var statusMsg = status.getMessageType();
        assertEquals(FieldDescriptor.Type.BOOL, statusMsg.findFieldByName("ok").getType());
        assertEquals(FieldDescriptor.Type.INT32, statusMsg.findFieldByName("code").getType());

        // vector -> nested message, referenceFrame rides on the field
        var loc = desc.findFieldByName("location");
        assertEquals(FieldDescriptor.Type.MESSAGE, loc.getType());
        assertEquals(FRAME_WGS84_3D, loc.getOptions().getExtension(SweOptions.referenceFrame));
        var locMsg = loc.getMessageType();
        assertEquals(FieldDescriptor.Type.DOUBLE, locMsg.findFieldByName("lat").getType());
        assertEquals("deg", locMsg.findFieldByName("lon").getOptions().getExtension(SweOptions.uomCode));
    }


    /**
     * Prove the generated descriptor is usable for actual message I/O, not just
     * structurally valid: build a DynamicMessage against it, set scalar + nested
     * fields, serialize, parse back, and read the values.
     */
    @Test
    public void testDynamicMessageRoundTrips() throws Exception
    {
        var result = writer.write(buildRecord(), "datastreams/ds1/obs.proto", PKG, MSG);
        var desc = cache.register("ds1", result.toByteArray(), result.messageTypeName);

        var tempField = desc.findFieldByName("airTemp");
        var countField = desc.findFieldByName("count");
        var locField = desc.findFieldByName("location");
        var latField = locField.getMessageType().findFieldByName("lat");

        var location = DynamicMessage.newBuilder(locField.getMessageType())
            .setField(latField, 34.7d)
            .build();

        var obs = DynamicMessage.newBuilder(desc)
            .setField(tempField, 21.5f)
            .setField(countField, 7)
            .setField(locField, location)
            .build();

        var bytes = obs.toByteArray();
        var parsed = DynamicMessage.parseFrom(desc, bytes);

        assertEquals(21.5f, (float) parsed.getField(tempField), 1e-6);
        assertEquals(7, parsed.getField(countField));
        var parsedLoc = (DynamicMessage) parsed.getField(locField);
        assertEquals(34.7d, (double) parsedLoc.getField(latField), 1e-9);
    }


    /** A record whose vector field is already PascalCase, exactly like the
     *  mavsdk driver outputs (field "Acceleration" → derived nested type name
     *  "Acceleration"). Fields and nested types share one protobuf symbol
     *  namespace per message, so the TYPE must be renamed and the field name
     *  preserved. */
    static DataComponent buildPascalCaseRecord(String name)
    {
        var swe = new SWEHelper();
        return swe.createRecord()
            .name(name)
            .addSamplingTimeIsoUTC("sampleTime")
            .addField(name, swe.createVector()
                .definition("http://x/" + name)
                .addCoordinate("x", swe.createQuantity().uomCode("m").dataType(DataType.DOUBLE).build())
                .addCoordinate("y", swe.createQuantity().uomCode("m").dataType(DataType.DOUBLE).build())
                .addCoordinate("z", swe.createQuantity().uomCode("m").dataType(DataType.DOUBLE).build()))
            .build();
    }


    @Test
    public void testPascalCaseFieldDoesNotCollideWithNestedType() throws Exception
    {
        var result = writer.write(buildPascalCaseRecord("Acceleration"), "datastreams/ds1/obs.proto", PKG, MSG);
        var desc = cache.register("ds1", result.toByteArray(), result.messageTypeName);

        // field keeps the component's name; only the type is renamed
        var field = desc.findFieldByName("Acceleration");
        assertNotNull("field keeps its wire name", field);
        assertEquals("Acceleration2", field.getMessageType().getName());
        assertNotNull(field.getMessageType().findFieldByName("x"));
    }


    /** All five mavsdk telemetry stream names that hit the collision live. */
    @Test
    public void testMavsdkTelemetryShapesResolve() throws Exception
    {
        for (var name : new String[] {"Acceleration", "AngularVelocity", "Attitude", "MagneticField", "Velocity"})
        {
            var result = writer.write(buildPascalCaseRecord(name), "datastreams/" + name + "/obs.proto", PKG, MSG);
            var desc = cache.register(name, result.toByteArray(), result.messageTypeName);
            assertNotNull(name + " field", desc.findFieldByName(name));
        }
    }


    /** Same collision one scope down (buildMessage path): a nested record whose
     *  own child repeats the record's PascalCase name. */
    @Test
    public void testNestedScopeCollisionResolves() throws Exception
    {
        var swe = new SWEHelper();
        var rec = swe.createRecord()
            .addSamplingTimeIsoUTC("time")
            .addField("Attitude", swe.createRecord()
                .addField("Attitude", swe.createVector()
                    .addCoordinate("roll", swe.createQuantity().uomCode("deg").dataType(DataType.DOUBLE).build())
                    .addCoordinate("pitch", swe.createQuantity().uomCode("deg").dataType(DataType.DOUBLE).build())))
            .build();

        var result = writer.write(rec, "datastreams/ds1/obs.proto", PKG, MSG);
        var desc = cache.register("ds1", result.toByteArray(), result.messageTypeName);

        var outer = desc.findFieldByName("Attitude");
        var inner = outer.getMessageType().findFieldByName("Attitude");
        assertNotNull(inner);
        assertNotNull(inner.getMessageType().findFieldByName("roll"));
    }


    /** Pathological ordering: a lowercase vector claims type name "Acceleration",
     *  then a later sibling FIELD wants the same name. The type name is reserved,
     *  so the later field gets the deterministic numeric suffix. */
    @Test
    public void testLaterSiblingFieldAvoidsReservedTypeName() throws Exception
    {
        var swe = new SWEHelper();
        var rec = swe.createRecord()
            .addField("acceleration", swe.createVector()
                .addCoordinate("x", swe.createQuantity().uomCode("m").dataType(DataType.DOUBLE).build()))
            .addField("Acceleration", swe.createQuantity().uomCode("m").dataType(DataType.DOUBLE))
            .build();

        var result = writer.write(rec, "datastreams/ds1/obs.proto", PKG, MSG);
        var desc = cache.register("ds1", result.toByteArray(), result.messageTypeName);

        assertNotNull(desc.findFieldByName("acceleration"));               // vector field untouched
        assertEquals("Acceleration", desc.findFieldByName("acceleration").getMessageType().getName());
        assertNotNull("later scalar renamed", desc.findFieldByName("Acceleration2"));
    }


    /** The renamed type is not load-bearing for I/O: round-trip a message
     *  through the collision-fixed schema. */
    @Test
    public void testCollisionSchemaRoundTrips() throws Exception
    {
        var result = writer.write(buildPascalCaseRecord("Velocity"), "datastreams/ds1/obs.proto", PKG, MSG);
        var desc = cache.register("ds1", result.toByteArray(), result.messageTypeName);

        var velField = desc.findFieldByName("Velocity");
        var xField = velField.getMessageType().findFieldByName("x");
        var vel = DynamicMessage.newBuilder(velField.getMessageType()).setField(xField, 1.5d).build();
        var obs = DynamicMessage.newBuilder(desc).setField(velField, vel).build();

        var parsed = DynamicMessage.parseFrom(desc, obs.toByteArray());
        assertEquals(1.5d, (double) ((DynamicMessage) parsed.getField(velField)).getField(xField), 1e-9);
    }
}
