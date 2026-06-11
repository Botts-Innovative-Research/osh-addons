package org.sensorhub.impl.service.federation.environment;

import java.util.ArrayList;
import java.util.List;

public class EnvironmentData {

    private List<NodeEnvData> nodes;

    public EnvironmentData() {
        this.nodes = new ArrayList<>();
    }

    public void deleteAuthInMem() {
        for (NodeEnvData node : nodes) {
            node.setAuth(null);
        }
    }

    public List<NodeEnvData> getCommanderNodes() {
        List<NodeEnvData> commanders = new ArrayList<>();
        for (NodeEnvData node : nodes) {
            if (node.isCommander()) {
                commanders.add(node);
            }
        }
        return commanders;
    }

    public List<NodeEnvData> getNodes() {
        return nodes;
    }

    public void setNodes(List<NodeEnvData> nodes) {
        this.nodes = nodes;
    }
}