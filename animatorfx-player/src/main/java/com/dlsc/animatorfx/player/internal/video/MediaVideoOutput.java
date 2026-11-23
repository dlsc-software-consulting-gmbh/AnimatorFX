package com.dlsc.animatorfx.player.internal.video;

import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.model.io.AnimationFile;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.VideoTrack;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/** JavaFX video: the visible picture and soundtrack share one decoder per layer instance. */
public final class MediaVideoOutput implements VideoOutput {
    private static final long SEEK_SETTLE_NANOS = 200_000_000L;
    private static final long DRIFT_SEEK_NANOS = 1_000_000_000L;
    private final System.Logger logger;
    private final BiConsumer<String, Throwable> failures;
    private final boolean silent;
    private final VideoFiles files = new VideoFiles();
    private final Map<String, Voice> voices = new HashMap<>();
    private final Map<String, ReadOnlyObjectWrapper<MediaPlayer>> players = new HashMap<>();
    private final Map<String, Double> durations = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private AnimationFile file;
    private boolean disposed;

    public MediaVideoOutput() {
        this(System.getLogger(MediaVideoOutput.class.getName()), (id, error) -> {}, false);
    }

    public MediaVideoOutput(System.Logger logger, BiConsumer<String, Throwable> failures, boolean silent) {
        this.logger = logger;
        this.failures = failures;
        this.silent = silent;
    }

    @Override
    public void setDocument(AnimationFile next) {
        AnimationFile before = file;
        file = next;
        Map<String, VideoLayer> layers = new HashMap<>();
        if (next != null) {
            Layers.flatten(next.animation()).forEach(layer -> {
                if (layer instanceof VideoLayer video) layers.put(video.id(), video);
            });
        }
        voices.entrySet().removeIf(entry -> {
            Voice voice = entry.getValue();
            VideoLayer layer = layers.get(entry.getKey());
            if (layer != null && layer.assetId().equals(voice.assetId)
                    && next.assets().get(layer.assetId()) == voice.bytes) return false;
            voice.close();
            return true;
        });
        durations.keySet().removeIf(id -> next == null || before == null
                || next.assets().get(id) != before.assets().get(id));
        failed.removeIf(id -> next == null || before == null
                || next.assets().get(id) != before.assets().get(id));
        players.entrySet().removeIf(entry -> {
            if (layers.containsKey(entry.getKey())) return false;
            entry.getValue().set(null);
            return true;
        });
        files.setDocument(next);
        releaseUnusedFiles();
    }

