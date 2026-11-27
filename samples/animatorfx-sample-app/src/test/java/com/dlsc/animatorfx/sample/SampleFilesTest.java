package com.dlsc.animatorfx.sample;

import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.Marker;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.PathData;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.SoundCue;
import com.dlsc.animatorfx.model.Stroke;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.model.io.AnimationIO;
import com.dlsc.animatorfx.player.Evaluator;
import com.dlsc.animatorfx.player.Frame;
import com.dlsc.animatorfx.player.LayerState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The committed files of {@code samples/animations}: up to date with the generator, loadable,
 * and each showing the feature it is there for.
 */
class SampleFilesTest {

    private static final Path DIRECTORY = Path.of(System.getProperty("animatorfx.samples.dir", "../animations"));

    private static final boolean WRITE = Boolean.getBoolean("animatorfx.samples.write");

    @BeforeAll
    static void writeWhenAsked() throws IOException {
        if (WRITE) {
            SampleGenerator.writeAll(DIRECTORY);
        }
    }

    static Stream<String> names() {
        return SampleGenerator.all().keySet().stream();
    }

    private static Animation committed(String name) throws IOException {
        return AnimationIO.read(DIRECTORY.resolve(name + ".afx")).animation();
    }

    @Test
    void loginSceneHasAStaggeredEntranceAndAnAmbientLoop() throws IOException {
        if (Boolean.getBoolean("animatorfx.login.write")) {
            AnimationIO.write(LoginSceneGenerator.create(), DIRECTORY.resolve("login.afx"));
        }
        assertTrue(Files.exists(DIRECTORY.resolve("login.afx")), "login sample is missing");
        Animation animation = committed("login");
        assertEquals(LoginSceneGenerator.create().animation(), animation);
        if (!Boolean.getBoolean("animatorfx.login.write")) {
            assertEquals(animation, Samples.load("login").animation());
        }
        assertEquals(1200, animation.width());
        assertEquals(800, animation.height());
        assertEquals(List.of("intro", "ambient"), animation.markers().stream().map(Marker::name).toList());
        assertEquals(List.of("login-form"), Layers.slots(animation).stream().map(SlotLayer::slotName).toList());
        Frame start = Evaluator.sample(animation, 0);
        Frame entered = Evaluator.sample(animation, 4);
        assertTrue(start.get("login-form").opacity() < entered.get("login-form").opacity());
        assertTrue(start.get("login-left-card").position().x() < entered.get("login-left-card").position().x());
        assertTrue(start.get("login-right-card").position().x() > entered.get("login-right-card").position().x());
        assertNotEquals(Evaluator.sample(animation, 4).get("login-orb").position(),
                Evaluator.sample(animation, 8).get("login-orb").position());
        assertEquals(Evaluator.sample(animation, 4).get("login-orb").position(),
                Evaluator.sample(animation, 12).get("login-orb").position());
    }

    @Test
    void winterTownIsAnEditableLoopWithMovingSceneElements() throws IOException {
        if (Boolean.getBoolean("animatorfx.winter.write")) {
            WinterTownGenerator.write(DIRECTORY.resolve("winter-town.afx"));
        }
        assertTrue(Files.exists(DIRECTORY.resolve("winter-town.afx")), "winter-town sample is missing");
        Animation animation = committed("winter-town");
        assertEquals(WinterTownGenerator.create().animation(), animation);
        assertEquals(1200, animation.width());
        assertEquals(800, animation.height());
        assertEquals(20, animation.duration());
        assertTrue(Layers.flatten(animation).size() > 80);
        Frame start = Evaluator.sample(animation, 0);
        Frame middle = Evaluator.sample(animation, 10);
        Frame end = Evaluator.sample(animation, 20);
        for (String id : List.of("winter-car", "winter-snow-0", "winter-character-1", "winter-cloud")) {
            assertNotEquals(start.get(id).position(), middle.get(id).position(), id);
            assertEquals(start.get(id).position(), end.get(id).position(), id);
        }
        assertNotNull(Layers.find(animation, "winter-cloud").orElseThrow().base().motionPath());
        assertNotNull(Layers.find(animation, "winter-house-a-shine").orElseThrow().base().mask());
        assertNotEquals(start.get("winter-character-1-arm-b").rotation(),
                middle.get("winter-character-1-arm-b").rotation());
        assertTrue(Evaluator.sample(animation, 2.12).get("winter-character-1-eye-a").scale().y() < .1);
    }

