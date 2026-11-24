package com.dlsc.animatorfx.sample;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Animation;
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
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.model.io.AnimationIO;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Rebuild with SampleFilesTest and -Danimatorfx.winter.write=true. */
final class WinterTownGenerator {
    private static final double DURATION = 20;
    private static final Easing EASE = new Easing.CubicBezier(.42, 0, .58, 1);
    private int key;

    static void write(Path path) throws IOException {
        AnimationIO.write(create(), path);
    }

    static AnimationFile create() {
        WinterTownGenerator scene = new WinterTownGenerator();
        return new AnimationFile(scene.build(), Map.of());
    }

    private Animation build() {
        List<Layer> layers = new ArrayList<>();
        layers.add(rect("sky", "Evening sky", 0, 0, 1200, 800,
                gradient(0, 0, 0, 800, "#142A52", "#C77783")));
        layers.add(ellipse("moon-halo", "Moon halo", 868, 52, 200, 200, "#FDE6BA70",
                base("moon-halo", "Moon halo").withTransform(Transform.at(868, 52))
                        .withEffects(List.of(new Effect.Blur(Animatable.of(28.0))))));
        layers.add(ellipse("moon", "Moon", 900, 85, 125, 125, "#FDECC8"));
        for (int i = 0; i < 35; i++) {
            double x = 35 + (i * 197 % 1120), y = 28 + (i * 113 % 235);
            double peak = 4 + i % 9;
            LayerBase star = base("star-" + i, "Star " + (i + 1))
                    .withTransform(Transform.at(x, y))
                    .withOpacity(keys(key(0, .35, EASE), key(peak, 1.0, EASE),
                            key(peak + 4, .35, EASE), key(20, .35, Easing.LINEAR)));
            layers.add(ellipse("star-" + i, "Star " + (i + 1), x, y, 2 + i % 3, 2 + i % 3, "#FFF4DB", star));
        }
        layers.add(cloud());
        layers.add(polygon("mountains-back", "Distant mountains", "#6F829B",
                0, 415, 150, 200, 265, 348, 418, 155, 594, 365, 765, 175, 950, 374, 1085, 210, 1200, 365, 1200, 570, 0, 570));
        layers.add(polygon("peak-a", "Snowy peak left", "#D7E5ED", 105, 263, 150, 200, 205, 271, 174, 253, 155, 267, 137, 250));
        layers.add(polygon("peak-b", "Snowy peak middle", "#E6EDF0", 350, 240, 418, 155, 500, 254, 458, 230, 431, 246, 410, 221, 389, 242));
        layers.add(polygon("peak-c", "Snowy peak right", "#DCE7EC", 700, 250, 765, 175, 840, 257, 800, 236, 777, 250, 755, 228, 732, 249));
        layers.add(polygon("hills", "Foreground hills", "#A9C0CE",
                0, 445, 160, 370, 310, 460, 495, 360, 690, 462, 830, 375, 1030, 447, 1200, 355, 1200, 605, 0, 605));
        for (int i = 0; i < 14; i++) {
            double x = 10 + i * 91, y = 425 + (i % 3) * 13;
            tree(layers, "far-tree-" + i, x, y, .65 + (i % 3) * .1);
        }
        layers.add(rect("snow-field", "Snow field", 0, 515, 1200, 285,
                gradient(0, 0, 0, 285, "#E4F0F1", "#A8C6D1")));
        house(layers, "house-a", 95, 360, "#CE705B", "#F8C96F");
        house(layers, "house-b", 365, 392, "#DBAA75", "#F4D391");
        house(layers, "house-c", 865, 370, "#7C9F9F", "#F4D391");
        layers.add(rect("road", "Town road", 0, 624, 1200, 94, solid("#657987")));
        layers.add(rect("road-snow-top", "Road upper snow edge", 0, 619, 1200, 10, solid("#E8F4F4")));
        layers.add(rect("road-snow-bottom", "Road lower snow edge", 0, 715, 1200, 12, solid("#D4E7EA")));
        for (int i = 0; i < 12; i++) {
            layers.add(rect("road-mark-" + i, "Road marking " + i, i * 115, 666, 55, 4, solid("#E3E4D7")));
        }
        tree(layers, "front-tree-left", 0, 420, 1.15);
        tree(layers, "front-tree-right", 1120, 426, 1.05);
        layers.add(car());
        layers.add(character("character-1", "Milo", 447, 546, "#DA7852", "#406B86", 0));
        layers.add(character("character-2", "Fern", 610, 552, "#638F81", "#E6B766", 2));
        layers.add(character("character-3", "Pip", 760, 557, "#8A77A4", "#D98E85", 4));
        layers.add(rect("bank", "Front snowbank", 0, 748, 1200, 52, solid("#EEF6F4")));
        for (int i = 0; i < 65; i++) {
            double x = (i * 173 + 22) % 1200, y = (i * i * 41 + i * 73 + 17) % 690;
            double size = 3 + i % 5;
            LayerBase snow = base("snow-" + i, "Falling snow " + (i + 1))
                    .withTransform(Transform.at(x, y).withPosition(keys(
                            key(0, new Vec2(x, y), Easing.LINEAR),
                            key(19, new Vec2(x + 75, y + 110), Easing.HOLD),
                            key(19.9, new Vec2(x, y), Easing.LINEAR),
                            key(20, new Vec2(x, y), Easing.LINEAR))))
                    .withOpacity(keys(key(0, 0.0, Easing.LINEAR), key(1, .8, Easing.LINEAR),
                            key(18, .8, Easing.LINEAR), key(19, 0.0, Easing.LINEAR),
                            key(20, 0.0, Easing.LINEAR)));
            layers.add(ellipse("snow-" + i, "Falling snow " + (i + 1), x, y, size, size, "#FFFFFF", snow));
        }
        return Animation.create("Winter Town", 1200, 800, DURATION)
                .withMarkers(List.of(new Marker("town", 0, 8), new Marker("passing car", 8, 15),
                        new Marker("snowfall", 15, 20)))
                .withLayers(layers);
    }

