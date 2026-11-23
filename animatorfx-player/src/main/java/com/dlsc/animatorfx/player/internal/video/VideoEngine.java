package com.dlsc.animatorfx.player.internal.video;

import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.player.Frame;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.media.MediaPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** A stable session that reconciles immutable edits without replacing unchanged decoders. */
public final class VideoEngine {
    private Supplier<VideoOutput> factory;
    private final System.Logger logger;
    private final Map<String, ReadOnlyObjectWrapper<MediaPlayer>> players = new HashMap<>();
    private AnimationFile file;
    private VideoOutput output;
    private Frame frame;
    private boolean playing, reversing, muted, suspended;
    private double rate = 1, volume = 1;
    private boolean applying;

    public VideoEngine(Supplier<VideoOutput> factory, System.Logger logger) {
        this.factory = Objects.requireNonNull(factory);
        this.logger = Objects.requireNonNull(logger);
    }

    public void setDocument(AnimationFile file) {
        if (this.file == file) {
            return;
        }
        this.file = file;
        frame = null;
        if (output != null) {
            run(() -> output.setDocument(file));
        }
        players.entrySet().removeIf(entry -> {
            if (file != null && Layers.find(file.animation(), entry.getKey()).orElse(null) instanceof VideoLayer) {
                return false;
            }
            entry.getValue().unbind();
            entry.getValue().set(null);
            return true;
        });
    }

    public void sync(Frame frame, boolean playing, double rate, boolean reversing, boolean muted, double volume) {
        this.frame = frame;
        this.playing = playing;
        this.rate = rate;
        this.reversing = reversing;
        this.muted = muted;
        this.volume = volume;
        apply();
    }

    /** The currently visible decoder, or null until ready or while inactive. */
    public final ReadOnlyObjectProperty<MediaPlayer> playerProperty(String layerId) {
        ReadOnlyObjectWrapper<MediaPlayer> property = players.computeIfAbsent(layerId,
                id -> new ReadOnlyObjectWrapper<>(this, id));
        if (output != null && !property.isBound()) {
            bind(layerId, property);
        }
        return property.getReadOnlyProperty();
    }

    private void bind(String id, ReadOnlyObjectWrapper<MediaPlayer> property) {
        property.bind(output.playerProperty(id));
    }

    private void apply() {
        if (suspended || applying || frame == null || file == null) {
            return;
        }
        applying = true;
        try {
            run(() -> {
                if (output == null) {
                    boolean hasVideo = Layers.flatten(file.animation()).stream().anyMatch(VideoLayer.class::isInstance);
                    if (!hasVideo) {
                        return;
                    }
                    output = factory.get();
                    if (output == null) {
                        suspended = true;
                        return;
                    }
                    output.setDocument(file);
                    players.forEach(this::bind);
                }
                Map<String, Double> durations = new HashMap<>();
                for (Layer layer : Layers.flatten(file.animation())) {
                    if (layer instanceof VideoLayer video) {
                        output.duration(video.assetId()).ifPresent(value -> durations.put(video.assetId(), value));
                    }
                }
                output.apply(VideoSchedule.targets(file.animation(), frame, playing, rate, reversing, muted,
                        volume, durations));
            });
        } finally {
            applying = false;
        }
    }

    public void setOutputFactory(Supplier<VideoOutput> factory) {
        release();
        this.factory = Objects.requireNonNull(factory);
        resume();
    }

    public void release() {
        suspended = true;
        players.values().forEach(property -> {
            property.unbind();
            property.set(null);
        });
        VideoOutput old = output;
        output = null;
        if (old != null) {
            try {
                old.dispose();
            } catch (RuntimeException | LinkageError e) {
                logger.log(System.Logger.Level.WARNING, "Video output could not be released", e);
            }
        }
    }

    public void resume() {
        suspended = false;
        apply();
    }

    public void dispose() {
        release();
    }

    private void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            logger.log(System.Logger.Level.WARNING, "Video output is not available", e);
            release();
        }
    }
}
