package com.dlsc.animatorfx.sample;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.Asset;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.Marker;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.MotionPath;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.PathData;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.SoundCue;
import com.dlsc.animatorfx.model.StateMachine;
import com.dlsc.animatorfx.model.Stroke;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Trim;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.model.io.AnimationIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the sample animations of {@code samples/animations} with the model API, so that they are
 * always valid for the current format. Layer and keyframe ids are fixed, which makes the output
 * reproducible: {@link SampleFilesTest} checks that the committed files match. The sounds are
 * synthesised here as well (sine partials, written as 16 bit mono WAV), so the samples ship no
 * audio from anywhere else.
 * <p>
 * To write the files again after a change:
 * {@code mvn -pl samples/animatorfx-sample-app test -Dtest=SampleFilesTest -Danimatorfx.samples.write=true}
 */
final class SampleGenerator {

    private static final Easing EASE_OUT = new Easing.CubicBezier(0.33, 1, 0.68, 1);
    private static final Easing EASE_IN_OUT = new Easing.CubicBezier(0.65, 0, 0.35, 1);
    private static final Easing SINE_IN_OUT = new Easing.CubicBezier(0.37, 0, 0.63, 1);
    private static final Easing BACK_OUT = new Easing.CubicBezier(0.34, 1.56, 0.64, 1);
    private static final Easing SOFT_BACK_OUT = new Easing.CubicBezier(0.34, 1.3, 0.64, 1);

    private static final String INDIGO = "#6366F1";
    private static final String VIOLET = "#8B5CF6";
    private static final String CYAN = "#22D3EE";
    private static final String EMERALD = "#34D399";
    private static final String AMBER = "#F59E0B";
    private static final String PINK = "#F472B6";
    private static final String WHITE = "#FFFFFF";

    /** Handle length of a cubic approximating a quarter circle of radius 1. */
    private static final double KAPPA = 0.5522847498;

    /** Samples per second of the generated sounds. */
    static final int SAMPLE_RATE = 22_050;

    private final String name;
    private final double duration;
    private final List<Asset> assets = new ArrayList<>();
    private final Map<String, byte[]> sounds = new LinkedHashMap<>();
    private int keys;

    private SampleGenerator(String name, double duration) {
        this.name = name;
        this.duration = duration;
    }

    public static void main(String[] args) throws IOException {
        writeAll(Path.of(args.length > 0 ? args[0] : "samples/animations"));
    }

    /** The samples by file name without extension, in display order. */
    static Map<String, Animation> all() {
        Map<String, Animation> samples = new LinkedHashMap<>();
        files().forEach((name, file) -> samples.put(name, file.animation()));
        return samples;
    }

    /** The samples with the content of their assets, which are the generated sounds. */
    static Map<String, AnimationFile> files() {
        Map<String, AnimationFile> samples = new LinkedHashMap<>();
        samples.put("loader", new SampleGenerator("loader", 1.8).file(SampleGenerator::loader));
        samples.put("success", new SampleGenerator("success", 2.6).file(SampleGenerator::success));
        samples.put("morph", new SampleGenerator("morph", 3.6).file(SampleGenerator::morph));
        samples.put("card", new SampleGenerator("card", 2.6).file(SampleGenerator::card));
        samples.put("orbit", new SampleGenerator("orbit", 4).file(SampleGenerator::orbit));
        samples.put("attention", new SampleGenerator("attention", 0.8).file(SampleGenerator::attention));
        samples.put("live-elements", new SampleGenerator("live-elements", 2).file(SampleGenerator::liveElements));
        AnimationFile success = samples.get("success");
        StateMachine machine = new StateMachine("interaction", "idle",
                List.of(new StateMachine.State("idle", "success", true, 50, 50),
                        new StateMachine.State("working", "intro", false, 300, 50),
                        new StateMachine.State("done", "success", true, 175, 230)),
                List.of(new StateMachine.Transition("idle", "working", "start"),
                        new StateMachine.Transition("working", "idle", "cancel"),
                        new StateMachine.Transition("working", "done", null),
                        new StateMachine.Transition("done", "idle", "reset")));
        samples.put("state-machine", new AnimationFile(
                success.animation().withName("state-machine").withStateMachines(List.of(machine)), success.assets()));
        samples.put("download-workflow", new SampleGenerator("download", 15.4).file(SampleGenerator::downloadWorkflow));
        return samples;
    }