    private void house(List<Layer> layers, String id, double x, double y, String wall, String window) {
        List<Layer> parts = new ArrayList<>();
        parts.add(rect(id + "-wall", "House walls", 0, 70, 180, 125, solid(wall)));
        parts.add(polygon(id + "-roof", "Roof", "#624B52", -12, 72, 90, 0, 192, 72));
        parts.add(polygon(id + "-roof-snow", "Roof snow", "#F1F7F1", -12, 72, 90, 0, 192, 72, 180, 76, 90, 15, 0, 76));
        parts.add(rect(id + "-chimney", "Chimney", 135, 14, 23, 45, solid("#74565A")));
        parts.add(rect(id + "-door", "Door", 74, 120, 35, 75, solid("#565365")));
        parts.add(ellipse(id + "-knob", "Door knob", 100, 160, 4, 4, "#F4D790"));
        for (int i = 0; i < 2; i++) {
            double wx = 20 + i * 120;
            parts.add(rect(id + "-window-" + i, "Window", wx, 100, 35, 37, solid(window)));
            parts.add(rect(id + "-window-v-" + i, "Window frame", wx + 16, 100, 3, 37, solid("#79665F")));
            parts.add(rect(id + "-window-h-" + i, "Window frame", wx, 117, 35, 3, solid("#79665F")));
        }
        if (id.equals("house-a")) {
            parts.add(rect(id + "-shine-mask", "Window shine mask", 20, 100, 35, 37, solid("#FFFFFF")));
            LayerBase shine = base(id + "-shine", "Moving window reflection")
                    .withTransform(Transform.at(-20, 100).withPosition(keys(
                            key(0, new Vec2(-20, 100), Easing.LINEAR),
                            key(5, new Vec2(75, 100), Easing.HOLD),
                            key(19.9, new Vec2(-20, 100), Easing.LINEAR),
                            key(20, new Vec2(-20, 100), Easing.LINEAR))))
                    .withMask(new Mask("winter-" + id + "-shine-mask", Mask.Mode.CLIP));
            parts.add(new ShapeLayer(shine, new Geometry.Rect(Animatable.of(new Vec2(14, 37)), Animatable.of(0.0)),
                    Animatable.of(solid("#FFFFFF88")), null, null));
        }
        layers.add(new GroupLayer(base(id, "House").withTransform(Transform.at(x, y)), parts));
    }

    private Layer cloud() {
        PathData route = new PathData(List.of(new PathData.Contour(List.of(
                vertex(100, 120), vertex(260, 110), vertex(360, 140), vertex(200, 150)), true)));
        MotionPath motion = new MotionPath(route, keys(key(0, 0.0, Easing.LINEAR),
                key(20, 1.0, Easing.LINEAR)), false);
        List<Layer> parts = List.of(
                ellipse("cloud-a", "Cloud puff", 0, 15, 85, 32, "#FFFFFF66"),
                ellipse("cloud-b", "Cloud puff", 25, 0, 65, 50, "#FFFFFF66"),
                ellipse("cloud-c", "Cloud puff", 65, 15, 70, 32, "#FFFFFF66"));
        return new GroupLayer(base("cloud", "Drifting cloud").withMotionPath(motion), parts);
    }

