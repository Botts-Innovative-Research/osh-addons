package org.sensorhub.impl.comm.reticulum;

import org.sensorhub.api.module.IModuleBase;
import org.sensorhub.api.module.IModuleProvider;
import org.sensorhub.api.module.ModuleConfigBase;
import org.sensorhub.impl.module.JarModuleProvider;

public class ReticulumNetworkDescriptor extends JarModuleProvider implements IModuleProvider
{
    @Override
    public String getModuleName()
    {
        return "Reticulum Network";
    }

    @Override
    public String getModuleDescription()
    {
        return "Reticulum RNS/LXMF/LXST communication provider with Connected Systems API mapping.";
    }

    @Override
    public String getModuleVersion()
    {
        return "0.1";
    }

    @Override
    public String getProviderName()
    {
        return "Botts Innovative Research";
    }

    @Override
    public Class<? extends IModuleBase<?>> getModuleClass()
    {
        return ReticulumNetworkProvider.class;
    }

    @Override
    public Class<? extends ModuleConfigBase> getModuleConfigClass()
    {
        return ReticulumNetworkProviderConfig.class;
    }
}
