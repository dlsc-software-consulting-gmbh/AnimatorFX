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
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.SlotLayer;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.AnimationFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Rebuild with SampleFilesTest and -Danimatorfx.login.write=true. */
final class LoginSceneGenerator {
    private static final double DURATION = 12;
    private static final Easing EASE_OUT = new Easing.CubicBezier(.16, 1, .3, 1);
    private static final Easing EASE = new Easing.CubicBezier(.42, 0, .58, 1);
    private int key;

    static AnimationFile create() {
        return new AnimationFile(new LoginSceneGenerator().build(), Map.of());
    }

    private Animation build() {
        List<Layer> layers = new ArrayList<>();
        layers.add(rect("background", "Background", 0, 0, 1200, 800, 0,
                gradient("#0D1738", "#342855")));
        layers.add(orb("orb", "Violet glow", 140, 80, 320, "#8B5CF666", 90, 45));
        layers.add(orb("orb-two", "Teal glow", 805, 350, 340, "#2DD4BF55", -75, -55));
        layers.add(orb("orb-three", "Rose glow", 720, -80, 240, "#F472B644", -50, 55));

        for (int i = 0; i < 32; i++) {
            double x = 26 + (i * 181 % 1140), y = 35 + (i * i * 31 + i * 67) % 720;
            double peak = 5 + i % 4;
            LayerBase star = base("dot-" + i, "Floating light")
                    .withTransform(Transform.at(x, y))
                    .withOpacity(keys(key(0, .2, EASE), key(4, .2, EASE), key(peak, .8, EASE),
                            key(12, .2, Easing.LINEAR)));
            layers.add(new ShapeLayer(star, new Geometry.Ellipse(Animatable.of(new Vec2(3 + i % 4, 3 + i % 4))),
                    Animatable.of(solid("#DAE7FF")), null, null));
        }

        layers.add(sideCard("left-card", "Welcome card", 62, 210, -320, true));
        layers.add(sideCard("right-card", "Security card", 917, 345, 1260, false));
        LayerBase panel = base("panel", "Login card")
                .withTransform(Transform.at(380, 100).withPosition(keys(
                        key(0, new Vec2(380, 160), EASE_OUT), key(1.6, new Vec2(380, 100), Easing.LINEAR))))
                .withOpacity(keys(key(0, 0.0, EASE_OUT), key(1.1, 1.0, Easing.LINEAR)))
                .withEffects(List.of(new Effect.DropShadow(Animatable.of(Rgba.parse("#080F2D99")),
                        Animatable.of(34.0), Animatable.of(0.0), Animatable.of(18.0), Animatable.of(.05))));
        layers.add(new ShapeLayer(panel, new Geometry.Rect(Animatable.of(new Vec2(440, 600)), Animatable.of(28.0)),
                Animatable.of(solid("#FCFCFF")), null, null));

        Transform rest = Transform.at(412, 126);
        LayerBase form = base("form", "Interactive form")
                .withTransform(rest.withPosition(keys(
                        key(0, new Vec2(412, 162), Easing.LINEAR), key(.6, new Vec2(412, 162), EASE_OUT),
                        key(2.1, new Vec2(412, 126), Easing.LINEAR))))
                .withOpacity(keys(key(0, 0.0, Easing.LINEAR), key(.6, 0.0, EASE_OUT),
                        key(1.7, 1.0, Easing.LINEAR)));
        layers.add(new SlotLayer(form, "login-form", 376, 548, rest));
        Animation animation = Animation.create("Animated login", 1200, 800, DURATION)
                .withMarkers(List.of(new Marker("intro", 0, 4), new Marker("ambient", 4, 12)))
                .withLayers(layers);
        return animation;
    }

    private Layer sideCard(String id, String label, double x, double y, double initialX, boolean left) {
        List<Layer> contents = new ArrayList<>();
        contents.add(rect(id + "-panel", "Card surface", 0, 0, 220, 180, 22, solid("#FFFFFF18")));
        contents.add(rect(id + "-accent", "Accent line", 24, 24, 46, 5, 3, solid(left ? "#A78BFA" : "#5EEAD4")));
        contents.add(text(id + "-heading", left ? "A little magic" : "Your space", 24, 48, 21, "#F6F4FF", true));
        contents.add(text(id + "-subtitle", left ? "for your everyday" : "safe and sound", 24, 78, 14, "#C6CBE1", false));
        for (int i = 0; i < 3; i++) {
            contents.add(rect(id + "-line-" + i, "Detail", 24, 112 + i * 15, 135 - i * 24, 5, 3, solid("#CFD5ED55")));
        }
        Transform place = Transform.at(x, y).withPosition(keys(
                key(0, new Vec2(initialX, y), EASE_OUT), key(2.4, new Vec2(x, y), Easing.LINEAR)));
        LayerBase card = base(id, label).withTransform(place)
                .withOpacity(keys(key(0, 0.0, EASE_OUT), key(1.3, 1.0, Easing.LINEAR)));
        return new GroupLayer(card, contents);
    }

    private Layer orb(String id, String label, double x, double y, double size, String color, double dx, double dy) {
        Transform move = Transform.at(x, y).withPosition(keys(
                key(0, new Vec2(x, y), Easing.LINEAR), key(4, new Vec2(x, y), EASE),
                key(8, new Vec2(x + dx, y + dy), EASE), key(12, new Vec2(x, y), Easing.LINEAR)));
        LayerBase glow = base(id, label).withTransform(move)
                .withEffects(List.of(new Effect.Blur(Animatable.of(60.0))));
        return new ShapeLayer(glow, new Geometry.Ellipse(Animatable.of(new Vec2(size, size))),
                Animatable.of(solid(color)), null, null);
    }

    private Layer rect(String id, String name, double x, double y, double w, double h, double radius, Paint paint) {
        return new ShapeLayer(base(id, name).withTransform(Transform.at(x, y)),
                new Geometry.Rect(Animatable.of(new Vec2(w, h)), Animatable.of(radius)),
                Animatable.of(paint), null, null);
    }

    private Layer text(String id, String value, double x, double y, double size, String colour, boolean bold) {
        return new TextLayer(base(id, value).withTransform(Transform.at(x, y)), value, "System", null,
                Animatable.of(size), bold ? TextLayer.Weight.BOLD : TextLayer.Weight.NORMAL, false, TextLayer.Align.LEFT,
                Animatable.of(solid(colour)), null);
    }

    private LayerBase base(String id, String name) {
        return LayerBase.create(name, DURATION).withId("login-" + id);
    }

    private <T> Keyframe<T> key(double time, T value, Easing easing) {
        return new Keyframe<>("login-key-" + ++key, time, value, easing);
    }

    @SafeVarargs
    private static <T> Animatable<T> keys(Keyframe<T>... values) {
        return Animatable.keyframed(List.of(values));
    }

    private static Paint solid(String colour) {
        return new Paint.Solid(Rgba.parse(colour));
    }

    private static Paint gradient(String a, String b) {
        return new Paint.LinearGradient(new Vec2(0, 0), new Vec2(1200, 800),
                List.of(new Paint.Stop(0, Rgba.parse(a)), new Paint.Stop(1, Rgba.parse(b))));
    }
}
