package org.sensorhub.impl.comm.reticulum;

public class ReticulumNetworkLxmfCommand
{
    public String validate(String destinationHash, String message)
    {
        return validate(destinationHash, message, ReticulumNetworkConfig.LxmfDeliveryMethod.DIRECT, "");
    }

    public String validate(String destinationHash, String message, ReticulumNetworkConfig.LxmfDeliveryMethod deliveryMethod, String propagationNode)
    {
        if (destinationHash == null || destinationHash.trim().isEmpty())
            return "Destination hash is required for LXMF outbound message";
        if (message == null || message.trim().isEmpty())
            return "LXMF message body is required";
        if (deliveryMethod == ReticulumNetworkConfig.LxmfDeliveryMethod.PROPAGATED
            && (propagationNode == null || propagationNode.trim().isEmpty()))
            return "LXMF propagation node is required for propagated delivery";
        return "LXMF Destination hash " + destinationHash.trim() + " method " + deliveryMethod;
    }
}