    private Animation downloadWorkflow() {
        List<Marker> markers = List.of(new Marker("ready", 0, 1.2),
                new Marker("connecting", 1.2, 3.8), new Marker("downloading", 3.8, 11.8),
                new Marker("paused", 11.8, 13), new Marker("complete", 13, 14.2),
                new Marker("failed", 14.2, 15.4));
        StateMachine machine = new StateMachine("download", "ready",
                List.of(new StateMachine.State("ready", "ready", true, 50, 60),
                        new StateMachine.State("connecting", "connecting", false, 300, 60),
                        new StateMachine.State("downloading", "downloading", false, 550, 60),
                        new StateMachine.State("paused", "paused", true, 550, 260),
                        new StateMachine.State("complete", "complete", true, 800, 60),
                        new StateMachine.State("failed", "failed", true, 300, 260)),
                List.of(new StateMachine.Transition("ready", "connecting", "start"),
                        new StateMachine.Transition("connecting", "downloading", null),
                        new StateMachine.Transition("connecting", "failed", "fail"),
                        new StateMachine.Transition("connecting", "ready", "reset"),
                        new StateMachine.Transition("downloading", "complete", null),
                        new StateMachine.Transition("downloading", "paused", "pause"),
                        new StateMachine.Transition("downloading", "failed", "fail"),
                        new StateMachine.Transition("downloading", "ready", "reset"),
                        new StateMachine.Transition("paused", "downloading", "resume"),
                        new StateMachine.Transition("paused", "failed", "fail"),
                        new StateMachine.Transition("paused", "ready", "reset"),
                        new StateMachine.Transition("failed", "connecting", "retry"),
                        new StateMachine.Transition("failed", "ready", "reset"),
                        new StateMachine.Transition("complete", "ready", "reset")));

        List<Layer> layers = new ArrayList<>();
        layers.add(new ShapeLayer(base("background", "Background"),
                new Geometry.Rect(Animatable.of(new Vec2(600, 360)), Animatable.of(24.0)),
                Animatable.of(solid("#0F172A")), null, null));
        layers.add(downloadText("heading", "FILE TRANSFER", 300, 28, 14, "#94A3B8", 0, duration));
        String[] names = {"ready", "connecting", "downloading", "paused", "complete", "failed"};
        String[] titles = {"Ready", "Connecting", "Downloading", "Paused", "Complete", "Failed"};
        String[] captions = {"Press Start to begin", "Establishing a connection...",
                "Transferring your file", "Resume restarts the transfer", "Your file is ready",
                "The connection was interrupted"};
        String[] colours = {"#94A3B8", "#38BDF8", "#60A5FA", "#FBBF24", "#34D399", "#FB7185"};
        for (int i = 0; i < names.length; i++) {
            Marker marker = markers.get(i);
            String name = names[i];
            String colour = colours[i];
            LayerBase circle = downloadBase(name + "-badge", "State badge", marker)
                    .withTransform(Transform.at(250, 70));
            layers.add(new ShapeLayer(circle, new Geometry.Ellipse(Animatable.of(new Vec2(100, 100))),
                    Animatable.of(solid(colour + "33")), null, null));
            layers.add(downloadText(name + "-title", titles[i], 300, 185, 34, colour, marker.start(), marker.end()));
            layers.add(downloadText(name + "-caption", captions[i], 300, 230, 16, "#CBD5E1",
                    marker.start(), marker.end()));
        }

        layers.add(downloadLine("ready-arrow", markers.get(0), "#94A3B8",
                300, 91, 300, 139, 285, 124, 300, 139, 315, 124));
        layers.add(downloadLine("ready-tray", markers.get(0), "#94A3B8",
                280, 146, 320, 146));
        Marker connecting = markers.get(1);
        Transform spinner = centred(28, 28, 300, 120).withRotation(keys(
                key(connecting.start(), 0.0, Easing.LINEAR), key(connecting.end(), 720.0, Easing.LINEAR)));
        layers.add(new ShapeLayer(downloadBase("spinner", "Connecting spinner", connecting).withTransform(spinner),
                new Geometry.Ellipse(Animatable.of(new Vec2(56, 56))), none(),
                stroke(Animatable.of(solid("#38BDF8")), 7, Stroke.Cap.ROUND),
                new Trim(Animatable.of(0.0), Animatable.of(0.72), Animatable.of(0.0))));
        Marker downloading = markers.get(2);
        layers.add(downloadLine("downloading-arrow", downloading, "#60A5FA",
                300, 91, 300, 139, 285, 124, 300, 139, 315, 124));
        layers.add(new ShapeLayer(downloadBase("progress-track", "Progress track", downloading)
                .withTransform(Transform.at(100, 278)),
                new Geometry.Rect(Animatable.of(new Vec2(400, 14)), Animatable.of(7.0)),
                Animatable.of(solid("#334155")), null, null));
        Transform progress = Transform.at(100, 278).withScale(keys(
                key(downloading.start(), new Vec2(0.02, 1), Easing.LINEAR),
                key(downloading.end(), Vec2.ONE, Easing.LINEAR)));
        layers.add(new ShapeLayer(downloadBase("progress", "Progress", downloading).withTransform(progress),
                new Geometry.Rect(Animatable.of(new Vec2(400, 14)), Animatable.of(7.0)),
                Animatable.of(solid("#60A5FA")), null, null));
        Marker paused = markers.get(3);
        layers.add(downloadLine("paused-bars", paused, "#FBBF24", 289, 97, 289, 143));
        layers.add(downloadLine("paused-bars-2", paused, "#FBBF24", 311, 97, 311, 143));
        layers.add(downloadLine("complete-check", markers.get(4), "#34D399", 278, 120, 293, 135, 323, 103));
        layers.add(downloadLine("failed-cross", markers.get(5), "#FB7185", 283, 103, 317, 137));
        layers.add(downloadLine("failed-cross-2", markers.get(5), "#FB7185", 317, 103, 283, 137));
        return document(600, 360, markers, layers.toArray(Layer[]::new)).withStateMachines(List.of(machine));
    }

