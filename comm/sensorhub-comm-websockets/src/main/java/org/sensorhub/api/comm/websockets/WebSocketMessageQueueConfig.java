/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2016 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.comm.websockets;

import org.sensorhub.api.comm.CommProviderConfig;
import org.sensorhub.api.comm.ICommNetwork;
import org.sensorhub.api.comm.MessageQueueConfig;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.impl.comm.RobustIPConnectionConfig;


public class WebSocketMessageQueueConfig extends MessageQueueConfig
{

    @DisplayInfo(label="Connection Options")
    public RobustIPConnectionConfig connection = new RobustIPConnectionConfig();


    @DisplayInfo(desc="IP or DNS name of remote host")
    @DisplayInfo.FieldType(DisplayInfo.FieldType.Type.REMOTE_ADDRESS)
    @DisplayInfo.AddressType(ICommNetwork.NetworkType.IP)
    @DisplayInfo.Required
    public String remoteHost;

    @DisplayInfo(desc="Port number to connect to on remote host")
    @DisplayInfo.ValueRange(min=0, max=65535)
    @DisplayInfo.Required
    public int remotePort;

    @DisplayInfo(label="User Name", desc="Remote user name")
    public String user;

    @DisplayInfo(label="Password", desc="Remote password")
    @DisplayInfo.FieldType(DisplayInfo.FieldType.Type.PASSWORD)
    public String password;

    @DisplayInfo(label="Resource Path", desc="Path")
    public String resourcePath;


    @DisplayInfo(desc="Secure communications with SSL/TLS")
    public boolean enableTLS;

    @DisplayInfo(label="Custom Event Name", desc="Custom event name")
    public String customEventName = "message";

}
