package com.perfectflow.neoforge;

import com.perfectflow.CommonClass;
import com.perfectflow.Constants;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Constants.MOD_ID)
public final class PerfectFlowNeoForge {
    public PerfectFlowNeoForge(IEventBus eventBus) {
        Constants.LOG.info("{} NeoForge entrypoint initialized", Constants.MOD_NAME);
        CommonClass.init();
    }
}
