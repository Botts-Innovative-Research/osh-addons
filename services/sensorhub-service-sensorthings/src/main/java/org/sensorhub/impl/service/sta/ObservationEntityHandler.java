/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sta;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import org.joda.time.DateTimeZone;
import org.sensorhub.api.datastore.DataStreamInfo;
import org.sensorhub.api.datastore.FeatureId;
import org.sensorhub.api.datastore.IObsStore;
import org.sensorhub.api.datastore.ObsData;
import org.sensorhub.api.datastore.ObsFilter;
import org.sensorhub.api.datastore.ObsKey;
import org.sensorhub.impl.sensor.VirtualSensorProxy;
import org.vast.data.DataBlockDouble;
import org.vast.data.DataBlockInt;
import org.vast.data.DataBlockLong;
import org.vast.data.DataBlockString;
import org.vast.util.Asserts;
import com.github.fge.jsonpatch.JsonPatch;
import de.fraunhofer.iosb.ilt.frostserver.model.Datastream;
import de.fraunhofer.iosb.ilt.frostserver.model.FeatureOfInterest;
import de.fraunhofer.iosb.ilt.frostserver.model.MultiDatastream;
import de.fraunhofer.iosb.ilt.frostserver.model.Observation;
import de.fraunhofer.iosb.ilt.frostserver.model.core.AbstractDatastream;
import de.fraunhofer.iosb.ilt.frostserver.model.core.Entity;
import de.fraunhofer.iosb.ilt.frostserver.model.core.EntitySet;
import de.fraunhofer.iosb.ilt.frostserver.model.core.EntitySetImpl;
import de.fraunhofer.iosb.ilt.frostserver.model.ext.TimeInstant;
import de.fraunhofer.iosb.ilt.frostserver.path.EntityPathElement;
import de.fraunhofer.iosb.ilt.frostserver.path.EntityType;
import de.fraunhofer.iosb.ilt.frostserver.path.ResourcePath;
import de.fraunhofer.iosb.ilt.frostserver.query.Query;
import de.fraunhofer.iosb.ilt.frostserver.util.NoSuchEntityException;
import net.opengis.swe.v20.DataBlock;


/**
 * <p>
 * Handler for Sensor resources
 * </p>
 *
 * @author Alex Robin
 * @date Sep 7, 2019
 */
@SuppressWarnings("rawtypes")
public class ObservationEntityHandler implements IResourceHandler<Observation>
{
    static final String NOT_FOUND_MESSAGE = "Cannot find 'Observation' entity with ID #";
    
    OSHPersistenceManager pm;
    STASecurity securityHandler;
    IObsStore obsReadStore;
    IObsStore obsWriteStore;
    int maxPageSize = 100;
    
    
    ObservationEntityHandler(OSHPersistenceManager pm)
    {
        this.pm = pm;
        this.obsReadStore = pm.obsDbRegistry.getFederatedObsDatabase().getObservationStore();
        this.obsWriteStore = pm.database != null ? pm.database.getObservationStore() : null;
        this.securityHandler = pm.service.getSecurityHandler();
    }
    
    
    @Override
    public ResourceId create(Entity entity) throws NoSuchEntityException
    {
        securityHandler.checkPermission(securityHandler.sta_insert_obs);
        Asserts.checkArgument(entity instanceof Observation);
        Observation obs = (Observation)entity;
        
        // check data stream is present
        AbstractDatastream<?> dataStream = obs.getDatastream();
        if (dataStream == null)
            dataStream = obs.getMultiDatastream();
        if (dataStream == null)
            throw new IllegalArgumentException("A new Observation SHALL link to a Datastream or MultiDatastream entity");
        
        Asserts.checkArgument(obs.getPhenomenonTime() != null, "Missing phenomenonTime");
        
        // prepare obs key fields and obs data
        ResourceId dsId = (ResourceId)dataStream.getId();
        Instant phenomenonTime = Instant.parse(obs.getPhenomenonTime().asISO8601()).truncatedTo(ChronoUnit.MILLIS);
        FeatureId foi = ObsKey.NO_FOI;
        if (obs.getFeatureOfInterest() != null)
        {
            ResourceId foiId = (ResourceId)obs.getFeatureOfInterest().getId();
            foi = new FeatureId(pm.toLocalID(foiId.internalID));
        }
        
        // generate OSH obs
        DataStreamInfo dsInfo = obsReadStore.getDataStreams().get(dsId.internalID);
        if (dsInfo == null)
            throw new NoSuchEntityException(DatastreamEntityHandler.NOT_FOUND_MESSAGE + dsId);
        ObsData obsData = toObsData(obs);
        
        // push obs to proxy
        pm.sensorHandler.checkProcedureWritable(dsInfo.getProcedure().getInternalID());
        VirtualSensorProxy proxy = pm.sensorHandler.getProcedureProxy(dsInfo.getProcedure().getUniqueID());
        //proxy.publishNewRecord(dsInfo.getOutputName(), obsData.getResult());
        
        // add observation to data store
        if (obsWriteStore != null)
        {
            ObsKey key = new ObsKey(pm.toLocalID(dsId.internalID), foi, phenomenonTime);
            obsWriteStore.put(key, obsData);
        }
        
        // generate datastream ID
        return new CompositeResourceId(dsId.internalID, foi.getInternalID(), phenomenonTime.toEpochMilli());
    }
    