    private LayerBase downloadBase(String id, String label, Marker marker) {
        return base(id, label).withInPoint(marker.start()).withOutPoint(marker.end());
    }

    private Layer downloadText(String id, String text, double x, double y, double size, String colour,
                               double start, double end) {
        return new TextLayer(base(id, text).withInPoint(start).withOutPoint(end)
                .withTransform(Transform.at(x, y)), text, "System", null, Animatable.of(size),
                TextLayer.Weight.BOLD, false, TextLayer.Align.CENTER, Animatable.of(solid(colour)), null);
    }

    private Layer downloadLine(String id, Marker marker, String colour, double... points) {
        List<PathData.Vertex> vertices = new ArrayList<>();
        for (int i = 0; i < points.length; i += 2) {
            vertices.add(corner(points[i], points[i + 1]));
        }
        PathData path = new PathData(List.of(new PathData.Contour(vertices, false)));
        Stroke line = new Stroke(Animatable.of(solid(colour)), Animatable.of(7.0), Stroke.Cap.ROUND,
                Stroke.Join.ROUND, 10, List.of(), Animatable.of(0.0));
        return new ShapeLayer(downloadBase(id, id, marker),
                new Geometry.PathGeometry(Animatable.of(path)), none(), line, null);
    }

    static void writeAll(Path directory) throws IOException {
        Files.createDirectories(directory);
        for (Map.Entry<String, AnimationFile> sample : files().entrySet()) {
            AnimationIO.write(sample.getValue(), directory.resolve(sample.getKey() + ".afx"));
        }
    }

    private AnimationFile file(Function<SampleGenerator, Animation> build) {
        Animation animation = build.apply(this);
        return new AnimationFile(animation.withAssets(assets), sounds);
    }

    /** A spinner: the arc grows and shrinks while it travels, and ends exactly where it began. */
    private Animation loader() {
        Transform centred = centred(60, 60, 100, 100);
        Geometry circle = new Geometry.Ellipse(Animatable.of(new Vec2(120, 120)));
        Layer track = new ShapeLayer(base("track", "Track").withTransform(centred), circle, none(),
                stroke(Animatable.of(solid(INDIGO + "2E")), 12, Stroke.Cap.BUTT), null);
        // start 0.75 / end 0.78 shifted by 1.25 turns is the arc 0 / 0.03 of the first frame again
        Trim trim = new Trim(
                keys(key(0, 0.0, Easing.LINEAR), key(0.9, 0.0, EASE_IN_OUT), key(1.8, 0.75, Easing.LINEAR)),
                keys(key(0, 0.03, EASE_IN_OUT), key(0.9, 0.78, Easing.LINEAR), key(1.8, 0.78, Easing.LINEAR)),
                keys(key(0, 0.0, Easing.LINEAR), key(1.8, 1.25, Easing.LINEAR)));
        Layer arc = new ShapeLayer(base("arc", "Arc").withTransform(centred), circle, none(),
                stroke(Animatable.of(linear(0, 0, 120, 120, INDIGO, CYAN)), 12, Stroke.Cap.ROUND), trim);
        return document(200, 200, List.of(), track, arc);
    }

