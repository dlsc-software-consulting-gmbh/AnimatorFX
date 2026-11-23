package com.dlsc.animatorfx.player.internal.video;

/** Desired picture and sound of one video-layer instance, in source seconds. */
public record VideoTarget(String layerId, String assetId, boolean visible, boolean playing,
                          double mediaTime, double volume, double rate, boolean loop) {
}
