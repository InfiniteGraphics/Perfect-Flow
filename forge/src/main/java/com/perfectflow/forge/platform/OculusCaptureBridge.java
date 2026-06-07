package com.perfectflow.forge.platform;

import com.perfectflow.CommonClass;
import com.perfectflow.platform.Services;
import com.perfectflow.shader.CaptureAttachment;
import com.perfectflow.shader.CaptureSource;
import com.perfectflow.shader.DepthTextureCaptureAttachment;
import com.perfectflow.shader.RenderTargetCaptureAttachment;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

public final class OculusCaptureBridge {
    private OculusCaptureBridge() {
    }

    public static CaptureSource resolve(Minecraft minecraft) {
        if (!isShaderPackInUse()) {
            String reason = "Oculus is installed, but no shader pack is currently enabled.";
            return CaptureSource.unavailable("oculus", "oculus/inactive", reason, reason);
        }

        CaptureAttachment color = new RenderTargetCaptureAttachment(new ForgeMainTargetAccess(minecraft.getMainRenderTarget()));
        if (!Services.PLATFORM.clientAccess().isWorldReady() || !CommonClass.config().capture.recordDepth) {
            return CaptureSource.available("oculus", "oculus/main-framebuffer-final", color, null);
        }

        Optional<Integer> depthTexture = findDepthTextureId();
        if (depthTexture.isEmpty()) {
            String reason = "Oculus is active, but PerfectFlow could not resolve the current Oculus depth texture. Color capture is using the final main framebuffer output.";
            return new CaptureSource("oculus", "oculus/main-framebuffer-final", color, null, "", reason);
        }

        CaptureAttachment depth = new DepthTextureCaptureAttachment(depthTexture.get(), color.width(), color.height());
        return CaptureSource.available("oculus", "oculus/main-framebuffer-final+depth", color, depth);
    }

    private static boolean isShaderPackInUse() {
        try {
            Class<?> apiClass = Class.forName("net.coderbot.iris.api.v0.IrisApi");
            Object api = invokeStatic(apiClass, "getInstance");
            Object value = invoke(api, "isShaderPackInUse");
            return value instanceof Boolean enabled && enabled;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    private static Optional<Integer> findDepthTextureId() {
        try {
            Object renderTargets = resolveRenderTargets();
            if (renderTargets == null) {
                return Optional.empty();
            }
            Object texture = firstNonNull(
                    invokeIfPresent(renderTargets, "getDepthTexture"),
                    invokeIfPresent(renderTargets, "getDepthTextureId"),
                    invokeIfPresent(renderTargets, "getDepthTextureNoTranslucents"),
                    fieldIfPresent(renderTargets, "depthTexture"),
                    fieldIfPresent(renderTargets, "depthTextureNoTranslucents")
            );
            Integer id = coerceTextureId(texture);
            return id != null && id > 0 ? Optional.of(id) : Optional.empty();
        } catch (ReflectiveOperationException | LinkageError exception) {
            return Optional.empty();
        }
    }

    private static Object resolveRenderTargets() throws ReflectiveOperationException {
        Class<?> irisClass = Class.forName("net.coderbot.iris.Iris");
        Object pipelineManager = invokeStatic(irisClass, "getPipelineManager");
        Object pipeline = unwrapOptional(invokeIfPresent(pipelineManager, "getPipelineNullable"));
        if (pipeline == null) {
            pipeline = unwrapOptional(invokeIfPresent(pipelineManager, "getPipeline"));
        }
        if (pipeline == null) {
            return null;
        }
        return firstNonNull(
                invokeIfPresent(pipeline, "getRenderTargets"),
                fieldIfPresent(pipeline, "renderTargets")
        );
    }

    private static Object invokeStatic(Class<?> owner, String methodName) throws ReflectiveOperationException {
        Method method = owner.getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private static Object invoke(Object owner, String methodName) throws ReflectiveOperationException {
        Method method = owner.getClass().getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(owner);
    }

    private static Object invokeIfPresent(Object owner, String methodName) throws ReflectiveOperationException {
        if (owner == null) {
            return null;
        }
        try {
            return invoke(owner, methodName);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private static Object fieldIfPresent(Object owner, String fieldName) throws ReflectiveOperationException {
        if (owner == null) {
            return null;
        }
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException exception) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object unwrapOptional(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return value;
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer coerceTextureId(Object texture) throws ReflectiveOperationException {
        texture = unwrapOptional(texture);
        if (texture instanceof Integer id) {
            return id;
        }
        if (texture == null) {
            return null;
        }
        Object id = firstNonNull(
                invokeIfPresent(texture, "getTextureId"),
                invokeIfPresent(texture, "getGlId"),
                invokeIfPresent(texture, "getId"),
                fieldIfPresent(texture, "texture"),
                fieldIfPresent(texture, "id"),
                fieldIfPresent(texture, "glId")
        );
        return id instanceof Integer value ? value : null;
    }
}
