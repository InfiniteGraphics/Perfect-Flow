package com.perfectframe;

import com.perfectframe.capture.CaptureController;
import com.perfectframe.config.PerfectFrameConfig;
import com.perfectframe.platform.Services;

public final class CommonClass {
    private static PerfectFrameConfig config;

    private CommonClass() {
    }

    public static void init() {
        config = PerfectFrameConfig.load(Services.PLATFORM.getConfigDirectory());
        CaptureController.INSTANCE.configure(config);
        Constants.LOG.info("{} initialized on {} ({})", Constants.MOD_NAME, Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());
    }

    public static PerfectFrameConfig config() {
        if (config == null) {
            config = PerfectFrameConfig.load(Services.PLATFORM.getConfigDirectory());
        }
        return config;
    }

    public static void reloadConfig() {
        config = PerfectFrameConfig.load(Services.PLATFORM.getConfigDirectory());
        CaptureController.INSTANCE.configure(config);
    }
}
