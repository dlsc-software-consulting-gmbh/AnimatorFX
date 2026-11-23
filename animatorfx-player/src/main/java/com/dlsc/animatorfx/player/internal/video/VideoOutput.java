package com.dlsc.animatorfx.player.internal.video;

import com.dlsc.animatorfx.model.io.AnimationFile;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.scene.media.MediaPlayer;

import java.util.List;
import java.util.OptionalDouble;

/** Decoder boundary, used only on the JavaFX application thread. */
public interface VideoOutput {
    void setDocument(AnimationFile file);
    OptionalDouble duration(String assetId);
    ReadOnlyObjectProperty<MediaPlayer> playerProperty(String layerId);
    void apply(List<VideoTarget> targets);
    void dispose();
}
