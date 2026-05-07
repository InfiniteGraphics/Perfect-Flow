package com.perfectframe.fabric.shader;

import com.perfectframe.shader.CaptureAttachment;
import com.perfectframe.shader.CaptureSource;
import com.perfectframe.shader.DepthTextureCaptureAttachment;
import com.perfectframe.shader.RenderTargetCaptureAttachment;
import com.perfectframe.fabric.platform.FabricMainTargetAccess;
import com.perfectframe.platform.Services;
import net.minecraft.client.MinecraftClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

public final class IrisCaptureBridge {
    private IrisCaptureBridge() {
    }

    public static CaptureSource resolve(MinecraftClient minecraft) {
        if (!isShaderPackInUse()) {
            String reason = "Iris is installed, but no shader pack is currently enabled.";
            return CaptureSource.unavailable("iris", "iris/inactive", reason, reason);
        }

        CaptureAttachment color = new RenderTargetCaptureAttachment(new FabricMainTargetAccess(minecraft.getFramebuffer()));
        if (!Services.PLATFORM.clientAccess().isWorldReady() || !com.perfectframe.CommonClass.config().capture.recordDepth) {
            return CaptureSource.available("iris", "iris/main-framebuffer-final", color, null);
        }

        Optional<Integer> depthTexture = findDepthTextureId();
        if (depthTexture.isEmpty()) {
            String reason = "Iris is active, but PerfectFlow could not resolve the current Iris depth texture. Color capture is using the final main framebuffer output.";
            return new CaptureSource("iris", "iris/main-framebuffer-final", color, null, "", reason);
        }

        CaptureAttachment depth = new DepthTextureCaptureAttachment(depthTexture.get(), color.width(), color.height());
        return CaptureSource.available("iris", "iris/main-framebuffer-final+depth", color, depth);
    }

    private static boolean isShaderPackInUse() {
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = invokeStatic(apiClass, "getInstance");
            Object value = invoke(api, "isShaderPackInUse");
            return value instanceof Boolean enabled && enabled;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    private static Optional<Integer> findDepthTextureId() {
        try {
            PipelineContext context = resolvePipelineContext();
            if (context == null) {
                return Optional.empty();
            }

            Object texture = firstNonNull(
                    invokeIfPresent(context.renderTargets(), "getDepthTexture"),
                    invokeIfPresent(context.renderTargets(), "getDepthTextureId"),
                    invokeIfPresent(context.renderTargets(), "getDepthTextureNoTranslucents"),
                    firstNonNull(
                            fieldIfPresent(context.renderTargets(), "depthTexture"),
                            fieldIfPresent(context.renderTargets(), "depthTextureNoTranslucents")
                    )
            );
            Integer id = coerceTextureId(texture);
            return id != null && id > 0 ? Optional.of(id) : Optional.empty();
        } catch (ReflectiveOperationException | LinkageError exception) {
            return Optional.empty();
        }
    }

    private static PipelineContext resolvePipelineContext() throws ReflectiveOperationException {
        Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
        Object pipelineManager = invokeStatic(irisClass, "getPipelineManager");
        Object pipeline = unwrapOptional(invokeIfPresent(pipelineManager, "getPipelineNullable"));
        if (pipeline == null) {
            pipeline = unwrapOptional(invokeIfPresent(pipelineManager, "getPipeline"));
        }
        if (pipeline == null) {
            return null;
        }
        Object renderTargets = firstNonNull(
                invokeIfPresent(pipeline, "getRenderTargets"),
                fieldIfPresent(pipeline, "renderTargets")
        );
        return renderTargets == null ? null : new PipelineContext(pipeline, renderTargets);
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

    private static Object invokeIndexedIfPresent(Object owner, String methodName, int index) throws ReflectiveOperationException {
        if (owner == null) {
            return null;
        }
        try {
            Method method = owner.getClass().getMethod(methodName, int.class);
            method.setAccessible(true);
            return method.invoke(owner, index);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private static Object fieldIfPresent(Object owner, String fieldName) throws ReflectiveOperationException {
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

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static Object firstNonNull(Object first, Object second, Object third) {
        if (first != null) {
            return first;
        }
        return second != null ? second : third;
    }

    private static Object firstNonNull(Object first, Object second, Object third, Object fourth) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return third != null ? third : fourth;
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
                firstNonNull(
                        fieldIfPresent(texture, "texture"),
                        fieldIfPresent(texture, "id"),
                        fieldIfPresent(texture, "glId")
                )
        );
        return id instanceof Integer value ? value : null;
    }

    private record PipelineContext(Object pipeline, Object renderTargets) {
    }
}