    private void tree(List<Layer> layers, String id, double x, double y, double scale) {
        List<Layer> parts = List.of(
                rect(id + "-trunk", "Trunk", 39, 130, 20, 35, solid("#765E53")),
                polygon(id + "-lower", "Lower foliage", "#315C58", 0, 145, 49, 48, 98, 145),
                polygon(id + "-middle", "Middle foliage", "#3B7168", 8, 105, 49, 25, 90, 105),
                polygon(id + "-upper", "Upper foliage", "#4D8378", 19, 65, 49, 0, 79, 65),
                polygon(id + "-snow", "Snow on tree", "#E4F1EB", 19, 65, 49, 0, 79, 65, 60, 54, 49, 40, 37, 56));
        layers.add(new GroupLayer(base(id, "Pine tree").withTransform(Transform.at(x, y)
                .withScale(Animatable.of(new Vec2(scale, scale)))), parts));
    }

    private Layer car() {
        List<Layer> parts = new ArrayList<>();
        parts.add(rect("car-body", "Car body", 0, 25, 155, 38, solid("#E5B353")));
        parts.add(polygon("car-roof", "Car roof", "#E5B353", 28, 25, 50, 0, 112, 0, 134, 25));
        parts.add(polygon("car-window", "Car window", "#9FC7D2", 52, 4, 108, 4, 124, 23, 36, 23));
        parts.add(rect("car-window-divider", "Window divider", 81, 3, 4, 22, solid("#6F8886")));
        parts.add(ellipse("car-wheel-a", "Rear wheel", 21, 49, 28, 28, "#344750"));
        parts.add(ellipse("car-wheel-b", "Front wheel", 111, 49, 28, 28, "#344750"));
        parts.add(ellipse("car-hub-a", "Rear hub", 29, 57, 12, 12, "#C2D3D0"));
        parts.add(ellipse("car-hub-b", "Front hub", 119, 57, 12, 12, "#C2D3D0"));
        parts.add(rect("car-headlight", "Headlight", 145, 32, 10, 9, solid("#FFF2BE")));
        Transform moving = Transform.at(-170, 628).withPosition(keys(
                key(0, new Vec2(-170, 628), Easing.LINEAR), key(12, new Vec2(1370, 628), Easing.HOLD),
                key(12.01, new Vec2(-170, 628), Easing.LINEAR), key(20, new Vec2(-170, 628), Easing.LINEAR)));
        return new GroupLayer(base("car", "Passing car").withTransform(moving), parts);
    }

    private Layer character(String id, String label, double x, double y, String coat, String hat, double phase) {
        List<Layer> parts = new ArrayList<>();
        parts.add(ellipse(id + "-shadow", "Shadow", 6, 166, 100, 19, "#44627650"));
        parts.add(rect(id + "-leg-a", "Left boot", 25, 145, 25, 28, solid("#394453")));
        parts.add(rect(id + "-leg-b", "Right boot", 61, 145, 25, 28, solid("#394453")));
        parts.add(ellipse(id + "-body", "Coat", 13, 69, 84, 95, coat));
        parts.add(arm(id + "-arm-a", "Left arm", 1, 84, coat, hat, phase, -1));
        parts.add(arm(id + "-arm-b", "Right arm", 85, 84, coat, hat, phase, 1));
        parts.add(ellipse(id + "-head", "Face", 19, 18, 74, 74, "#EFC3A5"));
        parts.add(ellipse(id + "-hat", "Hat", 17, 7, 78, 42, hat));
        parts.add(rect(id + "-hat-brim", "Hat brim", 17, 33, 78, 12, solid(hat)));
        parts.add(ellipse(id + "-pompom", "Hat pompom", 46, 0, 20, 20, "#F2D59B"));
        parts.add(eye(id + "-eye-a", 38, phase));
        parts.add(eye(id + "-eye-b", 66, phase));
        parts.add(ellipse(id + "-cheek-a", "Left cheek", 27, 62, 12, 6, "#DC8D8270"));
        parts.add(ellipse(id + "-cheek-b", "Right cheek", 73, 62, 12, 6, "#DC8D8270"));
        parts.add(rect(id + "-scarf", "Scarf", 25, 78, 62, 13, solid(hat)));
        parts.add(rect(id + "-scarf-end", "Scarf tail", 62, 86, 16, 38, solid(hat)));
        for (int i = 0; i < 3; i++) {
            parts.add(ellipse(id + "-button-" + i, "Coat button", 51, 104 + i * 15, 6, 6, "#EBDDB9"));
        }
        Transform bouncing = Transform.at(x, y).withPosition(keys(
                key(0, new Vec2(x, y), EASE), key(5 + phase, new Vec2(x + 6, y - 9 - phase), EASE),
                key(10, new Vec2(x + 12, y - 5), EASE),
                key(15 + phase / 2, new Vec2(x + 5, y - 12), EASE), key(20, new Vec2(x, y), Easing.LINEAR)));
        return new GroupLayer(base(id, label).withTransform(bouncing), parts);
    }

