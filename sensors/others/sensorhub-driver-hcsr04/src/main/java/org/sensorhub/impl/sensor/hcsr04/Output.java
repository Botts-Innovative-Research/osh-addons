/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
Developer are Copyright (C) 2014 the Initial Developer. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor.hcsr04;

import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.data.DataEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.TimeZone;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.Quantity;
import net.opengis.gml.v32.AbstractFeature;

import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;


public class Output extends AbstractSensorOutput<Sensor> {
    DataComponent data;
    DataEncoding dataEncoding;

    public Output(Sensor parentSensor)
    {
        super("data", parentSensor);
    }

    public void init() {}

    @Override
    public double getAverageSamplingPeriod() {
        return 10.0; // 10 sec
    }


    @Override
    public DataComponent getRecordDescription() {
        return data;
    }


    @Override
    public DataEncoding getRecommendedEncoding() {
        return dataEncoding;
    }
}
