package com.perfectflow.forge;

import com.perfectflow.CommonClass;
import com.perfectflow.Constants;
import com.perfectflow.forge.client.PerfectFlowForgeClientBindings;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(Constants.MOD_ID)
public final class PerfectFlowForge {
    public PerfectFlowForge() {
        CommonClass.init();
        if (FMLEnvironment.dist.isClient()) {
            PerfectFlowForgeClientBindings.register(FMLJavaModLoadingContext.get().getModEventBus());
        }
        Constants.LOG.info("{} Forge entrypoint initialized", Constants.MOD_NAME);
    }
}