    private Layer arm(String id, String label, double x, double y, String coat, String mitten,
                      double phase, int direction) {
        Transform moving = Transform.at(x + 12, y + 8).withAnchor(Animatable.of(new Vec2(12, 8)))
                .withRotation(keys(key(0, 0.0, EASE), key(5 + phase, direction * 24.0, EASE),
                        key(10, direction * -8.0, EASE), key(15 + phase / 2, direction * 18.0, EASE),
                        key(20, 0.0, Easing.LINEAR)));
        return new GroupLayer(base(id, label).withTransform(moving), List.of(
                ellipse(id + "-sleeve", "Sleeve", 0, 0, 25, 62, coat),
                ellipse(id + "-mitten", "Mitten", 1, 46, 23, 23, mitten)));
    }

    private Layer eye(String id, double x, double phase) {
        Transform blink = Transform.at(x, 48).withScale(keys(
                key(0, Vec2.ONE, Easing.LINEAR), key(2 + phase, Vec2.ONE, Easing.LINEAR),
                key(2.12 + phase, new Vec2(1, .08), Easing.LINEAR),
                key(2.24 + phase, Vec2.ONE, Easing.LINEAR),
                key(12 + phase, Vec2.ONE, Easing.LINEAR),
                key(12.12 + phase, new Vec2(1, .08), Easing.LINEAR),
                key(12.24 + phase, Vec2.ONE, Easing.LINEAR), key(20, Vec2.ONE, Easing.LINEAR)));
        return ellipse(id, "Eye", x, 48, 7, 9, "#34444A", base(id, "Eye").withTransform(blink));
    }

    private Layer rect(String id, String name, double x, double y, double w, double h, Paint paint) {
        return new ShapeLayer(base(id, name).withTransform(Transform.at(x, y)),
                new Geometry.Rect(Animatable.of(new Vec2(w, h)), Animatable.of(0.0)), Animatable.of(paint), null, null);
    }

    private Layer ellipse(String id, String name, double x, double y, double w, double h, String colour) {
        return ellipse(id, name, x, y, w, h, colour, base(id, name).withTransform(Transform.at(x, y)));
    }

    private Layer ellipse(String id, String name, double x, double y, double w, double h, String colour, LayerBase base) {
        return new ShapeLayer(base, new Geometry.Ellipse(Animatable.of(new Vec2(w, h))),
                Animatable.of(solid(colour)), null, null);
    }

    private Layer polygon(String id, String name, String colour, double... coordinates) {
        List<PathData.Vertex> vertices = new ArrayList<>();
        for (int i = 0; i < coordinates.length; i += 2) {
            vertices.add(vertex(coordinates[i], coordinates[i + 1]));
        }
        PathData path = new PathData(List.of(new PathData.Contour(vertices, true)));
        return new ShapeLayer(base(id, name), new Geometry.PathGeometry(Animatable.of(path)),
                Animatable.of(solid(colour)), null, null);
    }

    private static PathData.Vertex vertex(double x, double y) {
        return new PathData.Vertex(new Vec2(x, y), Vec2.ZERO, Vec2.ZERO);
    }

    private LayerBase base(String id, String name) {
        return LayerBase.create(name, DURATION).withId("winter-" + id);
    }

    private <T> Keyframe<T> key(double time, T value, Easing easing) {
        return new Keyframe<>("winter-key-" + ++key, time, value, easing);
    }

    @SafeVarargs
    private static <T> Animatable<T> keys(Keyframe<T>... values) {
        return Animatable.keyframed(List.of(values));
    }

    private static Paint solid(String colour) {
        return new Paint.Solid(Rgba.parse(colour));
    }

    private static Paint gradient(double x1, double y1, double x2, double y2, String a, String b) {
        return new Paint.LinearGradient(new Vec2(x1, y1), new Vec2(x2, y2),
                List.of(new Paint.Stop(0, Rgba.parse(a)), new Paint.Stop(1, Rgba.parse(b))));
    }
}
