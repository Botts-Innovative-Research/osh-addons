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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.vast.sensorML.SMLHelper;
import org.vast.swe.SWEHelper;


/**
 * <p>
 * xxx
 * </p>
 *
 * @author Philip Khaisman
 * @since Nov 2024
 */
public class Sensor extends AbstractSensorModule<Config> {

    public Sensor() {}

    @Override
    protected void doInit() throws SensorHubException {}

    @Override
    protected void updateSensorDescription() {}

    @Override
    protected void doStart() throws SensorHubException {}

    @Override
    protected void doStop() throws SensorHubException {}

    @Override
    public void cleanup() throws SensorHubException {}

    @Override
    public boolean isConnected() {}
}