    @Override
    public OptionalDouble duration(String assetId) {
        Double value = durations.get(assetId);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    @Override
    public final ReadOnlyObjectProperty<MediaPlayer> playerProperty(String layerId) {
        return property(layerId).getReadOnlyProperty();
    }

    private ReadOnlyObjectWrapper<MediaPlayer> property(String layerId) {
        return players.computeIfAbsent(layerId, id -> new ReadOnlyObjectWrapper<>(this, id));
    }

    @Override
    public void apply(List<VideoTarget> targets) {
        if (disposed || file == null) return;
        for (VideoTarget target : targets) {
            if (failed.contains(target.assetId())) continue;
            Voice voice = voices.get(target.layerId());
            if (voice == null && target.visible()) {
                voice = new Voice(target.layerId(), target.assetId(), file.assets().get(target.assetId()));
                voices.put(target.layerId(), voice);
                voice.load();
            }
            if (voice != null) {
                voice.want(target);
            }
        }
    }

    private final class Voice {
        final String layerId, assetId;
        final byte[] bytes;
        final PauseTransition settling = new PauseTransition();
        final PauseTransition timeout = new PauseTransition(Duration.seconds(10));
        MediaPlayer player;
        VideoTarget wanted;
        boolean ready, closed;
        boolean reposition;
        boolean fractionalClock;
        long lastSeek;
        long lastTargetNanos;
        double lastPosition = Double.NaN;

        Voice(String layerId, String assetId, byte[] bytes) {
            this.layerId = layerId;
            this.assetId = assetId;
            this.bytes = bytes;
            settling.setOnFinished(event -> sync());
            timeout.setOnFinished(event -> fail(assetId, new IllegalStateException("Video readiness timed out")));
        }

        boolean current() {
            return !disposed && !closed && voices.get(layerId) == this;
        }

        void want(VideoTarget target) {
            long now = System.nanoTime();
            if (wanted != null) {
                double expected = wanted.mediaTime();
                if (wanted.playing()) expected += (now - lastTargetNanos) / 1_000_000_000.0 * wanted.rate();
                reposition |= target.mediaTime() < wanted.mediaTime() - 0.1
                        || target.mediaTime() > expected + 0.1
                        || !target.playing() && target.mediaTime() != wanted.mediaTime();
            }
            wanted = target;
            lastTargetNanos = now;
            sync();
        }

        void load() {
            timeout.playFromStart();
            files.path(assetId).whenComplete((path, error) -> Platform.runLater(() -> {
                if (!current()) return;
                if (error != null) {
                    fail(assetId, error);
                    return;
                }
                try {
                    Media media = new Media(path.toUri().toString());
                    media.setOnError(() -> { if (current()) fail(assetId, media.getError()); });
                    player = new MediaPlayer(media);
                    player.setVolume(0);
                    player.setOnReady(() -> {
                        if (!current()) return;
                        double seconds = player.getTotalDuration().toSeconds();
                        if (!(seconds > 0) || !Double.isFinite(seconds)
                                || media.getTracks().stream().noneMatch(VideoTrack.class::isInstance)) {
                            fail(assetId, new IllegalArgumentException("The asset has no usable video track"));
                            return;
                        }
                        timeout.stop();
                        durations.put(assetId, seconds);
                        ready = true;
                        sync();
                    });
                    player.setOnError(() -> { if (current()) fail(assetId, player.getError()); });
                    player.setOnEndOfMedia(() -> { if (current()) sync(); });
                } catch (RuntimeException | LinkageError e) {
                    fail(assetId, e);
                }
            }));
        }

        void sync() {
            if (!current() || !ready || wanted == null) return;
            try {
                VideoTarget target = wanted;
                double position = target.mediaTime();
                double duration = durations.get(assetId);
                boolean visible = target.visible();
                if (position >= duration) {
                    if (target.loop()) position %= duration;
                    else visible = false;
                }
                property(layerId).set(visible ? player : null);
                if (!visible) {
                    player.setVolume(0);
                    player.pause();
                    settling.stop();
                    lastPosition = Double.NaN;
                    return;
                }
                boolean advancing = target.playing();
                player.setVolume(silent || !advancing ? 0 : target.volume());
                if (!advancing) player.pause();
                long now = System.nanoTime();
                double actual = player.getCurrentTime().toSeconds();
                fractionalClock |= Math.abs(actual - Math.rint(actual)) > 1e-6;
                double drift = Math.abs(actual - position);
                // AVFoundation may report only whole seconds; respect that quantization window.
                double tolerance = advancing ? (fractionalClock ? 0.1 : 1) : 0.015;
                boolean needsSeek = Double.isNaN(lastPosition) || reposition || drift > tolerance;
                if (needsSeek) {
                    // Coarse native clocks must not cause corrective seeks to starve decoding.
                    long interval = advancing && !reposition ? DRIFT_SEEK_NANOS : SEEK_SETTLE_NANOS;
                    long remaining = interval - (now - lastSeek);
                    if (remaining <= 0 || Double.isNaN(lastPosition)) {
                        player.seek(Duration.seconds(position));
                        lastSeek = now;
                        lastPosition = position;
                        reposition = false;
                    } else if (!advancing || reposition) {
                        settling.setDuration(Duration.millis(remaining / 1_000_000.0));
                        settling.playFromStart();
                    }
                }
                if (advancing) {
                    player.setRate(target.rate());
                    if (player.getStatus() != MediaPlayer.Status.PLAYING) player.play();
                }
            } catch (RuntimeException | LinkageError e) {
                fail(assetId, e);
            }
        }

        void close() {
            closed = true;
            settling.stop();
            timeout.stop();
            property(layerId).set(null);
            if (player != null) {
                player.setOnReady(null);
                player.setOnError(null);
                player.setOnEndOfMedia(null);
                player.getMedia().setOnError(null);
                player.dispose();
                player = null;
            }
        }
    }

    private void fail(String assetId, Throwable error) {
        if (disposed || !failed.add(assetId)) return;
        voices.values().stream().filter(voice -> voice.assetId.equals(assetId)).forEach(Voice::close);
        releaseUnusedFiles();
        logger.log(System.Logger.Level.WARNING, "Video asset '" + assetId + "' could not be played", error);
        try {
            failures.accept(assetId, error);
        } catch (RuntimeException e) {
            logger.log(System.Logger.Level.WARNING, "Video failure could not be reported", e);
        }
    }

    private void releaseUnusedFiles() {
        files.retain(voices.values().stream().filter(voice -> !voice.closed)
                .map(voice -> voice.assetId).collect(Collectors.toSet()));
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        voices.values().forEach(Voice::close);
        voices.clear();
        players.values().forEach(property -> property.set(null));
        durations.clear();
        files.dispose();
        file = null;
    }
}