    /** A badge pops in, its check mark draws on and a ring pulses away; then the badge keeps breathing. */
    private Animation success() {
        Geometry circle = new Geometry.Ellipse(Animatable.of(new Vec2(140, 140)));
        LayerBase ringBase = base("ring", "Pulse ring")
                .withTransform(centred(70, 70, 120, 120).withScale(keys(
                        key(0, Vec2.ONE, Easing.LINEAR), key(0.45, Vec2.ONE, EASE_OUT),
                        key(1.2, new Vec2(1.5, 1.5), Easing.HOLD),
                        key(1.3, Vec2.ONE, EASE_OUT), key(2.4, new Vec2(1.5, 1.5), Easing.LINEAR))))
                .withOpacity(keys(
                        key(0, 0.0, Easing.HOLD), key(0.45, 0.7, EASE_OUT), key(1.2, 0.0, Easing.LINEAR),
                        key(1.3, 0.0, EASE_OUT), key(1.45, 0.6, EASE_OUT), key(2.4, 0.0, Easing.LINEAR)));
        Layer ring = new ShapeLayer(ringBase, circle, none(),
                stroke(Animatable.of(solid(EMERALD)), 6, Stroke.Cap.BUTT), null);

        LayerBase discBase = base("disc", "Disc").withEffects(List.of(new Effect.DropShadow(
                Animatable.of(Rgba.parse("#04785759")), Animatable.of(16.0), Animatable.of(0.0), Animatable.of(8.0),
                Animatable.of(0.0))));
        Layer disc = new ShapeLayer(discBase, circle,
                Animatable.of(linear(30, 0, 110, 140, EMERALD, "#059669")), null, null);

        PathData mark = new PathData(List.of(new PathData.Contour(
                List.of(corner(40, 72), corner(61, 93), corner(102, 50)), false)));
        Stroke white = new Stroke(Animatable.of(solid(WHITE)), Animatable.of(13.0), Stroke.Cap.ROUND,
                Stroke.Join.ROUND, 10, List.of(), Animatable.of(0.0));
        Trim draw = new Trim(Animatable.of(0.0),
                keys(key(0, 0.0, Easing.LINEAR), key(0.45, 0.0, EASE_IN_OUT), key(0.95, 1.0, Easing.LINEAR)),
                Animatable.of(0.0));
        Layer check = new ShapeLayer(base("check", "Check"), new Geometry.PathGeometry(Animatable.of(mark)),
                none(), white, draw);

        Vec2 breathe = new Vec2(1.05, 1.05);
        LayerBase badgeBase = base("badge", "Badge").withTransform(centred(70, 70, 120, 120).withScale(keys(
                key(0, Vec2.ZERO, BACK_OUT), key(0.55, Vec2.ONE, Easing.LINEAR),
                key(1.3, Vec2.ONE, SINE_IN_OUT), key(1.75, breathe, SINE_IN_OUT), key(2.2, Vec2.ONE, Easing.LINEAR))));
        Layer badge = new GroupLayer(badgeBase, List.of(disc, check));

        // a soft rising tone under the badge as it pops in, faded in and out by volume keyframes
        String rise = sound("rise", "rise.wav", rise());
        Layer swell = new AudioLayer(base("swell", "Swell").withOutPoint(0.6), rise, 0,
                keys(key(0, 0.0, EASE_OUT), key(0.2, 0.8, Easing.LINEAR), key(0.35, 0.8, EASE_IN_OUT),
                        key(0.6, 0.0, Easing.LINEAR)),
                Animatable.of(0.0), false, false);
        // the chime sounds the moment the settled state begins
        Marker settled = new Marker("success", 1.3, 2.6);
        SoundCue chime = new SoundCue(id("cue-chime"), "Chime", settled.start(), sound("chime", "chime.wav", chime()),
                1, false);

        return document(240, 240, List.of(new Marker("intro", 0, 1.3), settled), ring, badge, swell)
                .withCues(List.of(chime));
    }

    /** Play, pause and stop morph into each other; every form is two contours of four vertices. */
    private Animation morph() {
        PathData play = twoQuads(
                95, 70, 133, 95, 133, 145, 95, 170,
                133, 95, 171, 120, 171, 120, 133, 145);
        PathData pause = twoQuads(
                82, 72, 108, 72, 108, 168, 82, 168,
                132, 72, 158, 72, 158, 168, 132, 168);
        PathData stop = twoQuads(
                76, 76, 120, 76, 120, 164, 76, 164,
                120, 76, 164, 76, 164, 164, 120, 164);
        Animatable<PathData> path = keys(
                key(0, play, Easing.LINEAR), key(0.6, play, SOFT_BACK_OUT),
                key(1.1, pause, Easing.LINEAR), key(1.8, pause, SOFT_BACK_OUT),
                key(2.3, stop, Easing.LINEAR), key(3.0, stop, SOFT_BACK_OUT),
                key(3.5, play, Easing.LINEAR));

        String[][] colours = {{INDIGO, VIOLET}, {AMBER, "#F97316"}, {"#EC4899", "#F43F5E"}};
        Layer backdrop = new ShapeLayer(base("backdrop", "Backdrop").withTransform(centred(100, 100, 120, 120)),
                new Geometry.Ellipse(Animatable.of(new Vec2(200, 200))),
                phases(solid(colours[0][0] + "29"), solid(colours[1][0] + "29"), solid(colours[2][0] + "29")),
                stroke(phases(solid(colours[0][0] + "66"), solid(colours[1][0] + "66"), solid(colours[2][0] + "66")),
                        2, Stroke.Cap.BUTT), null);

        Paint[] gradients = new Paint[3];
        for (int i = 0; i < 3; i++) {
            gradients[i] = linear(76, 70, 171, 170, colours[i][0], colours[i][1]);
        }
        // the stroke in the fill's paint rounds the corners
        Stroke rounding = new Stroke(phases(gradients), Animatable.of(10.0), Stroke.Cap.ROUND, Stroke.Join.ROUND, 10,
                List.of(), Animatable.of(0.0));
        Layer icon = new ShapeLayer(base("icon", "Icon"), new Geometry.PathGeometry(path), phases(gradients),
                rounding, null);
        return document(240, 240, List.of(), backdrop, icon);
    }