    @Override
    public boolean update(Entity entity) throws NoSuchEntityException
    {
        securityHandler.checkPermission(securityHandler.sta_update_obs);
        throw new UnsupportedOperationException("Patch not supported");
    }
    
    
    public boolean patch(ResourceId id, JsonPatch patch) throws NoSuchEntityException
    {
        securityHandler.checkPermission(securityHandler.sta_update_obs);
        throw new UnsupportedOperationException("Patch not supported");
    }
    
    
    public boolean delete(ResourceId id) throws NoSuchEntityException
    {
        securityHandler.checkPermission(securityHandler.sta_delete_obs);        
        CompositeResourceId obsId = checkResourceId(id);
        
        if (obsWriteStore != null)
        {
            ObsData obs = obsWriteStore.remove(toLocalKey(obsId));
            if (obs == null)
                throw new NoSuchEntityException(NOT_FOUND_MESSAGE + id);
            
            return true;
        }
        
        return false;
    }    
    

    @Override
    public Observation getById(ResourceId id, Query q) throws NoSuchEntityException
    {
        securityHandler.checkPermission(securityHandler.sta_read_obs);        
        CompositeResourceId obsId = checkResourceId(id);
        
        ObsKey key = toPublicKey(obsId);
        ObsData obs = obsReadStore.get(key);
        if (obs == null)
            throw new NoSuchEntityException(NOT_FOUND_MESSAGE + id);
        
        return toFrostObservation(key, obs, q);
    }
    

