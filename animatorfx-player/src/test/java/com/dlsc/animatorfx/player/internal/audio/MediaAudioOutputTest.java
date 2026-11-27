package com.dlsc.animatorfx.player.internal.audio;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.SoundCue;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.player.AnimationPlayer;
import com.dlsc.animatorfx.player.AnimationPlayer.Status;
import com.dlsc.animatorfx.player.FxThreadExtension;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real JavaFX media under the headless toolkit. Requires a working native media backend and audio
 * device; a virtual display alone is not enough.
 */
@Tag("native-audio")
@ExtendWith(FxThreadExtension.class)
class MediaAudioOutputTest {

    private final AudioFilesTest.RecordingLogger logger = new AudioFilesTest.RecordingLogger();
    private final List<MediaAudioOutput> outputs = new ArrayList<>();

    /** A quiet 440 Hz tone: 16 bit, mono, 44.1 kHz. */
    static byte[] wav(double seconds) {
        int rate = 44100;
        int samples = (int) Math.round(seconds * rate);
        ByteBuffer buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes()).putInt(36 + samples * 2).put("WAVE".getBytes());
        buffer.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate)
                .putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        buffer.put("data".getBytes()).putInt(samples * 2);
        for (int i = 0; i < samples; i++) {
            buffer.putShort((short) (Math.sin(2 * Math.PI * 440 * i / rate) * 0.2 * Short.MAX_VALUE));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(buffer.array());
        return out.toByteArray();
    }

    /** 0.4 s with a 0.3 s tone from the start and a 0.03 s cue at 0.1 s. */
    private static AnimationFile sounding() {
        AudioLayer tone = new AudioLayer(LayerBase.create("tone", 0.4).withId("tone").withOutPoint(0.3), "tone", 0,
                Animatable.of(1.0), Animatable.of(0.0), false, false);
        Animation animation = Animation.create("smoke", 10, 10, 0.4)
                .withAssets(List.of(new Asset("tone", Asset.Kind.AUDIO, "assets/tone.wav", "tone.wav"),
                        new Asset("blip", Asset.Kind.AUDIO, "assets/blip.wav", "blip.wav"),
                        new Asset("broken", Asset.Kind.AUDIO, "assets/broken.wav", "broken.wav")))
                .withLayers(List.of(tone))
                .withCues(List.of(new SoundCue("c", "", 0.1, "blip", 1, false),
                        new SoundCue("b", "", 0.2, "broken", 1, false),
                        new SoundCue("m", "", 0.25, "missing", 1, false)));
        return new AnimationFile(animation, Map.of("tone", wav(0.3), "blip", wav(0.03),
                "broken", new byte[]{1, 2, 3, 4}));
    }

    private AnimationPlayer player() {
        AnimationPlayer player = new AnimationPlayer(sounding());
        player.setAudioOutputFactory(file -> {
            MediaAudioOutput output = new MediaAudioOutput(file, logger);
            outputs.add(output);
            return output;
        });
        return player;
    }

    /** Lets the toolkit run until the condition holds or the time is up; whether it held. */
    private static boolean await(BooleanSupplier condition, double seconds) {
        Object key = new Object();
        long deadline = System.nanoTime() + (long) (seconds * 1e9);
        boolean[] held = new boolean[1];
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                held[0] = condition.getAsBoolean();
                if (held[0] || System.nanoTime() > deadline) {
                    stop();
                    Platform.exitNestedEventLoop(key, null);
                }
            }
        };
        timer.start();
        Platform.enterNestedEventLoop(key);
        return held[0];
    }

    @Test
    void anAnimationWithSoundPlaysToItsEndWhetherOrNotAudioWorks() {
        AnimationPlayer player = player();
        // inaudible: nothing here depends on the loudness
        player.setVolume(0);

        player.play();
        assertTrue(await(() -> player.getStatus() == Status.STOPPED, 10), "the animation finishes");

        assertEquals(0.4, player.getCurrentTime(), 1e-9);
        assertEquals(1, outputs.size());
        // unplayable and missing cue assets are reported, each once, and never thrown
        assertTrue(logger.warnings.stream().anyMatch(warning -> warning.contains("missing")),
                logger.warnings.toString());
        assertEquals(logger.warnings.size(), logger.warnings.stream().distinct().count(),
                logger.warnings.toString());

        player.scrub(0.1);
        player.dispose();
        player.dispose();
        assertTrue(await(() -> true, 1));
    }

    @Test
    void whereMediaIsAvailableTheLayerGetsAMediaPlayerThatPlays() {
        AnimationPlayer player = player();
        // warm up inaudibly: media players get ready some time after they were created
        player.setVolume(0);
        player.setCycleCount(AnimationPlayer.INDEFINITE);
        player.play();
        boolean played = await(() -> !outputs.isEmpty()
                && (outputs.get(0).hasPlayed() || outputs.get(0).failedAssets().contains("tone")), 8);
        player.stop();
        MediaAudioOutput output = outputs.get(0);
        assumeTrue(played && output.hasPlayed(), "JavaFX media does not play in this environment: "
                + logger.warnings);

        assertEquals(1, output.voiceCount());
        assertTrue(output.duration("tone").isPresent());
        assertEquals(0.3, output.duration("tone").getAsDouble(), 0.02);
        assertFalse(output.failedAssets().contains("tone"));

        // one more pass, still inaudible
        player.setCycleCount(1);
        player.play();
        assertTrue(await(() -> player.getStatus() == Status.STOPPED, 10));
        assertEquals(1, output.voiceCount());

        player.dispose();
        assertEquals(1, outputs.size());
    }

    @Test
    void anMp3VoiceCanStartItsClockBeforeDriftCorrectionSeeksAgain() throws Exception {
        byte[] mp3;
        try (var input = getClass().getResourceAsStream("startup.mp3")) {
            mp3 = input.readAllBytes();
        }
        AudioLayer music = AudioLayer.create("music", "music", 0, 4);
        Animation animation = Animation.create("MP3 startup", 10, 10, 4)
                .withLayers(List.of(music))
                .withAssets(List.of(new Asset("music", Asset.Kind.AUDIO, "assets/music.mp3", "music.mp3")));
        AnimationPlayer player = new AnimationPlayer(new AnimationFile(animation, Map.of("music", mp3)));
        MediaAudioOutput output = new MediaAudioOutput(player.getAnimation(), logger);
        outputs.add(output);
        player.setAudioOutputFactory(file -> output);
        player.setVolume(0);
        try {
            player.play();
            boolean available = await(() -> output.hasPlayed() || output.failedAssets().contains("music"), 8);
            assumeTrue(available && output.hasPlayed(), "MP3 media is not available: " + logger.warnings);
            assertTrue(await(() -> player.getCurrentTime() >= 2.5, 8));
            assertTrue(output.seekCount() <= 2, "startup should not repeatedly seek: " + output.seekCount());
            assertTrue(output.mediaTime(music.id()) > 1, "The decoder must advance, not just report PLAYING");
        } finally {
            player.stop();
            player.dispose();
        }
    }

    @Test
    void aVoiceFollowsTheAnimationClockWithoutBeingRepositionedAllTheTime() {
        AudioLayer tone = new AudioLayer(LayerBase.create("tone", 2).withId("tone"), "tone", 0, Animatable.of(1.0),
                Animatable.of(0.0), false, false);
        Animation animation = Animation.create("sync", 10, 10, 2)
                .withAssets(List.of(new Asset("tone", Asset.Kind.AUDIO, "assets/tone.wav", "tone.wav")))
                .withLayers(List.of(tone));
        AnimationPlayer player = new AnimationPlayer(new AnimationFile(animation, Map.of("tone", wav(2))));
        player.setAudioOutputFactory(file -> {
            MediaAudioOutput output = new MediaAudioOutput(file, logger);
            outputs.add(output);
            return output;
        });
        // inaudible throughout
        player.setVolume(0);
        player.setCycleCount(AnimationPlayer.INDEFINITE);
        player.play();
        boolean played = await(() -> !outputs.isEmpty()
                && (outputs.get(0).hasPlayed() || outputs.get(0).failedAssets().contains("tone")), 8);
        player.stop();
        MediaAudioOutput output = outputs.get(0);
        assumeTrue(played && output.hasPlayed(), "JavaFX media does not play in this environment: "
                + logger.warnings);
        int before = output.seekCount();

        player.play();
        await(() -> player.getCurrentTime() >= 1, 5);
        assertEquals(player.getCurrentTime(), output.mediaTime("tone"), 0.15);
        assertTrue(output.seekCount() - before <= 2, "seeks: " + (output.seekCount() - before));

        player.seek(0.2);
        await(() -> player.getCurrentTime() >= 0.8, 5);
        assertEquals(player.getCurrentTime(), output.mediaTime("tone"), 0.15);
        assertTrue(output.seekCount() - before <= 4, "seeks: " + (output.seekCount() - before));

        player.pause();
        await(() -> false, 0.3);
        double held = output.mediaTime("tone");
        await(() -> false, 0.3);
        assertEquals(held, output.mediaTime("tone"), 0.02, "a paused player is silent");

        player.dispose();
    }

    @Test
    void aClipThatEndedSoundsAgainOnTheNextCycle() {
        AnimationPlayer player = player();
        // inaudible throughout
        player.setVolume(0);
        player.setCycleCount(AnimationPlayer.INDEFINITE);
        player.play();
        boolean played = await(() -> !outputs.isEmpty()
                && (outputs.get(0).hasPlayed() || outputs.get(0).failedAssets().contains("tone")), 8);
        MediaAudioOutput output = outputs.get(0);
        assumeTrue(played && output.hasPlayed(), "JavaFX media does not play in this environment: "
                + logger.warnings);

        for (int cycle = 0; cycle < 10; cycle++) {
            // past the end of the 0.3 s clip, then well into the clip of the next cycle
            assertTrue(await(() -> player.getCurrentTime() > 0.32, 5));
            assertTrue(await(() -> player.getCurrentTime() > 0.15 && player.getCurrentTime() < 0.25, 5));
            assertEquals(player.getCurrentTime(), output.mediaTime("tone"), 0.12, "cycle " + cycle);
        }
        player.stop();
        player.dispose();
    }

    /** A real output for a document with one layer on a 0.3 s tone and a 5 s sound for cues. */
    private MediaAudioOutput output(boolean loop) {
        AudioLayer tone = new AudioLayer(LayerBase.create("tone", 4).withId("tone"), "tone", 0, Animatable.of(1.0),
                Animatable.of(0.0), loop, false);
        Animation animation = Animation.create("direct", 10, 10, 4)
                .withAssets(List.of(new Asset("tone", Asset.Kind.AUDIO, "assets/tone.wav", "tone.wav"),
                        new Asset("long", Asset.Kind.AUDIO, "assets/long.wav", "long.wav")))
                .withLayers(List.of(tone));
        MediaAudioOutput output = new MediaAudioOutput(
                new AnimationFile(animation, Map.of("tone", wav(0.3), "long", wav(5))), logger);
        outputs.add(output);
        return output;
    }

    private static VoiceTarget inaudibleAt(double mediaTime) {
        return new VoiceTarget("tone", "tone", true, mediaTime, 0, 0, 1);
    }

    @Test
    void aLoopingVoiceFirstHeardBeyondOneClipLengthIsPositionedInsideTheClip() {
        MediaAudioOutput output = output(true);
        // the clip length is not known yet, so the target is not wrapped
        output.apply(List.of(inaudibleAt(1.0)));
        boolean played = await(() -> output.hasPlayed() || output.failedAssets().contains("tone"), 8);
        try {
            assumeTrue(played && output.hasPlayed(), "JavaFX media does not play in this environment: "
                    + logger.warnings);

            assertEquals(0.1, output.maxSeekPosition(), 1e-6);
        } finally {
            output.dispose();
        }
    }

    @Test
    void theFirstSnippetSoundsOnceItsVoiceIsReadyAndThenEnds() {
        MediaAudioOutput output = output(false);
        output.snippet(List.of(inaudibleAt(0.05)), 0.1);
        boolean played = await(() -> output.hasPlayed() || output.failedAssets().contains("tone"), 8);
        try {
            assumeTrue(played && output.hasPlayed(), "JavaFX media does not play in this environment: "
                    + logger.warnings);

            await(() -> false, 0.4);
            double held = output.mediaTime("tone");
            await(() -> false, 0.2);
            assertEquals(held, output.mediaTime("tone"), 0.02, "the snippet ended");
            assertTrue(held < 0.29, "and did not run to the end of the sound: " + held);
        } finally {
            output.dispose();
        }
    }

    @Test
    void cuesRingOutThroughSilenceAndAreCutByStopCues() {
        MediaAudioOutput output = output(false);
        try {
            // AudioClip.isPlaying() can be true even when its native player failed to start.
            output.apply(List.of(inaudibleAt(0)));
            boolean played = await(() -> output.hasPlayed() || output.failedAssets().contains("tone"), 8);
            assumeTrue(played && output.hasPlayed(), "JavaFX media does not play in this environment: "
                    + logger.warnings);
            output.silence();

            // inaudible: volume 0
            output.playCue("long", 0);
            assertTrue(await(output::cuesSounding, 3), "the cue starts");

            output.silence();
            await(() -> false, 0.2);
            assertTrue(output.cuesSounding(), "silence leaves a cue alone");

            output.stopCues();
            assertTrue(await(() -> !output.cuesSounding(), 1), "stopCues cuts it");
        } finally {
            output.dispose();
        }
    }
}