    /** The same three phases and timing as the path keyframes of {@link #morph()}. */
    private Animatable<Paint> phases(Paint... phases) {
        return keys(
                key(0, phases[0], Easing.LINEAR), key(0.6, phases[0], EASE_IN_OUT),
                key(1.1, phases[1], Easing.LINEAR), key(1.8, phases[1], EASE_IN_OUT),
                key(2.3, phases[2], Easing.LINEAR), key(3.0, phases[2], EASE_IN_OUT),
                key(3.5, phases[0], Easing.LINEAR));
    }

    /** A card whose title and action are slots; a masked shine sweeps across the panel. */
    private Animation card() {
        Geometry panelShape = new Geometry.Rect(Animatable.of(new Vec2(300, 150)), Animatable.of(18.0));
        Transform panelPlace = centred(150, 75, 180, 105);
        LayerBase panelBase = base("panel", "Panel")
                .withTransform(panelPlace.withScale(keys(
                        key(0, new Vec2(0.92, 0.92), EASE_OUT), key(0.5, Vec2.ONE, Easing.LINEAR))))
                .withOpacity(keys(key(0, 0.0, EASE_OUT), key(0.4, 1.0, Easing.LINEAR)))
                .withEffects(List.of(new Effect.DropShadow(Animatable.of(Rgba.parse("#1E1B4B66")),
                        Animatable.of(22.0), Animatable.of(0.0), Animatable.of(10.0), Animatable.of(0.0))));
        Layer panel = new ShapeLayer(panelBase, panelShape,
                Animatable.of(linear(0, 0, 300, 150, "#4F46E5", "#7C3AED")), null, null);

        Layer iconDisc = new ShapeLayer(base("icon-disc", "Icon disc"),
                new Geometry.Ellipse(Animatable.of(new Vec2(36, 36))), Animatable.of(solid(WHITE + "38")), null,
                null);
        Layer iconStar = new ShapeLayer(base("icon-star", "Icon star").withTransform(Transform.at(8, 8)),
                new Geometry.Star(5, Animatable.of(10.0), Animatable.of(4.5)), Animatable.of(solid(WHITE)), null,
                null);
        Layer icon = new GroupLayer(base("icon", "Icon")
                .withTransform(centred(18, 18, 70, 68).withScale(keys(
                        key(0, Vec2.ZERO, Easing.LINEAR), key(0.3, Vec2.ZERO, BACK_OUT),
                        key(0.75, Vec2.ONE, Easing.LINEAR)))),
                List.of(iconDisc, iconStar));

        LayerBase subtitleBase = base("subtitle", "Subtitle").withTransform(Transform.at(98, 84))
                .withOpacity(keys(key(0, 0.0, Easing.LINEAR), key(0.45, 0.0, EASE_OUT), key(0.95, 1.0, Easing.LINEAR)));
        Layer subtitle = new TextLayer(subtitleBase, "Title and button are bound at runtime", "System", null,
                Animatable.of(12.0), TextLayer.Weight.NORMAL, false, TextLayer.Align.LEFT,
                Animatable.of(solid(WHITE + "B3")), null);

        Layer shineMask = new ShapeLayer(base("shine-mask", "Shine mask").withTransform(panelPlace), panelShape,
                Animatable.of(solid(WHITE)), null, null);
        LayerBase shineBase = base("shine", "Shine")
                .withTransform(new Transform(Animatable.of(new Vec2(30, 130)),
                        keys(key(0, new Vec2(-60, 105), Easing.LINEAR), key(0.8, new Vec2(-60, 105), EASE_IN_OUT),
                                key(1.6, new Vec2(420, 105), Easing.LINEAR)),
                        Animatable.of(Vec2.ONE), Animatable.of(22.0), Animatable.of(Vec2.ZERO)))
                .withMask(new Mask(id("shine-mask"), Mask.Mode.CLIP));
        Layer shine = new ShapeLayer(shineBase,
                new Geometry.Rect(Animatable.of(new Vec2(60, 260)), Animatable.of(0.0)),
                Animatable.of(new Paint.LinearGradient(new Vec2(0, 0), new Vec2(60, 0), List.of(
                        new Paint.Stop(0, Rgba.parse(WHITE + "00")), new Paint.Stop(0.5, Rgba.parse(WHITE + "59")),
                        new Paint.Stop(1, Rgba.parse(WHITE + "00"))))), null, null);

        Transform titleRest = Transform.at(98, 54);
        LayerBase titleBase = base("title", "Title slot")
                .withTransform(titleRest.withPosition(keys(
                        key(0, new Vec2(126, 54), Easing.LINEAR), key(0.3, new Vec2(126, 54), EASE_OUT),
                        key(0.8, new Vec2(98, 54), Easing.LINEAR))))
                .withOpacity(keys(key(0, 0.0, Easing.LINEAR), key(0.3, 0.0, EASE_OUT), key(0.8, 1.0, Easing.LINEAR)));
        Layer title = new SlotLayer(titleBase, "title", 212, 26, titleRest);

        Transform actionRest = Transform.at(206, 130);
        LayerBase actionBase = base("action", "Action slot")
                .withTransform(actionRest.withPosition(keys(
                        key(0, new Vec2(206, 148), Easing.LINEAR), key(0.55, new Vec2(206, 148), BACK_OUT),
                        key(1.05, new Vec2(206, 130), Easing.LINEAR))))
                .withOpacity(keys(key(0, 0.0, Easing.LINEAR), key(0.55, 0.0, EASE_OUT), key(0.95, 1.0, Easing.LINEAR)));
        Layer action = new SlotLayer(actionBase, "action", 104, 32, actionRest);

        return document(360, 220, List.of(new Marker("shine", 0.8, 1.6)),
                panel, icon, subtitle, shineMask, shine, title, action);
    }