    @Override
    public EntitySet<?> queryCollection(ResourcePath path, Query q)
    {
        securityHandler.checkPermission(securityHandler.sta_read_obs);
        
        // create obs filter
        ObsFilter filter = getFilter(path, q);      
        int skip = q.getSkip(0);
        int limit = Math.min(q.getTopOrDefault(), maxPageSize);
        
        // collect result to entity set
        var entitySet = obsReadStore.selectEntries(filter)
            .skip(skip)
            .limit(limit+1) // request limit+1 elements to handle paging
            .map(e -> toFrostObservation(e.getKey(), e.getValue(), q))
            .collect(Collectors.toCollection(EntitySetImpl::new));
        
        return FrostUtils.handlePaging(entitySet, path, q, limit);
    }
    
    
    protected ObsFilter getFilter(ResourcePath path, Query q)
    {
        ObsFilter.Builder builder = new ObsFilter.Builder();
        
        EntityPathElement idElt = path.getIdentifiedElement();
        if (idElt != null)
        {
            if (idElt.getEntityType() == EntityType.DATASTREAM ||
                idElt.getEntityType() == EntityType.MULTIDATASTREAM)
            {
                ResourceId dsId = (ResourceId)idElt.getId();
                builder.withDataStreams(dsId.internalID);
            }
            if (idElt.getEntityType() == EntityType.FEATUREOFINTEREST)
            {
                ResourceId foiId = (ResourceId)idElt.getId();
                builder.withFois(foiId.internalID);
            }
        }
        
        /*SensorFilterVisitor visitor = new SensorFilterVisitor(builder);
        if (q.getFilter() != null)
            q.getFilter().accept(visitor);*/
        
        return builder.build();
    }
    
    
    protected CompositeResourceId checkResourceId(ResourceId id) throws NoSuchEntityException
    {
        if (!(id instanceof CompositeResourceId) ||
            ((CompositeResourceId)id).parentIDs.length != 2 ||
            ((CompositeResourceId)id).parentIDs[0] <= 0)
            throw new NoSuchEntityException(NOT_FOUND_MESSAGE + id);
        
        return (CompositeResourceId)id;
    }
    
    
    /*
     * Create a local DB obs key from the entity ID
     */
    protected ObsKey toLocalKey(CompositeResourceId obsId)
    {
        long dataStreamID = obsId.parentIDs[0];
        long foiID = obsId.parentIDs[1];
        
        return new ObsKey(
            pm.toLocalID(dataStreamID),
            new FeatureId(pm.toLocalID(foiID)),
            Instant.ofEpochMilli(obsId.internalID));
    }
    
    
    /*
     * Create a public obs key from the entity ID
     */
    protected ObsKey toPublicKey(CompositeResourceId obsId)
    {
        long dataStreamID = obsId.parentIDs[0];
        long foiID = obsId.parentIDs[1];
        
        return new ObsKey(
            dataStreamID,
            new FeatureId(foiID),
            Instant.ofEpochMilli(obsId.internalID));
    }
    
    
    protected ObsData toObsData(Observation obs)
    {
        Object result = obs.getResult();
        DataBlock dataBlk;
        
        if (result instanceof Integer)
        {
            dataBlk = new DataBlockInt(1);
            dataBlk.setIntValue((Integer)result);        
        }
        else if (result instanceof Long)
        {
            dataBlk = new DataBlockLong(1);
            dataBlk.setLongValue((Long)result);        
        }
        else if (result instanceof Number)
        {
            dataBlk = new DataBlockDouble(1);
            dataBlk.setDoubleValue(((Number)result).doubleValue());        
        }
        else if (result instanceof String)
        {
            dataBlk = new DataBlockString();
            dataBlk.setStringValue((String)result);
        }
        else
            throw new IllegalArgumentException("Unsupported result type: " + result.getClass().getSimpleName());
        
        return new ObsData(dataBlk);
    }
    
    
    protected void addToDataBlock(Object obj)
    {
        
    }
    
    
    protected Observation toFrostObservation(ObsKey key, ObsData obsData, Query q)
    {
        Observation obs = new Observation();
        
        // composite ID
        obs.setId(new CompositeResourceId(
            key.getDataStreamID(),
            key.getFoiID().getInternalID(),
            key.getPhenomenonTime().toEpochMilli()));
        
        // phenomenon time
        obs.setPhenomenonTime(TimeInstant.create(key.getPhenomenonTime().toEpochMilli(), DateTimeZone.UTC));
        
        // result time
        if (key.getResultTime() == null)
            obs.setResultTime((TimeInstant)obs.getPhenomenonTime());
        else
            obs.setResultTime(TimeInstant.create(key.getResultTime().toEpochMilli(), DateTimeZone.UTC));
        
        // FOI
        if (key.getFoiID().getInternalID() != 0)
        {
            FeatureOfInterest foi = new FeatureOfInterest(new ResourceId(key.getFoiID().getInternalID()));
            foi.setExportObject(false);
            obs.setFeatureOfInterest(foi);
        }
        
        // result
        boolean isExternalDatastream = pm.obsDbRegistry.getDatabaseID(key.getDataStreamID()) != pm.database.getDatabaseID();
        DataBlock data = obsData.getResult();
        if ((isExternalDatastream && data.getAtomCount() == 2) || data.getAtomCount() == 1)
        {
            int resultValueIdx = isExternalDatastream ? 1 : 0;
            Datastream ds = new Datastream(new ResourceId(key.getDataStreamID()));
            ds.setExportObject(false);
            obs.setDatastream(ds);            
            obs.setResult(getResultValue(data, resultValueIdx));
        }
        else
        {
            MultiDatastream ds = new MultiDatastream(new ResourceId(key.getDataStreamID()));
            ds.setExportObject(false);
            obs.setMultiDatastream(ds);
            Object[] result = new Object[data.getAtomCount()-1];
            for (int i = 1; i < data.getAtomCount(); i++)
                result[i-1] = getResultValue(data, i);
            obs.setResult(result);           
        }        
        
        return obs;
    }
    
    
    protected Object getResultValue(DataBlock data, int index)
    {
        switch (data.getDataType(index))
        {
            case DOUBLE:
            case FLOAT:
                return data.getDoubleValue(index);
                
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
            case UBYTE:
            case USHORT:
            case UINT:
            case ULONG:
                return data.getLongValue(index);
                
            case ASCII_STRING:
            case UTF_STRING:
                return data.getStringValue(index);
                
            default:
                return null;
        }
    }
    
    
    protected void handleObservationAssocList(ResourceId dataStreamId, AbstractDatastream<?> dataStream) throws NoSuchEntityException
    {
        if (dataStream.getObservations() == null)
            return;
        
        boolean isMultiDatastream = dataStream instanceof MultiDatastream;
        
        for (Observation obs: dataStream.getObservations())
        {        
            if (obs.getResult() != null)
            {
                // also set/override mandatory datastream ID
                if (isMultiDatastream)
                    obs.setMultiDatastream(new MultiDatastream(dataStreamId));
                else
                    obs.setDatastream(new Datastream(dataStreamId));
                
                create(obs);
            }
        }
    }
    
    
    /*protected boolean isObservationVisible(ObsKey publicKey)
    {
        // TODO also check that current user has the right to read this entity!
        
        return pm.obsDbRegistry.getDatabaseID(publicKey.getDataStreamID()) == pm.database.getDatabaseID() ||
            pm.service.isProcedureExposed(fid);
    }*/

}