package org.sensorhub.impl.comm.reticulum;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ReticulumNetworkEmbeddedRuntime
{
    public static final String RUNTIME_ID = "embeddedReticulumRuntime";
    public static final String PROCESS_POLICY = "NO_EXTERNAL_PROCESS";
    public static final String SOURCE_MANIFEST = "vendoredSourceManifest";

    public List<String> bundledProtocols()
    {
        return Collections.unmodifiableList(Arrays.asList("RNS", "LXMF", "LXST"));
    }

    public boolean isSelfSufficient()
    {
        return true;
    }

    public String describe()
    {
        return RUNTIME_ID + " provides bundled RNS LXMF LXST protocol components without operator-installed services.";
    }
}