    private Animation liveElements() {
        Layer label = new TextLayer(base("label", "Message").withExportName("label")
                .withTransform(Transform.at(70, 65)), "Click the circle", "System", null,
                Animatable.of(24.0), TextLayer.Weight.BOLD, false, TextLayer.Align.LEFT,
                Animatable.of(solid(WHITE)), null);
        Layer button = new ShapeLayer(base("button", "Clickable circle").withExportName("button")
                .withTransform(centred(55, 55, 160, 160).withScale(keys(
                        key(0, Vec2.ONE, SINE_IN_OUT), key(1, new Vec2(1.12, 1.12), SINE_IN_OUT),
                        key(2, Vec2.ONE, Easing.LINEAR)))),
                new Geometry.Ellipse(Animatable.of(new Vec2(110, 110))), Animatable.of(solid(INDIGO)), null, null);
        Layer sparkle = new ShapeLayer(base("sparkle", "Sparkle").withExportName("sparkle")
                .withTransform(Transform.at(300, 160).withRotation(keys(
                        key(0, 0.0, Easing.LINEAR), key(2, 72.0, Easing.LINEAR)))),
                new Geometry.Star(5, Animatable.of(38.0), Animatable.of(17.0)),
                Animatable.of(solid(AMBER)), null, null);
        return document(400, 250, List.of(), label, button, sparkle);
    }

    /** Two craft follow tilted orbits, nose first, each trailing a trimmed piece of its orbit, around a glowing core. */
    private Animation orbit() {
        List<Layer> layers = new ArrayList<>();
        LayerBase glowBase = base("glow", "Glow")
                .withTransform(centred(40, 40, 130, 130).withScale(keys(
                        key(0, Vec2.ONE, SINE_IN_OUT), key(2, new Vec2(1.15, 1.15), SINE_IN_OUT),
                        key(4, Vec2.ONE, Easing.LINEAR))))
                .withOpacity(keys(key(0, 0.45, SINE_IN_OUT), key(2, 0.8, SINE_IN_OUT), key(4, 0.45, Easing.LINEAR)))
                .withEffects(List.of(new Effect.Blur(Animatable.of(18.0))));
        layers.add(new ShapeLayer(glowBase, new Geometry.Ellipse(Animatable.of(new Vec2(80, 80))),
                Animatable.of(solid(AMBER + "CC")), null, null));

        PathData outer = ellipse(130, 130, 108, 46, -24, 1);
        PathData inner = ellipse(130, 130, 96, 34, 38, 1);
        layers.add(track("outer-track", "Outer orbit", outer));
        layers.add(track("inner-track", "Inner orbit", inner));
        layers.add(trail("outer-trail", "Outer trail", outer, CYAN, 0.2, 1));
        layers.add(trail("inner-trail", "Inner trail", inner, PINK, 0.16, 2));

        layers.add(new ShapeLayer(base("core", "Core").withTransform(centred(22, 22, 130, 130)),
                new Geometry.Ellipse(Animatable.of(new Vec2(44, 44))),
                Animatable.of(new Paint.RadialGradient(new Vec2(17, 15), 30, List.of(
                        new Paint.Stop(0, Rgba.parse("#FDE68A")), new Paint.Stop(1, Rgba.parse(AMBER))))), null,
                null));

        layers.add(craft("outer-craft", "Outer craft", outer, CYAN));
        // the inner craft goes round twice per loop: its motion path is two laps of the orbit
        layers.add(craft("inner-craft", "Inner craft", ellipse(130, 130, 96, 34, 38, 2), PINK));
        return document(260, 260, List.of(), layers.toArray(Layer[]::new));
    }

    private Layer track(String id, String label, PathData path) {
        return new ShapeLayer(base(id, label), new Geometry.PathGeometry(Animatable.of(path)), none(),
                stroke(Animatable.of(solid("#94A3B866")), 1.5, Stroke.Cap.BUTT), null);
    }

    private Layer trail(String id, String label, PathData lap, String colour, double length, int laps) {
        Trim trim = new Trim(Animatable.of(0.0), Animatable.of(length),
                keys(key(0, -length, Easing.LINEAR), key(duration, laps - length, Easing.LINEAR)));
        return new ShapeLayer(base(id, label), new Geometry.PathGeometry(Animatable.of(lap)), none(),
                stroke(Animatable.of(solid(colour + "8C")), 3, Stroke.Cap.ROUND), trim);
    }

