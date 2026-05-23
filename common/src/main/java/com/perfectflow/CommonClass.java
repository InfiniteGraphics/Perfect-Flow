package com.perfectflow;

import com.perfectflow.capture.CaptureController;
import com.perfectflow.config.PerfectFlowConfig;
import com.perfectflow.platform.Services;

public final class CommonClass {
    private static PerfectFlowConfig config;

    private CommonClass() {
    }

    public static void init() {
        config = PerfectFlowConfig.load(Services.PLATFORM.getConfigDirectory());
        CaptureController.INSTANCE.configure(config);
        Constants.LOG.info("{} initialized on {} ({})", Constants.MOD_NAME, Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());
    }

    public static PerfectFlowConfig config() {
        if (config == null) {
            config = PerfectFlowConfig.load(Services.PLATFORM.getConfigDirectory());
        }
        return config;
    }

    public static void reloadConfig() {
        config = PerfectFlowConfig.load(Services.PLATFORM.getConfigDirectory());
        CaptureController.INSTANCE.configure(config);
    }
}
