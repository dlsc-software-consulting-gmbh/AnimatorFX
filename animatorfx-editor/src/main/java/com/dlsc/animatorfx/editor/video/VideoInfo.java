package com.dlsc.animatorfx.editor.video;

/** Metadata obtained from a real video track, never stored in undo history. */
public record VideoInfo(double duration, double width, double height, boolean hasAudio) {
    public VideoInfo {
        if (!(duration > 0) || !Double.isFinite(duration) || !(width > 0) || !Double.isFinite(width)
                || !(height > 0) || !Double.isFinite(height)) {
            throw new IllegalArgumentException("Video duration and dimensions must be finite and positive");
        }
    }

    public String description() {
        return String.format(java.util.Locale.ROOT, "%.0f x %.0f, %.2f s%s",
                width, height, duration, hasAudio ? ", audio" : "");
    }
}