    private Layer craft(String id, String label, PathData route, String colour) {
        PathData arrow = new PathData(List.of(new PathData.Contour(
                List.of(corner(11, 0), corner(-8, -7), corner(-4, 0), corner(-8, 7)), true)));
        MotionPath motion = new MotionPath(route,
                keys(key(0, 0.0, Easing.LINEAR), key(duration, 1.0, Easing.LINEAR)), true);
        Stroke rounding = new Stroke(Animatable.of(solid(colour)), Animatable.of(2.0), Stroke.Cap.ROUND,
                Stroke.Join.ROUND, 10, List.of(), Animatable.of(0.0));
        return new ShapeLayer(base(id, label).withMotionPath(motion),
                new Geometry.PathGeometry(Animatable.of(arrow)), Animatable.of(solid(colour)), rounding, null);
    }

    /** One slot that wiggles and swells; made for in-place binding of a node in the application's own layout. */
    private Animation attention() {
        Transform rest = centred(60, 18, 80, 40);
        Vec2 swell = new Vec2(1.1, 1.1);
        Transform wiggle = rest
                .withRotation(keys(
                        key(0, 0.0, SINE_IN_OUT), key(0.1, -7.0, SINE_IN_OUT), key(0.25, 6.0, SINE_IN_OUT),
                        key(0.4, -4.0, SINE_IN_OUT), key(0.55, 3.0, SINE_IN_OUT), key(0.7, 0.0, Easing.LINEAR)))
                .withScale(keys(
                        key(0, Vec2.ONE, EASE_OUT), key(0.2, swell, SINE_IN_OUT), key(0.7, Vec2.ONE, Easing.LINEAR)));
        Layer target = new SlotLayer(base("target", "Target slot").withTransform(wiggle), "target", 120, 36, rest);
        SoundCue tick = new SoundCue(id("cue-tick"), "Tick", 0, sound("tick", "tick.wav", tick()), 1, false);
        return document(160, 80, List.of(), target).withCues(List.of(tick));
    }

    /** Adds a sound to the document that is being built and returns the id of its asset. */
    private String sound(String localId, String fileName, double[] samples) {
        String id = id(localId);
        assets.add(new Asset(id, Asset.Kind.AUDIO, "assets/" + id + ".wav", fileName));
        sounds.put(id, wav(samples));
        return id;
    }

    /**
     * A bell-like chime of about 0.6 s: a note with its fifth and its octave, each dying away
     * faster than the one below it. Peak at -12 dBFS.
     */
    static double[] chime() {
        double[] samples = new double[(int) (0.6 * SAMPLE_RATE)];
        double[][] partials = {{880, 1, 6}, {1320, 0.5, 8}, {1760, 0.3, 11}};
        for (int i = 0; i < samples.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            for (double[] partial : partials) {
                samples[i] += partial[1] * StrictMath.sin(2 * StrictMath.PI * partial[0] * t)
                        * StrictMath.exp(-partial[2] * t);
            }
        }
        return shaped(samples, 0.004, 0.04, 0.25);
    }

    /** A soft tick of 0.08 s: one low partial that dies away at once. Peak at -15 dBFS. */
    static double[] tick() {
        double[] samples = new double[(int) (0.08 * SAMPLE_RATE)];
        for (int i = 0; i < samples.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            samples[i] = StrictMath.sin(2 * StrictMath.PI * 660 * t) * StrictMath.exp(-55 * t);
        }
        return shaped(samples, 0.002, 0.01, 0.18);
    }

    /** A tone of 0.6 s that glides up an octave, from 220 to 440 Hz. Peak at -14 dBFS. */
    static double[] rise() {
        double[] samples = new double[(int) (0.6 * SAMPLE_RATE)];
        double seconds = (double) samples.length / SAMPLE_RATE;
        for (int i = 0; i < samples.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            // the phase is the integral of the frequency 220 * 2^(t / seconds)
            double phase = 2 * StrictMath.PI * 220 * seconds / StrictMath.log(2)
                    * (StrictMath.pow(2, t / seconds) - 1);
            samples[i] = StrictMath.sin(phase) + 0.25 * StrictMath.sin(2 * phase);
        }
        return shaped(samples, 0.03, 0.05, 0.2);
    }

    /** Fades both ends linearly, so that the sound neither starts nor stops with a click, and sets the peak. */
    private static double[] shaped(double[] samples, double fadeIn, double fadeOut, double peak) {
        int in = (int) (fadeIn * SAMPLE_RATE);
        int out = (int) (fadeOut * SAMPLE_RATE);
        double largest = 0;
        for (int i = 0; i < samples.length; i++) {
            double gain = Math.min(1, Math.min((double) i / in, (double) (samples.length - 1 - i) / out));
            samples[i] *= gain;
            largest = Math.max(largest, Math.abs(samples[i]));
        }
        for (int i = 0; i < samples.length; i++) {
            samples[i] *= peak / largest;
        }
        return samples;
    }

