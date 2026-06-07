package com.perfectflow.util;

import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class ComponentCompat {
    private ComponentCompat() {
    }

    public static Component literal(String value) {
        try {
            Method method = Component.class.getMethod("literal", String.class);
            Object result = method.invoke(null, value);
            if (result instanceof Component component) {
                return component;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> textComponentClass = Class.forName("net.minecraft.network.chat.TextComponent");
            Constructor<?> constructor = textComponentClass.getConstructor(String.class);
            Object result = constructor.newInstance(value);
            if (result instanceof Component component) {
                return component;
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to create a literal chat component.", exception);
        }

        throw new IllegalStateException("Unable to create a literal chat component.");
    }
}