    @ParameterizedTest
    @MethodSource("names")
    void theCommittedFileIsWhatTheGeneratorProduces(String name) throws IOException {
        String hint = "Run the generator again, see SampleGenerator";
        AnimationFile generated = SampleGenerator.files().get(name);
        AnimationFile committed = AnimationIO.read(DIRECTORY.resolve(name + ".afx"));
        assertEquals(generated.animation(), committed.animation(), hint);
        assertEquals(generated.assets().keySet(), committed.assets().keySet(), hint);
        for (String id : generated.assets().keySet()) {
            assertArrayEquals(generated.assets().get(id), committed.assets().get(id), hint + ": " + id);
        }
        // and what is in the archives, entry by entry. Not the archives themselves: how the entries
        // are compressed is up to the zip library of the JDK that wrote them.
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        AnimationIO.write(generated, written);
        Map<String, byte[]> expected = entries(new ByteArrayInputStream(written.toByteArray()));
        Map<String, byte[]> actual;
        try (InputStream in = Files.newInputStream(DIRECTORY.resolve(name + ".afx"))) {
            actual = entries(in);
        }
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()), hint);
        assertEquals(new String(expected.get("animation.json"), StandardCharsets.UTF_8),
                new String(actual.get("animation.json"), StandardCharsets.UTF_8), hint);
        for (String entry : expected.keySet()) {
            assertArrayEquals(expected.get(entry), actual.get(entry), hint + ": " + entry);
        }
    }

    /** The uncompressed content of every entry of a zip archive, in the order of the archive. */
    private static Map<String, byte[]> entries(InputStream archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(archive)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    @ParameterizedTest
    @MethodSource("names")
    void theApplicationCarriesTheSampleAsAResource(String name) throws IOException {
        // the resources were copied before this run rewrote the files
        assumeFalse(WRITE, "the samples are being regenerated");
        AnimationFile resource = Samples.load(name);
        assertEquals(committed(name), resource.animation());
        assertEquals(SampleGenerator.files().get(name).assets().keySet(), resource.assets().keySet());
    }

    @Test
    void theBrowserShowsTheShowcasesButNotTheDedicatedDemos() {
        Set<String> all = SampleGenerator.all().keySet();
        assertEquals(all.stream().filter(name -> !name.equals(Samples.ATTENTION)
                && !name.equals(Samples.LIVE_ELEMENTS)).toList(), Samples.SHOWCASE);
        assertTrue(all.contains(Samples.ATTENTION));
        assertTrue(all.contains(Samples.LIVE_ELEMENTS));
    }

    @ParameterizedTest
    @MethodSource("names")
    void everyFrameCanBeSampled(String name) throws IOException {
        Animation animation = committed(name);
        assertEquals(0, animation.background().a(), "samples are transparent");
        int layers = Layers.flatten(animation).size();
        for (int i = 0; i <= 60; i++) {
            Frame frame = Evaluator.sample(animation, animation.duration() * i / 60);
            assertEquals(layers, frame.layers().size());
            for (LayerState state : frame.layers().values()) {
                for (double value : state.matrix()) {
                    assertTrue(Double.isFinite(value));
                }
                assertTrue(state.opacity() >= 0 && state.opacity() <= 1);
            }
        }
    }

    @Test
    void theLoaderIsATrimmedArcWithRoundCapsThatLoopsSeamlessly() throws IOException {
        Animation loader = committed("loader");
        ShapeLayer arc = shape(loader, "loader-arc");
        assertTrue(arc.trim().start().isAnimated());
        assertTrue(arc.trim().end().isAnimated());
        assertTrue(arc.trim().offset().isAnimated());
        assertEquals(Stroke.Cap.ROUND, arc.stroke().cap());

        PathData first = Evaluator.sample(loader, 0).get("loader-arc").strokePath();
        PathData last = Evaluator.sample(loader, loader.duration()).get("loader-arc").strokePath();
        assertClose(ends(first), ends(last));
        PathData middle = Evaluator.sample(loader, loader.duration() / 2).get("loader-arc").strokePath();
        assertNotEquals(ends(first).get(1), ends(middle).get(1));
    }

    @Test
    void successHasBothMarkersAnOvershootingBadgeAndACheckThatDrawsOn() throws IOException {
        Animation success = committed("success");
        assertEquals(List.of("intro", "success"), success.markers().stream().map(Marker::name).toList());
        assertEquals(success.marker("intro").orElseThrow().end(), success.marker("success").orElseThrow().start());
        assertEquals(success.duration(), success.marker("success").orElseThrow().end());

        double largest = 0;
        for (int i = 0; i <= 55; i++) {
            largest = Math.max(largest, Evaluator.sample(success, i / 100.0).get("success-badge").scale().x());
        }
        assertTrue(largest > 1.05, "the badge overshoots, but its largest scale was " + largest);

        ShapeLayer check = shape(success, "success-check");
        assertTrue(check.trim().end().isAnimated());
        assertTrue(Evaluator.sample(success, 0.2).get("success-check").strokePath().contours().isEmpty());
        PathData drawn = Evaluator.sample(success, 1.3).get("success-check").strokePath();
        assertEquals(3, drawn.contours().get(0).vertices().size());

        // the loop of the settled state starts and ends alike
        Frame from = Evaluator.sample(success, 1.3);
        Frame to = Evaluator.sample(success, 2.6);
        assertEquals(from.get("success-badge").scale(), to.get("success-badge").scale());
        assertEquals(from.get("success-ring").opacity(), to.get("success-ring").opacity());
    }

    @Test
    void successPlaysAChimeAtItsMarkerAndASwellWithAVolumeFadeUnderTheIntro() throws IOException {
        AnimationFile file = AnimationIO.read(DIRECTORY.resolve("success.afx"));
        Animation success = file.animation();
        assertEquals(List.of("success-rise", "success-chime"), success.assets().stream().map(Asset::id).toList());
        assertTrue(success.assets().stream().allMatch(asset -> asset.kind() == Asset.Kind.AUDIO));

        assertEquals(1, success.cues().size());
        SoundCue chime = success.cues().get(0);
        assertEquals("success-chime", chime.assetId());
        assertEquals(success.marker("success").orElseThrow().start(), chime.time());
        assertEquals(1, chime.volume());
        assertFalse(chime.muted());
        assertEquals(0.6, seconds(file.assets().get("success-chime")), 1e-3);

        AudioLayer swell = assertInstanceOf(AudioLayer.class, Layers.find(success, "success-swell").orElseThrow());
        assertEquals("success-rise", swell.assetId());
        assertEquals(0, swell.base().inPoint());
        assertEquals(0.6, swell.base().outPoint());
        assertTrue(swell.base().outPoint() <= success.marker("intro").orElseThrow().end());
        assertFalse(swell.muted());
        // the volume fades in and out again
        assertTrue(swell.volume().isAnimated());
        assertEquals(0, swell.volume().at(0));
        assertEquals(0.8, swell.volume().at(0.3), 1e-9);
        assertEquals(0, swell.volume().at(0.6));
        LayerState early = Evaluator.sample(success, 0.1).get("success-swell");
        assertTrue(early.volume() > 0 && early.volume() < 0.8, "fading in: " + early.volume());
        // the sound is as long as the layer
        assertEquals(0.6, seconds(file.assets().get("success-rise")), 1e-3);
    }

    @Test
    void theOtherShowcaseSamplesAreSilent() throws IOException {
        for (String name : List.of("loader", "morph", "card", "orbit")) {
            AnimationFile file = AnimationIO.read(DIRECTORY.resolve(name + ".afx"));
            assertTrue(file.animation().cues().isEmpty(), name);
            assertTrue(file.animation().assets().isEmpty(), name);
            assertTrue(file.assets().isEmpty(), name);
            assertTrue(Layers.flatten(file.animation()).stream().noneMatch(AudioLayer.class::isInstance), name);
        }
    }

    /** Every sound is a WAV file that the JDK decodes: 16 bit mono, gentle, without a click at either end. */
    @Test
    void theSoundsAreGentleWavFiles() throws Exception {
        int sounds = 0;
        for (String name : SampleGenerator.all().keySet()) {
            AnimationFile file = AnimationIO.read(DIRECTORY.resolve(name + ".afx"));
            for (Asset asset : file.animation().assets()) {
                assertEquals(Asset.Kind.AUDIO, asset.kind());
                assertTrue(asset.path().endsWith(".wav"), asset.path());
                short[] samples = decode(file.assets().get(asset.id()));
                sounds++;
                int peak = 0;
                for (short sample : samples) {
                    peak = Math.max(peak, Math.abs(sample));
                }
                double decibels = 20 * Math.log10(peak / 32768.0);
                assertTrue(decibels <= -11.9 && decibels >= -20, asset.id() + " peaks at " + decibels + " dBFS");
                assertTrue(Math.abs(samples[0]) < 40, asset.id() + " starts at " + samples[0]);
                assertTrue(Math.abs(samples[samples.length - 1]) < 40,
                        asset.id() + " ends at " + samples[samples.length - 1]);
            }
        }
        assertEquals(3, sounds);
    }

    private static double seconds(byte[] wav) throws IOException {
        try {
            return (double) decode(wav).length / SampleGenerator.SAMPLE_RATE;
        } catch (UnsupportedAudioFileException e) {
            throw new IOException(e);
        }
    }

    private static short[] decode(byte[] wav) throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat format = audio.getFormat();
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
            assertEquals(SampleGenerator.SAMPLE_RATE, format.getSampleRate());
            assertEquals(16, format.getSampleSizeInBits());
            assertEquals(1, format.getChannels());
            assertFalse(format.isBigEndian());
            byte[] data = audio.readAllBytes();
            assertEquals(audio.getFrameLength() * 2, data.length);
            short[] samples = new short[data.length / 2];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) (data[2 * i] & 0xFF | data[2 * i + 1] << 8);
            }
            return samples;
        }
    }

    @Test
    void morphAnimatesAPathAndItsGradient() throws IOException {
        Animation morph = committed("morph");
        ShapeLayer icon = shape(morph, "morph-icon");
        Geometry.PathGeometry geometry = assertInstanceOf(Geometry.PathGeometry.class, icon.geometry());
        assertEquals(3, geometry.path().keyframes().stream().map(k -> k.value()).distinct().count());
        assertTrue(icon.fill().isAnimated());
        assertInstanceOf(Paint.LinearGradient.class, icon.fill().at(0));

        PathData play = Evaluator.sample(morph, 0).get("morph-icon").fillPath();
        PathData between = Evaluator.sample(morph, 0.85).get("morph-icon").fillPath();
        PathData pause = Evaluator.sample(morph, 1.4).get("morph-icon").fillPath();
        assertNotEquals(play, between);
        assertNotEquals(pause, between);
        assertEquals(play, Evaluator.sample(morph, morph.duration()).get("morph-icon").fillPath());
        assertNotEquals(icon.fill().at(0), icon.fill().at(0.85));
        assertNotEquals(icon.fill().at(1.4), icon.fill().at(0.85));
    }

    @Test
    void cardHasTwoSlotsAShadowAndAMaskedShine() throws IOException {
        Animation card = committed("card");
        assertEquals(List.of("title", "action"), Layers.slots(card).stream().map(SlotLayer::slotName).toList());
        for (SlotLayer slot : Layers.slots(card)) {
            assertTrue(slot.base().transform().position().isAnimated());
            assertTrue(slot.base().opacity().isAnimated());
            // the slot settles on its rest transform
            assertEquals(slot.restTransform().position().at(0), slot.base().transform().position().at(card.duration()));
        }
        assertTrue(Layers.find(card, "card-panel").orElseThrow().base().effects().stream()
                .anyMatch(Effect.DropShadow.class::isInstance));
        ShapeLayer shine = shape(card, "card-shine");
        assertEquals(new Mask("card-shine-mask", Mask.Mode.CLIP), shine.base().mask());
        assertInstanceOf(Paint.LinearGradient.class, shine.fill().at(0));
        assertTrue(shine.base().transform().position().isAnimated());
        // staggered: the title starts before the action
        assertTrue(Evaluator.sample(card, 0.5).get("card-title").opacity() > 0);
        assertEquals(0, Evaluator.sample(card, 0.5).get("card-action").opacity());
    }

    @Test
    void orbitMovesBodiesAlongOrientedMotionPathsAroundABlurredGlow() throws IOException {
        Animation orbit = committed("orbit");
        for (String id : List.of("orbit-outer-craft", "orbit-inner-craft")) {
            Layer craft = Layers.find(orbit, id).orElseThrow();
            assertNotNull(craft.base().motionPath());
            assertTrue(craft.base().motionPath().orientToPath());
            assertTrue(craft.base().motionPath().progress().isAnimated());
            LayerState start = Evaluator.sample(orbit, 0).get(id);
            LayerState quarter = Evaluator.sample(orbit, 1).get(id);
            LayerState end = Evaluator.sample(orbit, orbit.duration()).get(id);
            assertNotEquals(start.position(), quarter.position());
            assertNotEquals(start.rotation(), quarter.rotation());
            assertClose(List.of(start.position()), List.of(end.position()));
        }
        assertTrue(Layers.find(orbit, "orbit-glow").orElseThrow().base().effects().stream()
                .anyMatch(Effect.Blur.class::isInstance));
        // each trail ends at its craft
        for (String name : List.of("outer", "inner")) {
            for (double time : new double[]{0.3, 1.7, 3.1}) {
                Frame frame = Evaluator.sample(orbit, time);
                List<PathData.Contour> trail = frame.get("orbit-" + name + "-trail").strokePath().contours();
                List<PathData.Vertex> last = trail.get(trail.size() - 1).vertices();
                assertClose(List.of(frame.get("orbit-" + name + "-craft").position()),
                        List.of(last.get(last.size() - 1).point()));
            }
        }
    }

    @Test
    void attentionIsASingleSlotThatReturnsToRest() throws IOException {
        Animation attention = committed("attention");
        SlotLayer slot = Layers.slots(attention).get(0);
        assertEquals("target", slot.slotName());
        assertEquals(1, Layers.flatten(attention).size());
        // a tick the moment the wiggle starts
        assertEquals(1, attention.cues().size());
        assertEquals(0, attention.cues().get(0).time());
        assertEquals("attention-tick", attention.cues().get(0).assetId());
        assertEquals(List.of(new Asset("attention-tick", Asset.Kind.AUDIO, "assets/attention-tick.wav", "tick.wav")),
                attention.assets());
        LayerState moving = Evaluator.sample(attention, 0.1).get(slot.id());
        assertNotEquals(0, moving.rotation());
        assertNotEquals(Vec2.ONE, moving.scale());
        for (double time : new double[]{0, attention.duration()}) {
            LayerState rest = Evaluator.sample(attention, time).get(slot.id());
            assertEquals(0, rest.rotation());
            assertEquals(Vec2.ONE, rest.scale());
            assertEquals(slot.restTransform().position().at(0), rest.position());
        }
    }

    private static ShapeLayer shape(Animation animation, String id) {
        return assertInstanceOf(ShapeLayer.class, Layers.find(animation, id).orElseThrow());
    }

    /** The first and the last point of a path. */
    private static List<Vec2> ends(PathData path) {
        List<PathData.Vertex> first = path.contours().get(0).vertices();
        List<PathData.Vertex> last = path.contours().get(path.contours().size() - 1).vertices();
        return List.of(first.get(0).point(), last.get(last.size() - 1).point());
    }

    private static void assertClose(List<Vec2> expected, List<Vec2> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).x(), actual.get(i).x(), 1e-6);
            assertEquals(expected.get(i).y(), actual.get(i).y(), 1e-6);
        }
    }
}