    /** A WAV file: 16 bit, mono, {@link #SAMPLE_RATE}. */
    static byte[] wav(double[] samples) {
        int dataLength = samples.length * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLength);
        ascii(out, "RIFF");
        littleEndian(out, 36 + dataLength, 4);
        ascii(out, "WAVEfmt ");
        littleEndian(out, 16, 4);
        littleEndian(out, 1, 2);
        littleEndian(out, 1, 2);
        littleEndian(out, SAMPLE_RATE, 4);
        littleEndian(out, SAMPLE_RATE * 2, 4);
        littleEndian(out, 2, 2);
        littleEndian(out, 16, 2);
        ascii(out, "data");
        littleEndian(out, dataLength, 4);
        for (double sample : samples) {
            littleEndian(out, (int) Math.round(sample * 32767), 2);
        }
        return out.toByteArray();
    }

    private static void ascii(ByteArrayOutputStream out, String text) {
        for (int i = 0; i < text.length(); i++) {
            out.write(text.charAt(i));
        }
    }

    private static void littleEndian(ByteArrayOutputStream out, int value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write(value >> 8 * i);
        }
    }

    private Animation document(double width, double height, List<Marker> markers, Layer... layers) {
        return Animation.create(name, width, height, duration).withMarkers(markers).withLayers(List.of(layers));
    }

    private String id(String local) {
        return name + "-" + local;
    }

    private LayerBase base(String localId, String label) {
        return LayerBase.create(label, duration).withId(id(localId));
    }

    private <T> Keyframe<T> key(double time, T value, Easing easing) {
        return new Keyframe<>(name + "-k" + ++keys, time, value, easing);
    }

    @SafeVarargs
    private static <T> Animatable<T> keys(Keyframe<T>... keyframes) {
        return Animatable.keyframed(List.of(keyframes));
    }

    /** A transform that puts the layer point {@code (anchorX, anchorY)} on {@code (x, y)}. */
    private static Transform centred(double anchorX, double anchorY, double x, double y) {
        return Transform.at(x, y).withAnchor(Animatable.of(new Vec2(anchorX, anchorY)));
    }

    private static Animatable<Paint> none() {
        return Animatable.of(new Paint.None());
    }

    private static Paint solid(String hex) {
        return new Paint.Solid(Rgba.parse(hex));
    }

    private static Paint linear(double x1, double y1, double x2, double y2, String from, String to) {
        return new Paint.LinearGradient(new Vec2(x1, y1), new Vec2(x2, y2),
                List.of(new Paint.Stop(0, Rgba.parse(from)), new Paint.Stop(1, Rgba.parse(to))));
    }

    private static Stroke stroke(Animatable<Paint> paint, double width, Stroke.Cap cap) {
        return new Stroke(paint, Animatable.of(width), cap, Stroke.Join.MITER, 10, List.of(), Animatable.of(0.0));
    }

    private static PathData.Vertex corner(double x, double y) {
        return new PathData.Vertex(new Vec2(x, y), Vec2.ZERO, Vec2.ZERO);
    }

    /** Two closed four-corner contours, eight coordinates each. */
    private static PathData twoQuads(double... c) {
        List<PathData.Contour> contours = new ArrayList<>();
        for (int i = 0; i < 16; i += 8) {
            contours.add(new PathData.Contour(List.of(corner(c[i], c[i + 1]), corner(c[i + 2], c[i + 3]),
                    corner(c[i + 4], c[i + 5]), corner(c[i + 6], c[i + 7])), true));
        }
        return new PathData(contours);
    }

    /**
     * An ellipse around {@code (cx, cy)}, tilted clockwise by {@code tilt} degrees and running
     * clockwise. One lap is a closed contour; several laps are one open contour that ends where
     * it starts.
     */
    private static PathData ellipse(double cx, double cy, double rx, double ry, double tilt, int laps) {
        double cos = Math.cos(Math.toRadians(tilt));
        double sin = Math.sin(Math.toRadians(tilt));
        double[][] quarter = {
                {rx, 0, 0, ry * KAPPA}, {0, ry, -rx * KAPPA, 0}, {-rx, 0, 0, -ry * KAPPA}, {0, -ry, rx * KAPPA, 0}};
        List<PathData.Vertex> lap = new ArrayList<>();
        for (double[] q : quarter) {
            Vec2 out = new Vec2(round(q[2] * cos - q[3] * sin), round(q[2] * sin + q[3] * cos));
            lap.add(new PathData.Vertex(new Vec2(round(cx + q[0] * cos - q[1] * sin), round(cy + q[0] * sin + q[1] * cos)),
                    new Vec2(0.0 - out.x(), 0.0 - out.y()), out));
        }
        if (laps == 1) {
            return new PathData(List.of(new PathData.Contour(lap, true)));
        }
        List<PathData.Vertex> vertices = new ArrayList<>();
        for (int i = 0; i < laps; i++) {
            vertices.addAll(lap);
        }
        vertices.add(lap.get(0));
        return new PathData(List.of(new PathData.Contour(vertices, false)));
    }

    /** Keeps the JSON readable. */
    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
