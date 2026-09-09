package org.sensorhub.impl.comm.reticulum;

public class ReticulumNetworkLxmfCommand
{
    public String validate(String destinationHash, String message)
    {
        if (destinationHash == null || destinationHash.trim().isEmpty())
            return "Destination hash is required for LXMF outbound message";
        if (message == null || message.trim().isEmpty())
            return "LXMF message body is required";
        return "LXMF Destination hash " + destinationHash.trim();
    }
}
