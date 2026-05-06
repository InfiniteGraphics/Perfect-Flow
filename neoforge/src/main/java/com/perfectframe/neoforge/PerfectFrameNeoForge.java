package com.perfectframe.neoforge;

import com.perfectframe.CommonClass;
import com.perfectframe.Constants;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Constants.MOD_ID)
public final class PerfectFrameNeoForge {
    public PerfectFrameNeoForge(IEventBus eventBus) {
        Constants.LOG.info("{} NeoForge entrypoint initialized", Constants.MOD_NAME);
        CommonClass.init();
    }
}
