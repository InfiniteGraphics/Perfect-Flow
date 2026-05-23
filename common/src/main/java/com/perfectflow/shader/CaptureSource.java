package com.perfectflow.shader;

public record CaptureSource(
        String id,
        String label,
        CaptureAttachment colorAttachment,
        CaptureAttachment depthAttachment,
        String colorUnavailableReason,
        String depthUnavailableReason
) {
    public static CaptureSource available(String id, String label, CaptureAttachment colorAttachment, CaptureAttachment depthAttachment) {
        return new CaptureSource(id, label, colorAttachment, depthAttachment, "", "");
    }

    public static CaptureSource unavailable(String id, String label, String colorUnavailableReason, String depthUnavailableReason) {
        return new CaptureSource(id, label, null, null, colorUnavailableReason, depthUnavailableReason);
    }

    public boolean hasColor() {
        return colorAttachment != null;
    }

    public boolean hasDepth() {
        return depthAttachment != null;
    }

    public int width() {
        return hasColor() ? colorAttachment.width() : depthAttachment.width();
    }

    public int height() {
        return hasColor() ? colorAttachment.height() : depthAttachment.height();
    }

    public void destroy() {
        if (hasColor()) {
            colorAttachment.destroy();
        }
        if (hasDepth() && depthAttachment != colorAttachment) {
            depthAttachment.destroy();
        }
    }
}
