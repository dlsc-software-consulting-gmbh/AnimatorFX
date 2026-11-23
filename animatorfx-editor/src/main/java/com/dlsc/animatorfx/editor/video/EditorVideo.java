package com.dlsc.animatorfx.editor.video;

import com.dlsc.animatorfx.editor.audio.EditorAudio;
import com.dlsc.animatorfx.player.AnimationPlayer;
import com.dlsc.animatorfx.player.internal.video.MediaVideoOutput;
import com.dlsc.animatorfx.player.internal.video.VideoOutput;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Editor media policy: disabling sound must never disable the video's picture. */
public final class EditorVideo {
    private static final System.Logger LOG = System.getLogger(EditorVideo.class.getName());
    private static Supplier<VideoOutput> factory;

    private EditorVideo() {
    }

    /** For tooling: replaces video output; null restores JavaFX decoding. */
    public static void setOutputFactory(Supplier<VideoOutput> outputFactory) {
        factory = outputFactory;
    }

    public static void install(AnimationPlayer player, Consumer<String> notice) {
        player.setVideoOutputFactory(() -> factory != null ? factory.get() : new MediaVideoOutput(LOG, (id, error) -> {
            var file = player.getAnimation();
            String name = file == null ? id : file.animation().assets().stream().filter(asset -> asset.id().equals(id))
                    .map(asset -> asset.name().isBlank() ? asset.id() : asset.name()).findFirst().orElse(id);
            notice.accept("Could not play video '" + name + "': " + error.getMessage());
        }, "off".equalsIgnoreCase(System.getProperty(EditorAudio.PROPERTY))));
    }
}
