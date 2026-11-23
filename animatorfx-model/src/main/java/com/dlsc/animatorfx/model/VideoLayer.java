package com.dlsc.animatorfx.model;

import java.util.Objects;

/**
 * An embedded video drawn at {@code (0,0)} with the given dimensions. Picture and sound start
 * {@code clipOffset} seconds into the source at the layer's in-point. Muting affects sound only.
 */
public record VideoLayer(LayerBase base, String assetId, double width, double height,
                         double clipOffset, Animatable<Double> volume, boolean loop, boolean muted) implements Layer {

    public VideoLayer {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(volume, "volume");
    }

    @Override
    public VideoLayer withBase(LayerBase base) {
        return new VideoLayer(base, assetId, width, height, clipOffset, volume, loop, muted);
    }

    public VideoLayer withAssetId(String assetId) {
        return new VideoLayer(base, assetId, width, height, clipOffset, volume, loop, muted);
    }

    public VideoLayer withWidth(double width) {
        return new VideoLayer(base, assetId, width, height, clipOffset, volume, loop, muted);
    }

    public VideoLayer withHeight(double height) {
        return new VideoLayer(base, assetId, width, height, clipOffset, volume, loop, muted);
    }

    public VideoLayer withClipOffset(double clipOffset) {
        return new VideoLayer(base, assetId, width, height, clipOffset, volume, loop, muted);
    }

    public VideoLayer withVolume(Animatable<Double> volume) {
        return new VideoLayer(base, assetId, width, height, clipOffset, volume, loop, muted);
    }

    public VideoLayer withLoop(boolean loop) {
        return new VideoLayer(base, assetId, width, height, clipOffset, volume, loop, muted);
    }

    public VideoLayer withMuted(boolean muted) {
        return new VideoLayer(base, assetId, width, height, clipOffset, volume, loop, muted);
    }
}
